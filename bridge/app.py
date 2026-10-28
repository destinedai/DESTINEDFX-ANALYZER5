import os, sqlite3, secrets, hashlib, hmac, json, time, math, uuid
from datetime import datetime, timezone, timedelta
from contextlib import contextmanager
from typing import Any
from urllib.parse import urlparse
from fastapi import FastAPI, Request, HTTPException, Header, Query
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, ConfigDict
from cryptography.fernet import Fernet, InvalidToken


DB_PATH=os.getenv('BRIDGE_DB_PATH','/data/bridge.sqlite3')
ALLOW_LOOPBACK=os.getenv('ALLOW_LOOPBACK_HTTP','false').lower()=='true'
MAX_BODY=int(os.getenv('MAX_BODY_BYTES','65536'))
PAIR_RATE=int(os.getenv('PAIR_RATE_LIMIT','10'))
STALE=int(os.getenv('HEARTBEAT_STALE_SECONDS','15'))
app=FastAPI(title='DestinedFX MT5 Bridge', version='1.0')

def now(): return datetime.now(timezone.utc)
def iso(dt=None): return (dt or now()).isoformat().replace('+00:00','Z')
def digest(s): return hashlib.sha256(s.encode()).hexdigest()
def token(): return secrets.token_urlsafe(32)
def db():
    os.makedirs(os.path.dirname(os.path.abspath(DB_PATH)),exist_ok=True)
    c=sqlite3.connect(DB_PATH, timeout=10, isolation_level=None)
    c.row_factory=sqlite3.Row
    c.execute('PRAGMA journal_mode=WAL'); c.execute('PRAGMA foreign_keys=ON'); c.execute('PRAGMA busy_timeout=10000')
    c.executescript('''CREATE TABLE IF NOT EXISTS pairings(id TEXT PRIMARY KEY, code_hash TEXT NOT NULL, app_hash TEXT NOT NULL, terminal_hash TEXT, expires REAL NOT NULL, consumed INTEGER NOT NULL DEFAULT 0, terminal_id TEXT, instance_id TEXT, account TEXT, heartbeat TEXT, created REAL NOT NULL);
    CREATE TABLE IF NOT EXISTS commands(id INTEGER PRIMARY KEY AUTOINCREMENT, pairing_id TEXT NOT NULL REFERENCES pairings(id), request_id TEXT NOT NULL, action TEXT NOT NULL, config TEXT NOT NULL, ack TEXT, UNIQUE(pairing_id,request_id));
    CREATE TABLE IF NOT EXISTS rate(ip TEXT PRIMARY KEY, window REAL NOT NULL, count INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS managed_accounts(pairing_id TEXT PRIMARY KEY REFERENCES pairings(id) ON DELETE CASCADE, login_enc TEXT NOT NULL, server_enc TEXT NOT NULL, password_enc TEXT NOT NULL, access_type TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS worker_jobs(id TEXT PRIMARY KEY, pairing_id TEXT NOT NULL REFERENCES pairings(id), kind TEXT NOT NULL DEFAULT 'PROVISION', status TEXT NOT NULL, worker_id TEXT, lease_until REAL, lease_started REAL, error_code TEXT, error_message TEXT, created REAL NOT NULL);''')
    columns={r['name'] for r in c.execute('PRAGMA table_info(worker_jobs)').fetchall()}
    if 'lease_started' not in columns: c.execute('ALTER TABLE worker_jobs ADD COLUMN lease_started REAL')
    return c
@contextmanager
def tx(immediate=False):
    c=db(); c.execute('BEGIN IMMEDIATE' if immediate else 'BEGIN')
    try: yield c; c.commit()
    except: c.rollback(); raise
    finally: c.close()

def secure(request):
    if request.url.scheme=='https': return
    peer=request.client.host if request.client else ''
    if ALLOW_LOOPBACK and peer in ('127.0.0.1','::1','localhost'): return
    raise HTTPException(400,'HTTPS is required')
@app.middleware('http')
async def limits(request:Request, call_next):
    secure(request)
    if request.method in ('POST','PUT','PATCH'):
        try: length=int(request.headers.get('content-length','0'))
        except ValueError: return JSONResponse({'detail':'Invalid content length'},400)
        if length>MAX_BODY: return JSONResponse({'detail':'Request body too large'},413)
        body=bytearray()
        async for chunk in request.stream():
            if len(body)+len(chunk)>MAX_BODY: return JSONResponse({'detail':'Request body too large'},413)
            body.extend(chunk)
        request._body=bytes(body)
    return await call_next(request)
class Pair(BaseModel): model_config=ConfigDict(extra='forbid')
class ManagedAccount(BaseModel):
    model_config=ConfigDict(extra='forbid')
    login:str=Field(min_length=1,max_length=128); server:str=Field(min_length=1,max_length=128); password:str=Field(min_length=1,max_length=512); access_type:str; consent:bool
class WorkerClaim(BaseModel): model_config=ConfigDict(extra='forbid'); worker_id:str=Field(min_length=1,max_length=128)
class WorkerUpdate(BaseModel):
    model_config=ConfigDict(extra='forbid')
    status:str; worker_id:str=Field(min_length=1,max_length=128); error_code:str|None=Field(default=None,max_length=64); error_message:str|None=Field(default=None,max_length=200)

class Claim(BaseModel): model_config=ConfigDict(extra='forbid'); pairing_code:str=Field(min_length=8,max_length=64); terminal_instance_id:str=Field(min_length=1,max_length=128); account:dict[str,str]
class Command(BaseModel): model_config=ConfigDict(extra='forbid'); request_id:str=Field(min_length=1,max_length=64); action:str; config:dict[str,Any]
class Ack(BaseModel): model_config=ConfigDict(extra='forbid'); outcome:str; state:str; message:str=Field(default='',max_length=1000)
class Position(BaseModel):
    model_config=ConfigDict(extra='forbid')
    ticket:int|str; position_id:int|str; symbol:str=Field(max_length=64); direction:str=Field(max_length=16); volume:float=Field(gt=0,allow_inf_nan=False); open_price:float=Field(gt=0,allow_inf_nan=False); stop_loss:float=Field(ge=0,allow_inf_nan=False); take_profit:float=Field(ge=0,allow_inf_nan=False); floating_profit:float=Field(allow_inf_nan=False); commission:float|None=Field(default=None,allow_inf_nan=False); swap:float=Field(allow_inf_nan=False)
class ClosedDeal(BaseModel):
    model_config=ConfigDict(extra='forbid')
    ticket:int|str; position_id:int|str; symbol:str=Field(max_length=64); closed_at:str=Field(max_length=64); profit:float=Field(allow_inf_nan=False); commission:float=Field(allow_inf_nan=False); swap:float=Field(allow_inf_nan=False)
class Account(BaseModel):
    model_config=ConfigDict(extra='forbid')
    login:str=Field(min_length=1,max_length=128); server:str=Field(min_length=1,max_length=128); company:str=Field(min_length=1,max_length=128); currency:str=Field(min_length=1,max_length=16)
    balance:float|None=Field(default=None,gt=0,allow_inf_nan=False); equity:float|None=Field(default=None,gt=0,allow_inf_nan=False); margin:float|None=Field(default=None,ge=0,allow_inf_nan=False); free_margin:float|None=Field(default=None,ge=0,allow_inf_nan=False); margin_level:float|None=Field(default=None,ge=0,allow_inf_nan=False); leverage:int|None=Field(default=None,gt=0)
class Heartbeat(BaseModel):
    model_config=ConfigDict(extra='forbid')
    state:str; timestamp:str; terminal_connected:bool; trade_allowed:bool; account:Account; configuration:dict[str,Any]=Field(default_factory=dict); last_signal:Any=None; last_error:str=Field(default='',max_length=1000); open_positions:list[Position]; closed_deals:list[ClosedDeal]

def auth(header, kind):
    if not header or not header.startswith('Bearer '): raise HTTPException(401,'Bearer token required')
    raw=header[7:]
    if len(raw)>200: raise HTTPException(401,'Unauthorized')
    field='app_hash' if kind=='app' else 'terminal_hash'
    with tx() as c:
        rows=c.execute(f'SELECT * FROM pairings WHERE {field} IS NOT NULL').fetchall()
    for r in rows:
        if hmac.compare_digest(r[field],digest(raw)):
            if kind=='app' or (r['consumed'] and r['terminal_hash']): return dict(r)
    raise HTTPException(401,'Unauthorized')
def check_json(v, depth=0):
    if depth>8: raise HTTPException(422,'Data nesting exceeds limit')
    if isinstance(v,float) and not math.isfinite(v): raise HTTPException(422,'Non-finite numbers are not allowed')
    if isinstance(v,dict):
        if len(v)>100: raise HTTPException(422,'Too many fields')
        for k,x in v.items():
            if len(str(k))>128: raise HTTPException(422,'Field name too long')
            check_json(x,depth+1)
    elif isinstance(v,list):
        if len(v)>1000: raise HTTPException(422,'Too many list items')
        for x in v: check_json(x,depth+1)

def validate_origin(raw, allow_loopback=False):
    value=(raw or '').strip().rstrip('/')
    parsed=urlparse(value)
    host=(parsed.hostname or '').lower().rstrip('.')
    loopback=host in ('localhost','127.0.0.1','::1')
    placeholder=(host in {'example.com','example.org','example.net','testserver','bridge.example'} or host.endswith('.example') or host.startswith('your-') or host.startswith('placeholder') or 'example' in host)
    if not host or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('','/'):
        return None
    if parsed.scheme=='http':
        if not (allow_loopback and loopback): return None
    elif parsed.scheme!='https' or loopback or placeholder:
        return None
    return f'{parsed.scheme}://{host}' + (f':{parsed.port}' if parsed.port else '')

def managed_config():
    key=os.getenv('CREDENTIALS_FERNET_KEY','')
    worker=os.getenv('WORKER_TOKEN','')
    bridge_url=os.getenv('BRIDGE_BASE_URL','').strip()
    if not key or len(worker)<32 or len(worker)>512 or not validate_origin(bridge_url, allow_loopback=ALLOW_LOOPBACK):
        raise HTTPException(503, detail={'code':'CONFIGURATION_REQUIRED','message':'Managed execution is not configured'})
    try: return Fernet(key.encode()), worker
    except Exception: raise HTTPException(503, detail={'code':'CONFIGURATION_REQUIRED','message':'Managed execution is not configured'})
def worker_auth(header):
    _, secret=managed_config()
    if not header or not header.startswith('Bearer ') or not hmac.compare_digest(header[7:],secret): raise HTTPException(401,'Unauthorized')
def fresh_heartbeat(p):
    if not p or not p['heartbeat']: return None
    try:
        b=json.loads(p['heartbeat']); dt=datetime.fromisoformat(b['timestamp'].replace('Z','+00:00'))
        return b if 0 <= (now()-dt.astimezone(timezone.utc)).total_seconds() <= STALE else None
    except Exception: return None

@app.post('/v1/app/managed-account',status_code=202)
def managed_account(x:ManagedAccount, authorization:str|None=Header(default=None)):
    p=auth(authorization,'app'); fernet,_=managed_config()
    if not x.consent or x.access_type not in ('MASTER','INVESTOR') or not x.login.isdigit() or any(ord(ch)<32 or ord(ch)==127 for ch in x.server+x.password): raise HTTPException(422,'Consent, account number, and valid access_type are required')
    with tx(True) as c:
        if c.execute('SELECT 1 FROM managed_accounts WHERE pairing_id=?',(p['id'],)).fetchone(): raise HTTPException(409,'Managed account already exists for this pairing')
        pairing_code=secrets.token_urlsafe(18); job_id=str(uuid.uuid4()); t=time.time()
        c.execute('INSERT INTO managed_accounts VALUES(?,?,?,?,?,0)',(p['id'],fernet.encrypt(x.login.encode()).decode(),fernet.encrypt(x.server.encode()).decode(),fernet.encrypt(x.password.encode()).decode(),x.access_type))
        c.execute('UPDATE pairings SET code_hash=?,expires=?,consumed=0,terminal_hash=NULL,terminal_id=NULL,instance_id=NULL,account=NULL,heartbeat=NULL WHERE id=?',(digest(pairing_code),t+86400,p['id']))
        c.execute("INSERT INTO worker_jobs(id,pairing_id,kind,status,created) VALUES(?,?,'PROVISION','QUEUED',?)",(job_id,p['id'],t))
    return {'job_id':job_id,'status':'QUEUED'}

@app.post('/v1/worker/jobs/claim')
def worker_claim(x:WorkerClaim, authorization:str|None=Header(default=None)):
    worker_auth(authorization); fernet,_=managed_config(); t=time.time()
    with tx(True) as c:
        r=c.execute("SELECT * FROM worker_jobs WHERE status='QUEUED' OR (status IN ('LEASED','PROVISIONING','AUTHENTICATING') AND lease_until<?) ORDER BY created LIMIT 1",(t,)).fetchone()
        if not r: return {'job':None}
        c.execute("UPDATE worker_jobs SET status='LEASED',worker_id=?,lease_until=?,lease_started=? WHERE id=?",(x.worker_id,t+120,t,r['id']))
        if r['kind']=='REVOKE': return {'job_id':r['id'],'kind':'REVOKE','pairing_id':r['pairing_id'],'worker_id':x.worker_id}
        p=c.execute('SELECT * FROM pairings WHERE id=?',(r['pairing_id'],)).fetchone()
        m=c.execute('SELECT * FROM managed_accounts WHERE pairing_id=? AND revoked=0',(r['pairing_id'],)).fetchone()
        if not m:
            c.execute("UPDATE worker_jobs SET status='FAILED',error_code='ACCOUNT_REVOKED',error_message='Account was revoked before provisioning' WHERE id=?",(r['id'],))
            return {'job':None}
        account={k:fernet.decrypt(m[k+'_enc'].encode()).decode() for k in ('login','server','password')}; account['access_type']=m['access_type']
        code_row=c.execute('SELECT code_hash FROM pairings WHERE id=?',(r['pairing_id'],)).fetchone()
        # Existing plaintext code is intentionally not persisted; generate a fresh one for the worker and rotate hash.
        code=secrets.token_urlsafe(18); c.execute('UPDATE pairings SET code_hash=?,expires=? WHERE id=?',(digest(code),t+86400,r['pairing_id']))
        return {'job_id':r['id'],'kind':'PROVISION','worker_id':x.worker_id,'pairing_id':r['pairing_id'],'pairing_code':code,'bridge_url':os.getenv('BRIDGE_BASE_URL',''),'account':account}

@app.patch('/v1/worker/jobs/{job_id}')
def worker_update(job_id:str,x:WorkerUpdate,authorization:str|None=Header(default=None)):
    worker_auth(authorization)
    if x.status not in ('PROVISIONING','AUTHENTICATING','READY','FAILED','REVOKED'): raise HTTPException(422,'Invalid provisioning status')
    # Only expose constrained operational codes; never reflect arbitrary worker text.
    code=(x.error_code or '')[:64]
    safe_messages={'terminal_auth_failed':'Terminal authentication failed','bridge_unavailable':'Bridge unavailable','terminal_launch_failed':'Terminal launch failed','REVOKE_PROCESS_ID_MISMATCH':'Terminal process identity could not be verified','REVOKE_PROCESS_ID_MISSING':'Terminal process identity is missing','REVOKE_MARKER_NOT_FOUND':'Terminal ownership marker was not found; operator review is required','REVOKE_CLEANUP_FAILED':'Terminal cleanup could not be verified','ACCOUNT_REVOKED':'Account was revoked before provisioning'}
    message=safe_messages.get(code,'')
    with tx(True) as c:
        r=c.execute('SELECT * FROM worker_jobs WHERE id=?',(job_id,)).fetchone()
        if not r: raise HTTPException(404,'Job not found')
        if r['worker_id']!=x.worker_id: raise HTTPException(403,'Worker does not own this job lease')
        if r['lease_until'] is None or r['lease_until']<time.time(): raise HTTPException(409,'Worker job lease expired; reclaim the job before continuing')
        if r['kind']=='REVOKE' and x.status not in ('REVOKED','FAILED'): raise HTTPException(422,'Revocation job must be acknowledged as REVOKED or FAILED')
        if r['kind']!='REVOKE' and x.status=='REVOKED': raise HTTPException(422,'Only a revocation job can be marked REVOKED')
        if r['status']=='READY' and x.status in ('PROVISIONING','AUTHENTICATING'): return {'updated':True}
        if r['status'] not in ('LEASED','PROVISIONING','AUTHENTICATING'): raise HTTPException(409,'Job is not active')
        lease=time.time()+120 if x.status in ('PROVISIONING','AUTHENTICATING','LEASED') else r['lease_until']
        c.execute('UPDATE worker_jobs SET status=?,lease_until=?,error_code=?,error_message=? WHERE id=?',(x.status,lease,code or None,message or None,job_id))
    return {'updated':True}

@app.get('/v1/worker/jobs/{job_id}/status')
def worker_job_status(job_id:str,worker_id:str=Query(min_length=1,max_length=128),authorization:str|None=Header(default=None)):
    worker_auth(authorization)
    with tx(True) as c:
        job=c.execute('SELECT * FROM worker_jobs WHERE id=?',(job_id,)).fetchone()
        if not job: raise HTTPException(404,'Job not found')
        if job['worker_id']!=worker_id: raise HTTPException(403,'Worker does not own this job lease')
        t=time.time()
        if job['lease_until'] is None or job['lease_until']<t: raise HTTPException(409,'Worker job lease expired; reclaim the job before continuing')
        if job['status'] in ('LEASED','PROVISIONING','AUTHENTICATING'):
            c.execute('UPDATE worker_jobs SET lease_until=? WHERE id=?',(t+120,job_id))
            job=c.execute('SELECT * FROM worker_jobs WHERE id=?',(job_id,)).fetchone()
        pairing=c.execute('SELECT * FROM pairings WHERE id=?',(job['pairing_id'],)).fetchone()
        managed=c.execute('SELECT access_type,revoked FROM managed_accounts WHERE pairing_id=?',(job['pairing_id'],)).fetchone()
        if job['kind']=='PROVISION' and job['status'] in ('PROVISIONING','AUTHENTICATING') and managed and not managed['revoked']:
            beat=fresh_heartbeat(pairing)
            valid=bool(beat and beat.get('state')=='READY' and beat.get('terminal_connected') and (managed['access_type']=='INVESTOR' or beat.get('trade_allowed')))
            if valid:
                c.execute("UPDATE worker_jobs SET status='READY',error_code=NULL,error_message=NULL WHERE id=?",(job_id,))
                job=c.execute('SELECT * FROM worker_jobs WHERE id=?',(job_id,)).fetchone()
            elif time.time()-(job['lease_started'] if job['lease_started'] else job['created'])>300:
                c.execute("UPDATE worker_jobs SET status='FAILED',error_code='TERMINAL_AUTH_TIMEOUT',error_message='Terminal did not authenticate in time' WHERE id=?",(job_id,))
                job=c.execute('SELECT * FROM worker_jobs WHERE id=?',(job_id,)).fetchone()
    return {'job_id':job_id,'kind':job['kind'],'status':job['status'],'error_code':job['error_code'],'error_message':job['error_message']}

@app.get('/health')
def health(): return {'status':'ok'}
@app.post('/v1/pairings',status_code=201)
def create_pair(request:Request):
    ip=request.client.host if request.client else 'unknown'; t=time.time()
    with tx(True) as c:
        row=c.execute('SELECT * FROM rate WHERE ip=?',(ip,)).fetchone()
        if row and t-row['window']<60 and row['count']>=PAIR_RATE: raise HTTPException(429,'Pairing rate limit exceeded')
        if row and t-row['window']<60: c.execute('UPDATE rate SET count=count+1 WHERE ip=?',(ip,))
        else: c.execute('INSERT OR REPLACE INTO rate VALUES(?,?,1)',(ip,t))
        pairing_id=str(uuid.uuid4()); code=secrets.token_urlsafe(12); app_token=token(); exp=t+600
        c.execute('INSERT INTO pairings(id,code_hash,app_hash,expires,created) VALUES(?,?,?,?,?)',(pairing_id,digest(code),digest(app_token),exp,t))
    return {'pairing_id':pairing_id,'pairing_code':code,'app_token':app_token,'expires_at':iso(datetime.fromtimestamp(exp,timezone.utc))}
@app.post('/v1/terminal/claim')
def claim(x:Claim):
    for k in ('login','server','company','currency'):
        if k not in x.account or not x.account[k] or len(x.account[k])>128: raise HTTPException(422,'Invalid account identity')
    terminal_token=token(); terminal_id=str(uuid.uuid4()); t=time.time()
    with tx(True) as c:
        rows=c.execute('SELECT * FROM pairings WHERE consumed=0 AND expires>?',(t,)).fetchall()
        found=next((r for r in rows if hmac.compare_digest(r['code_hash'],digest(x.pairing_code))),None)
        if found is None: raise HTTPException(401,'Invalid or expired pairing code')
        managed=c.execute('SELECT login_enc,server_enc FROM managed_accounts WHERE pairing_id=? AND revoked=0',(found['id'],)).fetchone()
        if managed:
            fernet,_=managed_config()
            expected_login=fernet.decrypt(managed['login_enc'].encode()).decode()
            expected_server=fernet.decrypt(managed['server_enc'].encode()).decode()
            if x.account['login']!=expected_login or x.account['server'].casefold()!=expected_server.casefold():
                raise HTTPException(401,'Authenticated terminal account does not match managed account')
        c.execute('UPDATE pairings SET consumed=1,terminal_hash=?,terminal_id=?,instance_id=?,account=? WHERE id=? AND consumed=0',(digest(terminal_token),terminal_id,x.terminal_instance_id,json.dumps(x.account,separators=(',',':')),found['id']))
        if c.total_changes<1: raise HTTPException(409,'Pairing code already consumed')
    return {'terminal_id':terminal_id,'terminal_token':terminal_token,'poll_seconds':2}
@app.get('/v1/app/state')
def state(authorization:str|None=Header(default=None)):
    p=auth(authorization,'app'); beat=json.loads(p['heartbeat']) if p['heartbeat'] else None
    age=max(0,int(time.time()-datetime.fromisoformat(beat['timestamp'].replace('Z','+00:00')).timestamp())) if beat else None
    online=bool(beat and age is not None and age<=STALE)
    with tx() as c: cmd=c.execute('SELECT * FROM commands WHERE pairing_id=? ORDER BY id DESC LIMIT 1',(p['id'],)).fetchone()
    last_command=None
    if cmd:
        ack=json.loads(cmd['ack']) if cmd['ack'] else None
        last_command={'id':cmd['id'],'request_id':cmd['request_id'],'action':cmd['action'],'status':'ACKNOWLEDGED' if ack else 'QUEUED',**(ack or {})}
    with tx() as c:
        m=c.execute('SELECT access_type,revoked FROM managed_accounts WHERE pairing_id=?',(p['id'],)).fetchone()
        job=c.execute('SELECT status,error_code,error_message FROM worker_jobs WHERE pairing_id=? ORDER BY created DESC LIMIT 1',(p['id'],)).fetchone()
    managed=None
    if m:
        actual=fresh_heartbeat(p); worker_ready=bool(job and job['status']=='READY')
        app_status='READY' if actual and worker_ready else ('STALE' if worker_ready and p['heartbeat'] else ('AUTHENTICATING' if worker_ready else (job['status'] if job else 'QUEUED')))
        managed={'status':app_status,'access_type':m['access_type'],'terminal_fresh':bool(actual),'trade_allowed':bool(actual and actual.get('trade_allowed')),'error_code':job['error_code'] if job else None,'error_message':job['error_message'] if job else None}
    result={'pairing_status':'PAIRED' if p['consumed'] else 'PAIRING','terminal_online':online,'heartbeat_age_seconds':age,'heartbeat_stale_seconds':STALE,'ea_state':beat['state'] if online else 'OFFLINE','terminal_connected':beat['terminal_connected'] if online else False,'trade_allowed':beat['trade_allowed'] if online else False,'account':beat['account'] if online else None,'configuration':beat['configuration'] if online else {},'last_signal':beat['last_signal'] if online else None,'last_error':beat['last_error'] if online else ('Terminal heartbeat is stale or missing' if p['heartbeat'] else ''),'open_positions':beat['open_positions'] if online else None,'closed_deals':beat['closed_deals'] if online else None,'last_heartbeat_at':beat['timestamp'] if beat else None,'last_command':last_command}
    if managed is not None: result['managed_execution']=managed
    return result
@app.post('/v1/app/commands',status_code=202)
def command(x:Command,authorization:str|None=Header(default=None)):
    p=auth(authorization,'app'); check_json(x.config)
    if x.action not in ('START','STOP','PAUSE','RESUME','EMERGENCY_STOP'): raise HTTPException(422,'Invalid action')
    try: uuid.UUID(x.request_id)
    except ValueError: raise HTTPException(422,'request_id must be a UUID')
    with tx() as c:
        m=c.execute('SELECT access_type,revoked FROM managed_accounts WHERE pairing_id=?',(p['id'],)).fetchone()
    if x.action in ('START','RESUME'):
        if not m: raise HTTPException(409,'Managed account provisioning is required before trading can be enabled')
        beat=fresh_heartbeat(p)
        with tx() as c: job=c.execute("SELECT status FROM worker_jobs WHERE pairing_id=? ORDER BY created DESC LIMIT 1",(p['id'],)).fetchone()
        if m['revoked'] or m['access_type']=='INVESTOR' or not beat or beat.get('state') not in ('READY','STOPPED','PAUSED') or not beat.get('trade_allowed') or not job or job['status']!='READY': raise HTTPException(409,'Managed execution is not authorized to start or resume')
    serialized=json.dumps(x.config,separators=(',',':'),allow_nan=False)
    with tx(True) as c:
        c.execute('INSERT OR IGNORE INTO commands(pairing_id,request_id,action,config) VALUES(?,?,?,?)',(p['id'],x.request_id,x.action,serialized))
        r=c.execute('SELECT * FROM commands WHERE pairing_id=? AND request_id=?',(p['id'],x.request_id)).fetchone()
        if r['action']!=x.action or r['config']!=serialized: raise HTTPException(409,'request_id was already used for a different command payload')
    return {'id':r['id'],'request_id':r['request_id'],'action':r['action'],'config':json.loads(r['config']),'status':'acknowledged' if r['ack'] else 'queued'}
@app.get('/v1/terminal/commands')
def poll(cursor:int=Query(default=0,ge=0),authorization:str|None=Header(default=None)):
    p=auth(authorization,'terminal')
    with tx() as c: rows=c.execute('SELECT * FROM commands WHERE pairing_id=? AND (id>? OR ack IS NULL) ORDER BY id',(p['id'],cursor)).fetchall()
    commands=[{'id':r['id'],'request_id':r['request_id'],'action':r['action'],'config':json.loads(r['config'])} for r in rows]
    return {'cursor':max([cursor]+[r['id'] for r in rows]),'commands':commands}
@app.post('/v1/terminal/commands/{command_id}/ack')
def acknowledge(command_id:int,x:Ack,authorization:str|None=Header(default=None)):
    p=auth(authorization,'terminal')
    if x.outcome not in ('APPLIED','REJECTED') or x.state not in ('READY','RUNNING','PAUSED','STOPPED','ERROR'): raise HTTPException(422,'Invalid acknowledgement')
    with tx(True) as c:
        r=c.execute('SELECT * FROM commands WHERE id=? AND pairing_id=?',(command_id,p['id'])).fetchone()
        if not r: raise HTTPException(404,'Command not found')
        expected_state={'START':'RUNNING','RESUME':'RUNNING','PAUSE':'PAUSED','STOP':'STOPPED','EMERGENCY_STOP':'STOPPED'}.get(r['action'])
        if x.outcome=='APPLIED' and x.state!=expected_state: raise HTTPException(422,'Applied acknowledgement state does not match command action')
        ack=json.dumps({'outcome':x.outcome,'state':x.state,'message':x.message},separators=(',',':'))
        if r['ack'] and r['ack']!=ack: raise HTTPException(409,'Command already acknowledged differently')
        c.execute('UPDATE commands SET ack=? WHERE id=?',(ack,command_id))
    return {'id':command_id,'acknowledged':True}
@app.post('/v1/terminal/heartbeat')
def heartbeat(x:Heartbeat,authorization:str|None=Header(default=None)):
    p=auth(authorization,'terminal'); check_json(x.model_dump())
    if x.state not in ('READY','RUNNING','PAUSED','STOPPED','ERROR'): raise HTTPException(422,'Invalid state')
    try: dt=datetime.fromisoformat(x.timestamp.replace('Z','+00:00'))
    except Exception: raise HTTPException(422,'Invalid UTC timestamp')
    if dt.tzinfo is None or abs((now()-dt.astimezone(timezone.utc)).total_seconds())>STALE: raise HTTPException(422,'Stale or future heartbeat timestamp')
    expected=json.loads(p['account'])
    if any(getattr(x.account,k)!=expected.get(k) for k in ('login','server','company','currency')): raise HTTPException(409,'Terminal account identity mismatch')
    payload=x.model_dump(); payload['account']=x.account.model_dump(); payload['open_positions']=[v.model_dump() for v in x.open_positions]; payload['closed_deals']=[v.model_dump() for v in x.closed_deals]; payload['timestamp']=iso(dt.astimezone(timezone.utc))
    with tx(True) as c:
        c.execute('UPDATE pairings SET heartbeat=? WHERE id=?',(json.dumps(payload,separators=(',',':'),allow_nan=False),p['id']))
        managed=c.execute('SELECT access_type,revoked FROM managed_accounts WHERE pairing_id=?',(p['id'],)).fetchone()
        if managed and not managed['revoked'] and x.state=='READY' and x.terminal_connected and (managed['access_type']=='INVESTOR' or x.trade_allowed):
            c.execute("UPDATE worker_jobs SET status='READY',error_code=NULL,error_message=NULL WHERE pairing_id=? AND kind='PROVISION' AND status IN ('LEASED','PROVISIONING','AUTHENTICATING')",(p['id'],))
    return {'accepted':True,'received_at':iso()}
@app.delete('/v1/app/pairing')
def revoke(authorization:str|None=Header(default=None)):
    p=auth(authorization,'app')
    with tx(True) as c:
        c.execute('UPDATE managed_accounts SET revoked=1,login_enc=?,server_enc=?,password_enc=? WHERE pairing_id=?',('', '', '', p['id']))
        c.execute("INSERT INTO worker_jobs(id,pairing_id,kind,status,created) SELECT ?,id,'REVOKE','QUEUED',? FROM pairings WHERE id=?",(str(uuid.uuid4()),time.time(),p['id']))
        c.execute('UPDATE pairings SET app_hash=?,terminal_hash=NULL,consumed=1,heartbeat=NULL WHERE id=?',(digest(secrets.token_urlsafe(32)),p['id']))
        c.execute('DELETE FROM commands WHERE pairing_id=?',(p['id'],))
    return {'revoked':True,'deprovision_queued':True}

import os, sqlite3, secrets, hashlib, hmac, json, time, math, uuid
from datetime import datetime, timezone, timedelta
from contextlib import contextmanager
from typing import Any
from fastapi import FastAPI, Request, HTTPException, Header, Query
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, ConfigDict

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
    CREATE TABLE IF NOT EXISTS rate(ip TEXT PRIMARY KEY, window REAL NOT NULL, count INTEGER NOT NULL);''')
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
class Claim(BaseModel): model_config=ConfigDict(extra='forbid'); pairing_code:str=Field(min_length=8,max_length=64); terminal_instance_id:str=Field(min_length=1,max_length=128); account:dict[str,str]
class Command(BaseModel): model_config=ConfigDict(extra='forbid'); request_id:str=Field(min_length=1,max_length=64); action:str; config:dict[str,Any]
class Ack(BaseModel): model_config=ConfigDict(extra='forbid'); outcome:str; state:str; message:str=Field(default='',max_length=1000)
class Position(BaseModel):
    model_config=ConfigDict(extra='forbid')
    ticket:int|str; position_id:int|str; symbol:str=Field(max_length=64); direction:str=Field(max_length=16); volume:float; open_price:float; stop_loss:float; take_profit:float; floating_profit:float; commission:float|None=None; swap:float
class ClosedDeal(BaseModel):
    model_config=ConfigDict(extra='forbid')
    ticket:int|str; position_id:int|str; symbol:str=Field(max_length=64); closed_at:str=Field(max_length=64); profit:float; commission:float; swap:float
class Account(BaseModel):
    model_config=ConfigDict(extra='forbid')
    login:str=Field(min_length=1,max_length=128); server:str=Field(min_length=1,max_length=128); company:str=Field(min_length=1,max_length=128); currency:str=Field(min_length=1,max_length=16)
    balance:float|None=None; equity:float|None=None; margin:float|None=None; free_margin:float|None=None; margin_level:float|None=None; leverage:int|None=None
class Heartbeat(BaseModel):
    model_config=ConfigDict(extra='forbid')
    state:str; timestamp:str; terminal_connected:bool; trade_allowed:bool; account:Account; configuration:dict[str,Any]=Field(default_factory=dict); last_signal:Any=None; last_error:str=Field(default='',max_length=1000); open_positions:list[Position]=Field(default_factory=list); closed_deals:list[ClosedDeal]=Field(default_factory=list)

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
    return {'pairing_status':'PAIRED' if p['consumed'] else 'PAIRING','terminal_online':online,'heartbeat_age_seconds':age,'heartbeat_stale_seconds':STALE,'ea_state':beat['state'] if online else 'OFFLINE','terminal_connected':beat['terminal_connected'] if online else False,'trade_allowed':beat['trade_allowed'] if online else False,'account':beat['account'] if online else None,'configuration':beat['configuration'] if online else {},'last_signal':beat['last_signal'] if online else None,'last_error':beat['last_error'] if online else ('Terminal heartbeat is stale or missing' if p['heartbeat'] else ''),'open_positions':beat['open_positions'] if online else [],'closed_deals':beat['closed_deals'] if online else [],'last_heartbeat_at':beat['timestamp'] if beat else None,'last_command':last_command}
@app.post('/v1/app/commands',status_code=202)
def command(x:Command,authorization:str|None=Header(default=None)):
    p=auth(authorization,'app'); check_json(x.config)
    if x.action not in ('START','STOP','PAUSE','RESUME','EMERGENCY_STOP'): raise HTTPException(422,'Invalid action')
    try: uuid.UUID(x.request_id)
    except ValueError: raise HTTPException(422,'request_id must be a UUID')
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
    with tx(True) as c: c.execute('UPDATE pairings SET heartbeat=? WHERE id=?',(json.dumps(payload,separators=(',',':'),allow_nan=False),p['id']))
    return {'accepted':True,'received_at':iso()}
@app.delete('/v1/app/pairing')
def revoke(authorization:str|None=Header(default=None)):
    p=auth(authorization,'app')
    with tx(True) as c:
        c.execute('UPDATE pairings SET app_hash=?,terminal_hash=NULL,consumed=1,heartbeat=NULL WHERE id=?',(digest(secrets.token_urlsafe(32)),p['id']))
        c.execute('DELETE FROM commands WHERE pairing_id=?',(p['id'],))
    return {'revoked':True}

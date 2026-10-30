import os, sqlite3, secrets, hashlib, hmac, json, time, math, uuid, ipaddress, urllib.request, urllib.error
from datetime import datetime, timezone, timedelta
from contextlib import contextmanager
from typing import Any
from urllib.parse import urlparse, quote, urlencode
from fastapi import FastAPI, Request, HTTPException, Header, Query
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, ConfigDict, StrictBool, StrictInt, StrictStr
from cryptography.fernet import Fernet, InvalidToken


DB_PATH=os.getenv('BRIDGE_DB_PATH','/data/bridge.sqlite3')
ALLOW_LOOPBACK=os.getenv('ALLOW_LOOPBACK_HTTP','false').lower()=='true'
MAX_BODY=int(os.getenv('MAX_BODY_BYTES','65536'))
PAIR_RATE=int(os.getenv('PAIR_RATE_LIMIT','10'))
STALE=int(os.getenv('HEARTBEAT_STALE_SECONDS','15'))
RECOVERY_GRACE=int(os.getenv('HEARTBEAT_RECOVERY_GRACE_SECONDS','120'))
NEWS_BEFORE=int(os.getenv('NEWS_BLACKOUT_BEFORE_SECONDS','1800'))
NEWS_AFTER=int(os.getenv('NEWS_BLACKOUT_AFTER_SECONDS','1800'))
NEWS_MAX_AGE=min(120,max(1,int(os.getenv('NEWS_MAX_AGE_SECONDS','120'))))
NEWS_CACHE_TTL=min(NEWS_MAX_AGE,max(1,int(os.getenv('NEWS_CACHE_TTL_SECONDS','10'))))
NEWS_PROVIDER_URL=os.getenv('NEWS_PROVIDER_URL','').strip()
NEWS_PROVIDER_TOKEN=os.getenv('NEWS_PROVIDER_TOKEN','').strip()
_NEWS_CACHE={}
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
    c.executescript('''CREATE TABLE IF NOT EXISTS pairings(id TEXT PRIMARY KEY, code_hash TEXT NOT NULL, app_hash TEXT NOT NULL, terminal_hash TEXT, expires REAL NOT NULL, consumed INTEGER NOT NULL DEFAULT 0, terminal_id TEXT, instance_id TEXT, account TEXT, heartbeat TEXT, created REAL NOT NULL, live_authorized_until REAL, live_authorized_login TEXT, live_authorized_server TEXT, heartbeat_seq INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS commands(id INTEGER PRIMARY KEY AUTOINCREMENT, pairing_id TEXT NOT NULL REFERENCES pairings(id), request_id TEXT NOT NULL, action TEXT NOT NULL, config TEXT NOT NULL, ack TEXT, expires_at REAL, request_config TEXT, UNIQUE(pairing_id,request_id));
    CREATE TABLE IF NOT EXISTS close_all_confirmations(token_hash TEXT PRIMARY KEY, pairing_id TEXT NOT NULL REFERENCES pairings(id) ON DELETE CASCADE, account_mode TEXT NOT NULL, login TEXT NOT NULL, server TEXT NOT NULL, expires_at REAL NOT NULL, used INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS command_items(command_id INTEGER NOT NULL REFERENCES commands(id) ON DELETE CASCADE, ticket TEXT NOT NULL, target_id TEXT NOT NULL, target_kind TEXT NOT NULL, target_volume REAL NOT NULL DEFAULT 0.0, remaining_volume REAL, ack_status TEXT, message TEXT NOT NULL DEFAULT '', operational_status TEXT NOT NULL DEFAULT 'PENDING', ack_heartbeat_seq INTEGER, PRIMARY KEY(command_id,ticket));
    CREATE TABLE IF NOT EXISTS rate(ip TEXT PRIMARY KEY, window REAL NOT NULL, count INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS managed_accounts(pairing_id TEXT PRIMARY KEY REFERENCES pairings(id) ON DELETE CASCADE, login_enc TEXT NOT NULL, server_enc TEXT NOT NULL, password_enc TEXT NOT NULL, access_type TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0, account_mode TEXT NOT NULL DEFAULT 'UNVERIFIED');
    CREATE TABLE IF NOT EXISTS worker_jobs(id TEXT PRIMARY KEY, pairing_id TEXT NOT NULL REFERENCES pairings(id), kind TEXT NOT NULL DEFAULT 'PROVISION', status TEXT NOT NULL, worker_id TEXT, lease_until REAL, lease_started REAL, stale_since REAL, recovery_deadline REAL, error_code TEXT, error_message TEXT, created REAL NOT NULL);
    CREATE TABLE IF NOT EXISTS trade_ledger(pairing_id TEXT NOT NULL REFERENCES pairings(id) ON DELETE CASCADE, identity TEXT NOT NULL, account_mode TEXT NOT NULL, position_id TEXT, order_ticket TEXT, deal_ticket TEXT, symbol TEXT NOT NULL, direction TEXT, status TEXT NOT NULL, payload TEXT NOT NULL, first_seen REAL NOT NULL, updated REAL NOT NULL, PRIMARY KEY(pairing_id,identity));
    CREATE TABLE IF NOT EXISTS trade_ledger_events(id INTEGER PRIMARY KEY AUTOINCREMENT, pairing_id TEXT NOT NULL REFERENCES pairings(id) ON DELETE CASCADE, identity TEXT NOT NULL, account_mode TEXT NOT NULL, position_id TEXT, order_ticket TEXT, deal_ticket TEXT, symbol TEXT NOT NULL, direction TEXT, status TEXT NOT NULL, payload TEXT NOT NULL, signature TEXT NOT NULL, created REAL NOT NULL);
    CREATE INDEX IF NOT EXISTS idx_trade_ledger_events_pair_cursor ON trade_ledger_events(pairing_id,id);''')
    pairing_columns={r['name'] for r in c.execute('PRAGMA table_info(pairings)').fetchall()}
    for name, definition in (('live_authorized_until','REAL'),('live_authorized_login','TEXT'),('live_authorized_server','TEXT'),('heartbeat_seq','INTEGER NOT NULL DEFAULT 0')):
        if name not in pairing_columns: c.execute(f'ALTER TABLE pairings ADD COLUMN {name} {definition}')
    command_columns={r['name'] for r in c.execute('PRAGMA table_info(commands)').fetchall()}
    for name, definition in (('expires_at','REAL'),('request_config','TEXT')):
        if name not in command_columns: c.execute(f'ALTER TABLE commands ADD COLUMN {name} {definition}')
    item_columns={r['name'] for r in c.execute('PRAGMA table_info(command_items)').fetchall()}
    for name, definition in (('target_volume','REAL NOT NULL DEFAULT 0.0'),('remaining_volume','REAL')):
        if name not in item_columns: c.execute(f'ALTER TABLE command_items ADD COLUMN {name} {definition}')
    managed_columns={r['name'] for r in c.execute('PRAGMA table_info(managed_accounts)').fetchall()}
    if 'account_mode' not in managed_columns: c.execute("ALTER TABLE managed_accounts ADD COLUMN account_mode TEXT NOT NULL DEFAULT 'UNVERIFIED'")
    for name, definition in (('cost_model',"TEXT NOT NULL DEFAULT 'OBSERVED_ROUND_TURN'"),('commission_per_lot','REAL NOT NULL DEFAULT 0.0'),('cost_evidence',"TEXT NOT NULL DEFAULT ''")):
        if name not in managed_columns: c.execute(f'ALTER TABLE managed_accounts ADD COLUMN {name} {definition}')
    columns={r['name'] for r in c.execute('PRAGMA table_info(worker_jobs)').fetchall()}
    if 'lease_started' not in columns: c.execute('ALTER TABLE worker_jobs ADD COLUMN lease_started REAL')
    if 'stale_since' not in columns: c.execute('ALTER TABLE worker_jobs ADD COLUMN stale_since REAL')
    if 'recovery_deadline' not in columns: c.execute('ALTER TABLE worker_jobs ADD COLUMN recovery_deadline REAL')
    return c
@contextmanager
def tx(immediate=False):
    c=db(); c.execute('BEGIN IMMEDIATE' if immediate else 'BEGIN')
    try: yield c; c.commit()
    except: c.rollback(); raise
    finally: c.close()

def secure(request):
    if request.url.scheme=='https': return
    # Render terminates public TLS and forwards HTTP to its private container.
    # Trust forwarded HTTPS only when the server-generated origin and Host match.
    render_origin=os.getenv('RENDER_EXTERNAL_URL','').strip()
    forwarded_proto=request.headers.get('x-forwarded-proto','').split(',')[0].strip().lower()
    if render_origin.lower().startswith('https://') and forwarded_proto=='https':
        expected_host=(urlparse(render_origin).hostname or '').lower().rstrip('.')
        actual_host=request.headers.get('host','').split(':')[0].strip('[]').lower().rstrip('.')
        if expected_host and actual_host==expected_host: return
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
    login:str=Field(min_length=1,max_length=20); server:str=Field(min_length=1,max_length=128); password:str=Field(min_length=1,max_length=512); access_type:str; account_mode:str; consent:bool
    cost_model:str=Field(default='OBSERVED_ROUND_TURN',min_length=1,max_length=40)
    commission_per_lot:float=Field(default=0.0,ge=0,allow_inf_nan=False)
    cost_evidence:str=Field(default='',max_length=512)
class WorkerClaim(BaseModel): model_config=ConfigDict(extra='forbid'); worker_id:str=Field(min_length=1,max_length=128)
class WorkerUpdate(BaseModel):
    model_config=ConfigDict(extra='forbid')
    status:str; worker_id:str=Field(min_length=1,max_length=128); error_code:str|None=Field(default=None,max_length=64); error_message:str|None=Field(default=None,max_length=200)

class Claim(BaseModel): model_config=ConfigDict(extra='forbid'); pairing_code:str=Field(min_length=8,max_length=64); terminal_instance_id:str=Field(min_length=1,max_length=128); account:dict[str,str]
class Command(BaseModel): model_config=ConfigDict(extra='forbid'); request_id:str=Field(min_length=1,max_length=64); action:str; config:dict[str,Any]
class AckResult(BaseModel): model_config=ConfigDict(extra='forbid'); ticket:str; status:str; message:str=Field(default='',max_length=1000)
class Ack(BaseModel): model_config=ConfigDict(extra='forbid'); outcome:str; state:str; message:str=Field(default='',max_length=1000); results:list[AckResult]|None=None
class LiveAuthorization(BaseModel): model_config=ConfigDict(extra='forbid'); confirm_live_trading:bool
class CloseAllConfirmation(BaseModel): model_config=ConfigDict(extra='forbid'); confirm_close_all:StrictBool
class Position(BaseModel):
    model_config=ConfigDict(extra='forbid')
    ticket:StrictInt|StrictStr; position_id:StrictInt|StrictStr; symbol:str=Field(max_length=64); direction:str=Field(max_length=16); volume:float=Field(gt=0,allow_inf_nan=False); open_price:float=Field(gt=0,allow_inf_nan=False); stop_loss:float=Field(ge=0,allow_inf_nan=False); take_profit:float=Field(ge=0,allow_inf_nan=False); floating_profit:float=Field(allow_inf_nan=False); commission:float|None=Field(default=None,allow_inf_nan=False); swap:float=Field(allow_inf_nan=False); fees:float|None=Field(default=None,allow_inf_nan=False)
    managed_by_ea:StrictBool|None=None
class ClosedDeal(BaseModel):
    model_config=ConfigDict(extra='forbid')
    ticket:StrictInt|StrictStr; position_id:StrictInt|StrictStr; symbol:str=Field(max_length=64); closed_at:str=Field(max_length=64); profit:float=Field(allow_inf_nan=False); commission:float=Field(allow_inf_nan=False); swap:float=Field(allow_inf_nan=False); fees:float|None=Field(default=None,allow_inf_nan=False)
class ActiveOrder(BaseModel):
    model_config=ConfigDict(extra='forbid')
    ticket:StrictInt|StrictStr; position_id:StrictInt|StrictStr=0; symbol:str=Field(max_length=64); direction:str=Field(max_length=16); state:str=Field(max_length=32)
    volume:float=Field(gt=0,allow_inf_nan=False); remaining_volume:float=Field(gt=0,allow_inf_nan=False); open_price:float=Field(gt=0,allow_inf_nan=False)
    stop_loss:float=Field(ge=0,allow_inf_nan=False); take_profit:float=Field(ge=0,allow_inf_nan=False)
    managed_by_ea:StrictBool|None=None
class Account(BaseModel):
    model_config=ConfigDict(extra='forbid')
    login:str=Field(min_length=1,max_length=128); server:str=Field(min_length=1,max_length=128); company:str=Field(min_length=1,max_length=128); currency:str=Field(min_length=1,max_length=16); account_mode:str
    balance:float|None=Field(default=None,gt=0,allow_inf_nan=False); equity:float|None=Field(default=None,gt=0,allow_inf_nan=False); margin:float|None=Field(default=None,ge=0,allow_inf_nan=False); free_margin:float|None=Field(default=None,ge=0,allow_inf_nan=False); margin_level:float|None=Field(default=None,ge=0,allow_inf_nan=False); leverage:int|None=Field(default=None,gt=0)
class Heartbeat(BaseModel):
    model_config=ConfigDict(extra='forbid')
    state:str; timestamp:str; terminal_connected:bool; trade_allowed:bool; account:Account; configuration:dict[str,Any]=Field(default_factory=dict); last_signal:Any=None; last_error:str=Field(default='',max_length=1000); open_positions:list[Position]; active_orders:list[ActiveOrder]=Field(default_factory=list); closed_deals:list[ClosedDeal]; news_verdict:dict[str,Any]=Field(default_factory=dict); cancelled_order_tickets:list[str]=Field(default_factory=list)

def relevant_news_currencies(symbol):
    s=''.join(ch for ch in (symbol or '').upper() if ch.isalnum())
    explicit={'XAUUSD':{'XAU','USD'},'XAGUSD':{'XAG','USD'},'XPTUSD':{'XPT','USD'},'BTCUSD':{'BTC','USD'},'ETHUSD':{'ETH','USD'},'US30':{'USD'},'NAS100':{'USD'},'US100':{'USD'},'SPX500':{'USD'},'GER40':{'EUR'},'UK100':{'GBP'},'JPN225':{'JPY'}}
    if s in explicit: return explicit[s]
    currencies={'USD','EUR','GBP','JPY','CHF','CAD','AUD','NZD','CNH','NOK','SEK','SGD','HKD','ZAR','MXN','TRY','PLN'}
    # Broker prefixes/suffixes are tolerated only when the first six characters are a known FX pair.
    if len(s)>=6 and s[:3] in currencies and s[3:6] in currencies: return {s[:3],s[3:6]}
    return None

def validate_news_verdict(v, symbol=None, at=None):
    if not isinstance(v,dict): return False,'NEWS UNKNOWN: verdict missing'
    status=str(v.get('status','')).upper(); retrieved=v.get('retrieved_at_ms'); coverage_from=v.get('coverage_from_ms'); coverage_to=v.get('coverage_to_ms')
    source=v.get('source'); affected=v.get('affected_currencies')
    if status not in ('CLEAR','HIGH_IMPACT_WINDOW','UNKNOWN') or not isinstance(source,str) or not source.strip() or not isinstance(affected,list) or not all(isinstance(x,str) and x.strip() for x in affected) or not all(isinstance(x,(int,float)) and math.isfinite(x) for x in (retrieved,coverage_from,coverage_to)):
        return False,'NEWS UNKNOWN: malformed or unauthenticated verdict'
    t=(at or time.time()*1000); max_age=max(1,NEWS_MAX_AGE)*1000
    if retrieved>t+60000 or t-retrieved>max_age or coverage_from>t or coverage_to<t+NEWS_BEFORE*1000: return False,'NEWS UNKNOWN: stale or incomplete calendar coverage'
    source_generated=v.get('source_generated_at_ms')
    if source_generated is not None:
        # Public event tables may be cached for 15 minutes while their server clock stays current.
        if not isinstance(source_generated,(int,float)) or not math.isfinite(source_generated) or source_generated>t+60000 or t-source_generated>max(NEWS_MAX_AGE,960)*1000:
            return False,'NEWS UNKNOWN: source calendar snapshot is stale'
    relevant=relevant_news_currencies(symbol) if symbol else None
    if symbol and relevant is None: return False,'NEWS UNKNOWN: instrument relevance mapping unavailable'
    currencies={str(x).upper() for x in affected}
    if relevant and not relevant.intersection(currencies): return True,'NEWS CLEAR: no relevant affected currency'
    if status=='CLEAR': return True,'NEWS CLEAR'
    if status=='HIGH_IMPACT_WINDOW':
        event_ms=v.get('event_time_ms'); currency=v.get('currency')
        if not isinstance(event_ms,(int,float)) or not math.isfinite(event_ms) or not isinstance(currency,str) or not currency.strip(): return False,'NEWS UNKNOWN: high-impact event evidence is incomplete'
        if t < event_ms-NEWS_BEFORE*1000 or t > event_ms+NEWS_AFTER*1000: return True,'NEWS CLEAR: high-impact event is outside blackout window'
        return False,'HIGH-IMPACT NEWS — TRADING BLOCKED'
    return False,'NEWS UNKNOWN — TRADING BLOCKED'

def _parse_utc_ms(value):
    if not isinstance(value,str) or not value.strip(): return None
    try:
        dt=datetime.fromisoformat(value.strip().replace('Z','+00:00'))
        if dt.tzinfo is None: return None
        return int(dt.astimezone(timezone.utc).timestamp()*1000)
    except (TypeError,ValueError,OverflowError): return None

def _unknown_news(source='unavailable',detail='NEWS UNKNOWN: provider unavailable or malformed'):
    return {'status':'UNKNOWN','detail':detail,'source':source,'affected_currencies':[],
            'max_age_seconds':NEWS_MAX_AGE,'blackout_before_seconds':NEWS_BEFORE,'blackout_after_seconds':NEWS_AFTER}

def _showmytrades_news(symbol):
    relevant=relevant_news_currencies(symbol)
    supported={'USD','EUR','GBP','JPY','AUD','NZD','CAD','CHF','CNY'}
    if not relevant: return _unknown_news('ShowMyTrades Economic Calendar','NEWS UNKNOWN: instrument relevance mapping unavailable')
    currencies=sorted({'CNY' if c=='CNH' else c for c in relevant})
    if not set(currencies).issubset(supported):
        return _unknown_news('ShowMyTrades Economic Calendar','NEWS UNKNOWN: calendar does not cover every instrument currency')
    if NEWS_BEFORE<0 or NEWS_AFTER<0 or NEWS_AFTER>21600 or NEWS_BEFORE>29*86400:
        return _unknown_news('ShowMyTrades Economic Calendar','NEWS UNKNOWN: configured blackout exceeds provider coverage')
    days=max(2,min(30,math.ceil(max(NEWS_BEFORE,1)/86400)+1))
    query=urlencode({'currency':','.join(currencies),'impact':'high','days':days,'format':'json'})
    request=urllib.request.Request(NEWS_PROVIDER_URL.rstrip('/')+'?'+query,headers={
        'Accept':'application/json','User-Agent':'DestinedFXAnalyzer/1.0'
    })
    source='ShowMyTrades Economic Calendar'
    try:
        with urllib.request.urlopen(request,timeout=8) as response:
            if response.headers.get_content_type()!='application/json':
                return _unknown_news(source,'NEWS UNKNOWN: calendar returned a non-JSON content type')
            raw=response.read(256*1024+1)
        if len(raw)>256*1024: return _unknown_news(source,'NEWS UNKNOWN: calendar response exceeded size limit')
        payload=json.loads(raw.decode('utf-8'))
        if not isinstance(payload,dict) or not isinstance(payload.get('events'),list) or len(payload['events'])>5000:
            return _unknown_news(source,'NEWS UNKNOWN: calendar response schema is invalid')
        server_time=payload.get('server_time')
        if isinstance(server_time,bool) or not isinstance(server_time,(int,float)) or not math.isfinite(server_time) or server_time<=0:
            return _unknown_news(source,'NEWS UNKNOWN: calendar server clock is missing')
        server_ms=int(server_time*1000); now_ms=int(time.time()*1000)
        if server_ms>now_ms+60000 or now_ms-server_ms>max(1,NEWS_MAX_AGE)*1000:
            return _unknown_news(source,'NEWS UNKNOWN: calendar server clock is stale')
        generated_ms=_parse_utc_ms(payload.get('generated_at'))
        coverage_from=_parse_utc_ms(payload.get('from')); coverage_to=_parse_utc_ms(payload.get('to'))
        if generated_ms is None or coverage_from is None or coverage_to is None:
            return _unknown_news(source,'NEWS UNKNOWN: calendar timestamps are incomplete')
        if generated_ms>server_ms+60000 or server_ms-generated_ms>max(NEWS_MAX_AGE,960)*1000:
            return _unknown_news(source,'NEWS UNKNOWN: calendar schedule snapshot is stale')
        if coverage_from>server_ms or coverage_to<server_ms+NEWS_BEFORE*1000:
            return _unknown_news(source,'NEWS UNKNOWN: calendar coverage is incomplete')
        verdict={'status':'CLEAR','source':source,'affected_currencies':currencies,
                 'retrieved_at_ms':server_ms,'source_generated_at_ms':generated_ms,
                 'coverage_from_ms':coverage_from,'coverage_to_ms':coverage_to,
                 'max_age_seconds':NEWS_MAX_AGE,'blackout_before_seconds':NEWS_BEFORE,
                 'blackout_after_seconds':NEWS_AFTER}
        active=[]
        for event in payload['events']:
            if not isinstance(event,dict): return _unknown_news(source,'NEWS UNKNOWN: malformed calendar event')
            currency=str(event.get('currency','')).strip().upper()
            impact=str(event.get('impact','')).strip().lower()
            if impact!='high' or currency not in currencies: continue
            # The documented calendar schema omits event.status; absent means still listed as scheduled.
            event_status=str(event.get('status','scheduled')).strip().lower()
            if event_status not in {'scheduled','moved','tentative'}:
                return _unknown_news(source,'NEWS UNKNOWN: unrecognized high-impact event status')
            ts=event.get('ts')
            if isinstance(ts,bool) or not isinstance(ts,(int,float)) or not math.isfinite(ts) or ts<=0:
                return _unknown_news(source,'NEWS UNKNOWN: high-impact event time is missing')
            event_ms=int(ts*1000)
            if event_ms<coverage_from-6*60*60*1000 or event_ms>coverage_to+60000:
                return _unknown_news(source,'NEWS UNKNOWN: event timestamp falls outside declared coverage')
            if event_ms-NEWS_BEFORE*1000<=server_ms<=event_ms+NEWS_AFTER*1000:
                active.append((abs(event_ms-server_ms),event_ms,currency,event_status))
        if active:
            _,event_ms,currency,event_status=min(active)
            verdict.update({'status':'HIGH_IMPACT_WINDOW','event_time_ms':event_ms,
                            'currency':currency,'event_status':event_status})
            _,detail=validate_news_verdict(verdict,symbol,at=server_ms)
            if detail.startswith('NEWS UNKNOWN'): return _unknown_news(source,detail)
            verdict['detail']=detail
        else:
            ok,detail=validate_news_verdict(verdict,symbol,at=server_ms)
            if not ok: return _unknown_news(source,detail)
            verdict['detail']=detail
        return verdict
    except Exception as exc:
        # Diagnostic is limited to the exception type and missing symbol name; no URL/token/body is logged.
        missing_name = getattr(exc, 'name', None) if isinstance(exc, NameError) else None
        diagnostic = type(exc).__name__ + (':' + missing_name if missing_name else '')
        return _unknown_news(source,'NEWS UNKNOWN: calendar provider unavailable or malformed ('+diagnostic+')')

def provider_news(symbol):
    symbol_key=(symbol or '').strip().upper()
    key=(NEWS_PROVIDER_URL, symbol_key, id(urllib.request.urlopen))
    cached=_NEWS_CACHE.get(key)
    if cached and time.time()-cached[0] <= NEWS_CACHE_TTL:
        return dict(cached[1])
    parsed=urlparse(NEWS_PROVIDER_URL)
    if not NEWS_PROVIDER_URL or parsed.scheme.lower()!='https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or NEWS_BEFORE<0 or NEWS_AFTER<0:
        verdict=_unknown_news('unconfigured','NEWS UNKNOWN: provider is not configured as a credential-free HTTPS endpoint')
        _NEWS_CACHE[key]=(time.time(),verdict); return verdict
    if parsed.hostname.lower().rstrip('.') in {'showmytrades.com','www.showmytrades.com'} and parsed.path.rstrip('/')=='/api/public/calendar/v1':
        verdict=_showmytrades_news(symbol_key); _NEWS_CACHE[key]=(time.time(),dict(verdict)); return verdict
    try:
        q=urllib.request.Request(NEWS_PROVIDER_URL+'?symbol='+quote(symbol_key),headers={'Accept':'application/json','Authorization':'Bearer '+NEWS_PROVIDER_TOKEN} if NEWS_PROVIDER_TOKEN else {'Accept':'application/json'})
        with urllib.request.urlopen(q,timeout=5) as response:
            if response.headers.get_content_type()!='application/json': return _unknown_news('provider','NEWS UNKNOWN: provider returned a non-JSON content type')
            raw=response.read(256*1024+1)
        if len(raw)>256*1024: return _unknown_news('provider','NEWS UNKNOWN: provider response exceeded size limit')
        value=json.loads(raw.decode('utf-8'))
        if not isinstance(value,dict): return _unknown_news('provider','NEWS UNKNOWN: provider schema is invalid')
        ok,detail=validate_news_verdict(value,symbol_key)
        if not ok and value.get('status')=='CLEAR': value['status']='UNKNOWN'
        value['detail']=detail; value['max_age_seconds']=NEWS_MAX_AGE; value['blackout_before_seconds']=NEWS_BEFORE; value['blackout_after_seconds']=NEWS_AFTER
        _NEWS_CACHE[key]=(time.time(),dict(value)); return value
    except Exception: return _unknown_news('unavailable','NEWS UNKNOWN: provider unavailable or malformed')

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
    try:
        parsed=urlparse(value)
        host=(parsed.hostname or '').lower().rstrip('.')
        port=parsed.port
    except (ValueError, UnicodeError):
        return None
    loopback=host in ('localhost','127.0.0.1','::1')
    placeholder=(host in {'example.com','example.org','example.net','testserver','bridge.example','localhost'} or host.endswith(('.example','.localhost','.test','.invalid')) or host.startswith('your-') or host.startswith('placeholder') or 'example' in host)
    if not host or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('','/'):
        return None
    if parsed.scheme=='http':
        if not (allow_loopback and loopback): return None
    elif parsed.scheme!='https' or loopback or placeholder:
        return None
    if port is not None and not (1 <= port <= 65535):
        return None
    host_for_url=host.strip('[]')
    try:
        address=ipaddress.ip_address(host_for_url)
        loopback_dev=allow_loopback and parsed.scheme=='http' and address.is_loopback
        if not loopback_dev and not address.is_global:
            return None
    except ValueError:
        if ':' in host_for_url or all(ch in '0123456789.' for ch in host_for_url):
            return None
    serialized_host=f'[{host_for_url}]' if ':' in host_for_url else host_for_url
    return f'{parsed.scheme}://{serialized_host}' + (f':{port}' if port else '')

def managed_config():
    key=os.getenv('CREDENTIALS_FERNET_KEY','')
    worker=os.getenv('WORKER_TOKEN','')
    bridge_url=os.getenv('BRIDGE_BASE_URL','').strip() or os.getenv('RENDER_EXTERNAL_URL','').strip()
    if not key or len(worker)<32 or len(worker)>512 or not validate_origin(bridge_url, allow_loopback=ALLOW_LOOPBACK):
        raise HTTPException(503, detail={'code':'CONFIGURATION_REQUIRED','message':'Managed execution is not configured'})
    try: return Fernet(key.encode()), worker
    except Exception: raise HTTPException(503, detail={'code':'CONFIGURATION_REQUIRED','message':'Managed execution is not configured'})
def require_durable_execution():
    if os.getenv('EXECUTION_STORAGE_DURABLE','true').lower() != 'true':
        raise HTTPException(503, detail={'code':'NON_DURABLE_STORAGE','message':'Execution-sensitive operations are disabled on non-durable storage'})
def worker_auth(header):
    _, secret=managed_config()
    if not header or not header.startswith('Bearer ') or not hmac.compare_digest(header[7:],secret): raise HTTPException(401,'Unauthorized')
def fresh_heartbeat(p):
    if not p or not p['heartbeat']: return None
    try:
        b=json.loads(p['heartbeat']); dt=datetime.fromisoformat(b['timestamp'].replace('Z','+00:00'))
        return b if 0 <= (now()-dt.astimezone(timezone.utc)).total_seconds() <= STALE else None
    except Exception: return None

def terminal_identity_matches(p, account_mode, beat):
    try:
        claimed=json.loads(p['account']) if p and p['account'] else {}
        actual=beat.get('account') if beat else None
        return bool(account_mode in ('DEMO','LIVE') and isinstance(actual,dict) and
                    all(actual.get(k)==claimed.get(k) for k in ('login','server','company','currency','account_mode')) and
                    actual.get('account_mode')==account_mode)
    except Exception: return False

def execution_state_eligible(beat):
    return bool(beat and beat.get('state') in ('READY','RUNNING','PAUSED','STOPPED'))

UINT64_MAX=18446744073709551615
DESTRUCTIVE_ACTIONS={'CANCEL_PENDING_SELECTED','CANCEL_EA_PENDING','CLOSE_POSITION_SELECTED','CLOSE_ALL'}

def canonical_ticket(value):
    if not isinstance(value,str) or not value or len(value)>20 or not value.isascii() or not value.isdecimal() or value.startswith('0'):
        raise HTTPException(422,'ticket must be a canonical positive uint64 decimal string')
    number=int(value)
    if number<1 or number>UINT64_MAX: raise HTTPException(422,'ticket must be a canonical positive uint64 decimal string')
    return value

def snapshot_ticket(value):
    if isinstance(value,bool): return None
    if isinstance(value,int): number=value
    elif isinstance(value,str) and value.isascii() and value.isdecimal() and not value.startswith('0'):
        try: number=int(value)
        except ValueError: return None
    else: return None
    return str(number) if 1<=number<=UINT64_MAX else None

def destructive_context(p):
    with tx() as c:
        managed=c.execute('SELECT access_type,revoked,account_mode FROM managed_accounts WHERE pairing_id=?',(p['id'],)).fetchone()
        job=c.execute("SELECT status FROM worker_jobs WHERE pairing_id=? ORDER BY created DESC LIMIT 1",(p['id'],)).fetchone()
    beat=fresh_heartbeat(p)
    if (not managed or managed['revoked'] or managed['access_type']!='MASTER' or managed['account_mode'] not in ('DEMO','LIVE')
        or not p['consumed'] or not beat or not beat.get('terminal_connected') or not beat.get('trade_allowed') or not terminal_identity_matches(p,managed['account_mode'],beat)
        or not job or job['status']!='READY'):
        raise HTTPException(409,'Destructive action requires an active MASTER profile and a fresh authenticated matching terminal and ready worker')
    return managed,beat

def reconcile_command_items(c,pairing_id,beat,heartbeat_seq):
    orders={snapshot_ticket(x.get('ticket')):x.get('remaining_volume') for x in beat.get('active_orders',[]) if isinstance(x,dict)}
    positions={snapshot_ticket(x.get('position_id')):x.get('volume') for x in beat.get('open_positions',[]) if isinstance(x,dict)}
    cancelled={normalized for ticket in beat.get('cancelled_order_tickets',[]) if (normalized:=snapshot_ticket(ticket))}
    closed_positions={snapshot_ticket(x.get('position_id')) for x in beat.get('closed_deals',[]) if isinstance(x,dict)}
    rows=c.execute('''SELECT i.*,cmd.pairing_id,cmd.action FROM command_items i JOIN commands cmd ON cmd.id=i.command_id
                      WHERE cmd.pairing_id=? AND i.operational_status IN ('PENDING','PARTIAL') AND i.ack_status IN ('APPLIED','UNKNOWN')
                      AND i.ack_heartbeat_seq IS NOT NULL AND i.ack_heartbeat_seq<?''',(pairing_id,heartbeat_seq)).fetchall()
    for item in rows:
        remaining=orders.get(item['ticket']) if item['target_kind']=='ORDER' else positions.get(item['target_id'])
        still_present=remaining is not None
        explicit_cancel=item['target_kind']=='ORDER' and item['ticket'] in cancelled
        explicit_close=item['target_kind']=='POSITION' and item['target_id'] in closed_positions
        if item['target_kind']=='ORDER':
            proof=explicit_cancel or item['ack_status']=='APPLIED'
        else:
            proof=explicit_close if item['ack_status']=='UNKNOWN' else True
        if not still_present and proof:
            c.execute("UPDATE command_items SET operational_status='CONFIRMED',remaining_volume=0 WHERE command_id=? AND ticket=?",(item['command_id'],item['ticket']))
        elif still_present:
            partial=float(remaining)<float(item['target_volume'])-1e-9
            c.execute('UPDATE command_items SET operational_status=?,remaining_volume=? WHERE command_id=? AND ticket=?',
                      ('PARTIAL' if partial else 'PENDING',float(remaining),item['command_id'],item['ticket']))

def heartbeat_news_clear(beat, symbols=None):
    verdict=beat.get('news_verdict') if isinstance(beat,dict) else None
    if not isinstance(verdict,dict) or str(verdict.get('status','')).upper()!='CLEAR': return False
    ok,_=validate_news_verdict(verdict, (symbols or [None])[0] if symbols else None)
    return ok

@app.post('/v1/app/managed-account',status_code=202)
def managed_account(x:ManagedAccount, authorization:str|None=Header(default=None)):
    require_durable_execution()
    p=auth(authorization,'app'); fernet,_=managed_config()
    if not x.consent or x.access_type not in ('MASTER','INVESTOR') or x.account_mode not in ('DEMO','LIVE') or not x.login.isdigit() or not 1 <= len(x.login) <= 20 or any(ord(ch)<32 or ord(ch)==127 for ch in x.server+x.password): raise HTTPException(422,'Consent, account number, profile mode, and valid access_type are required')
    evidence=x.cost_evidence.strip()
    if any(ord(ch)<32 or ord(ch)==127 for ch in evidence): raise HTTPException(422,'Cost evidence reference must not contain control characters')
    if x.cost_model not in ('OBSERVED_ROUND_TURN','CONFIGURED_ROUND_TURN','CONFIGURED_PER_SIDE','ZERO_COMMISSION_SPREAD_ONLY'): raise HTTPException(422,'Invalid transaction cost model')
    if x.cost_model=='OBSERVED_ROUND_TURN':
        if x.commission_per_lot!=0 or evidence: raise HTTPException(422,'Observed round-turn mode must not include a configured commission or evidence reference')
    elif x.cost_model=='ZERO_COMMISSION_SPREAD_ONLY':
        if x.commission_per_lot!=0 or len(evidence)<8: raise HTTPException(422,'Zero-commission mode requires a zero commission value and an official broker pricing evidence reference')
    elif x.commission_per_lot<=0 or len(evidence)<8:
        raise HTTPException(422,'Configured commission models require a finite positive account-currency amount per standard lot and a broker evidence reference')
    with tx(True) as c:
        if c.execute('SELECT 1 FROM managed_accounts WHERE pairing_id=?',(p['id'],)).fetchone(): raise HTTPException(409,'Managed account already exists for this pairing')
        pairing_code=secrets.token_urlsafe(18); job_id=str(uuid.uuid4()); t=time.time()
        c.execute('INSERT INTO managed_accounts(pairing_id,login_enc,server_enc,password_enc,access_type,revoked,account_mode,cost_model,commission_per_lot,cost_evidence) VALUES(?,?,?,?,?,0,?,?,?,?)',(p['id'],fernet.encrypt(x.login.encode()).decode(),fernet.encrypt(x.server.encode()).decode(),fernet.encrypt(x.password.encode()).decode(),x.access_type,x.account_mode,x.cost_model,float(x.commission_per_lot),evidence))
        c.execute('UPDATE pairings SET code_hash=?,expires=?,consumed=0,terminal_hash=NULL,terminal_id=NULL,instance_id=NULL,account=NULL,heartbeat=NULL,live_authorized_until=NULL,live_authorized_login=NULL,live_authorized_server=NULL WHERE id=?',(digest(pairing_code),t+86400,p['id']))
        c.execute("INSERT INTO worker_jobs(id,pairing_id,kind,status,created) VALUES(?,?,'PROVISION','QUEUED',?)",(job_id,p['id'],t))
    return {'job_id':job_id,'status':'QUEUED'}

@app.post('/v1/worker/jobs/claim')
def worker_claim(x:WorkerClaim, authorization:str|None=Header(default=None)):
    require_durable_execution()
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
        if m['account_mode'] not in ('DEMO','LIVE'):
            c.execute("UPDATE worker_jobs SET status='FAILED',error_code='ACCOUNT_MODE_UNVERIFIED',error_message='Account mode must be re-verified before provisioning' WHERE id=?",(r['id'],))
            return {'job':None}
        account={k:fernet.decrypt(m[k+'_enc'].encode()).decode() for k in ('login','server','password')}; account['access_type']=m['access_type']; account['account_mode']=m['account_mode']
        account['cost_model']=m['cost_model']; account['commission_per_lot']=float(m['commission_per_lot']); account['cost_evidence']=m['cost_evidence']
        try:
            claimed_account=json.loads(p['account']) if p['account'] else {}
            for key in ('company','currency'):
                if isinstance(claimed_account.get(key),str) and claimed_account[key]: account[key]=claimed_account[key]
        except (TypeError, ValueError):
            pass
        code_row=c.execute('SELECT code_hash FROM pairings WHERE id=?',(r['pairing_id'],)).fetchone()
        # Existing plaintext code is intentionally not persisted; generate a fresh one for the worker and rotate hash.
        code=secrets.token_urlsafe(18); c.execute('UPDATE pairings SET code_hash=?,expires=? WHERE id=?',(digest(code),t+86400,r['pairing_id']))
        bridge_url=os.getenv('BRIDGE_BASE_URL','').strip() or os.getenv('RENDER_EXTERNAL_URL','').strip()
        return {'job_id':r['id'],'kind':'PROVISION','worker_id':x.worker_id,'pairing_id':r['pairing_id'],'pairing_code':code,'bridge_url':bridge_url,'account':account}

@app.patch('/v1/worker/jobs/{job_id}')
def worker_update(job_id:str,x:WorkerUpdate,authorization:str|None=Header(default=None)):
    require_durable_execution()
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
    require_durable_execution()
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
        managed=c.execute('SELECT access_type,revoked,account_mode FROM managed_accounts WHERE pairing_id=?',(job['pairing_id'],)).fetchone()
        if job['kind']=='PROVISION' and job['status']=='READY' and managed and not managed['revoked']:
            beat=fresh_heartbeat(pairing)
            valid=bool(execution_state_eligible(beat) and beat.get('terminal_connected') and managed['account_mode'] in ('DEMO','LIVE') and terminal_identity_matches(pairing,managed['account_mode'],beat) and (managed['access_type']=='INVESTOR' or beat.get('trade_allowed')))
            if valid:
                c.execute('UPDATE worker_jobs SET lease_until=?,stale_since=NULL,recovery_deadline=NULL,error_code=NULL,error_message=NULL WHERE id=?',(t+120,job_id))
            else:
                c.execute("UPDATE worker_jobs SET status='AUTHENTICATING',lease_until=?,stale_since=COALESCE(stale_since,?),recovery_deadline=COALESCE(recovery_deadline,?),error_code='TERMINAL_HEARTBEAT_STALE',error_message='Fresh authenticated terminal heartbeat is required' WHERE id=?",(t+120,t,t+RECOVERY_GRACE,job_id))
            job=c.execute('SELECT * FROM worker_jobs WHERE id=?',(job_id,)).fetchone()
        if job['kind']=='PROVISION' and job['status'] in ('PROVISIONING','AUTHENTICATING') and managed and not managed['revoked']:
            beat=fresh_heartbeat(pairing)
            valid=bool(execution_state_eligible(beat) and beat.get('terminal_connected') and managed['account_mode'] in ('DEMO','LIVE') and terminal_identity_matches(pairing,managed['account_mode'],beat) and (managed['access_type']=='INVESTOR' or beat.get('trade_allowed')))
            if valid:
                c.execute("UPDATE worker_jobs SET status='READY',stale_since=NULL,recovery_deadline=NULL,error_code=NULL,error_message=NULL WHERE id=?",(job_id,))
                job=c.execute('SELECT * FROM worker_jobs WHERE id=?',(job_id,)).fetchone()
            elif job['stale_since'] is not None and t > (job['recovery_deadline'] or t):
                c.execute("UPDATE worker_jobs SET status='FAILED',error_code='TERMINAL_HEARTBEAT_RECOVERY_EXHAUSTED',error_message='Terminal heartbeat recovery grace period expired' WHERE id=?",(job_id,))
                job=c.execute('SELECT * FROM worker_jobs WHERE id=?',(job_id,)).fetchone()
            elif job['stale_since'] is None and t-(job['lease_started'] if job['lease_started'] else job['created'])>300:
                c.execute("UPDATE worker_jobs SET status='FAILED',error_code='TERMINAL_AUTH_TIMEOUT',error_message='Terminal did not authenticate in time' WHERE id=?",(job_id,))
                job=c.execute('SELECT * FROM worker_jobs WHERE id=?',(job_id,)).fetchone()
    return {'job_id':job_id,'kind':job['kind'],'status':job['status'],'error_code':job['error_code'],'error_message':job['error_message']}

@app.get('/health')
def health(): return {'status':'ok'}
@app.get('/health/ready')
def readiness():
    """Report whether the bridge can safely accept managed-execution work."""
    if os.getenv('EXECUTION_STORAGE_DURABLE', 'true').lower() != 'true':
        raise HTTPException(503, detail={'code':'NON_DURABLE_STORAGE','message':'Execution readiness is disabled because this deployment uses ephemeral storage'})
    try:
        managed_config()
        with tx() as c:
            c.execute('SELECT 1').fetchone()
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(503, detail={'code':'DATABASE_UNAVAILABLE','message':'Bridge storage is unavailable'})
    return {'status':'ready','database':'ok','managed_execution':'configured'}
@app.post('/v1/pairings',status_code=201)
def create_pair(request:Request):
    require_durable_execution()
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
    require_durable_execution()
    for k in ('login','server','company','currency','account_mode'):
        if k not in x.account or not x.account[k] or len(x.account[k])>128: raise HTTPException(422,'Invalid account identity')
    if x.account['account_mode'] not in ('DEMO','LIVE'): raise HTTPException(422,'Unverified account mode')
    terminal_token=token(); terminal_id=str(uuid.uuid4()); t=time.time()
    with tx(True) as c:
        rows=c.execute('SELECT * FROM pairings WHERE consumed=0 AND expires>?',(t,)).fetchall()
        found=next((r for r in rows if hmac.compare_digest(r['code_hash'],digest(x.pairing_code))),None)
        if found is None: raise HTTPException(401,'Invalid or expired pairing code')
        managed=c.execute('SELECT login_enc,server_enc,account_mode FROM managed_accounts WHERE pairing_id=? AND revoked=0',(found['id'],)).fetchone()
        if managed:
            fernet,_=managed_config()
            expected_login=fernet.decrypt(managed['login_enc'].encode()).decode()
            expected_server=fernet.decrypt(managed['server_enc'].encode()).decode()
            if managed['account_mode'] not in ('DEMO','LIVE') or x.account['login']!=expected_login or x.account['server']!=expected_server or x.account['account_mode']!=managed['account_mode']:
                raise HTTPException(401,'Authenticated terminal account does not match managed account')
        c.execute('UPDATE pairings SET consumed=1,terminal_hash=?,terminal_id=?,instance_id=?,account=? WHERE id=? AND consumed=0',(digest(terminal_token),terminal_id,x.terminal_instance_id,json.dumps(x.account,separators=(',',':')),found['id']))
        if c.total_changes<1: raise HTTPException(409,'Pairing code already consumed')
    return {'terminal_id':terminal_id,'terminal_token':terminal_token,'poll_seconds':2}
def _stable_signal(signal):
    if not isinstance(signal,dict): return None
    fields=('state','strategy','execution_state','symbol','timeframe','direction','entry','actual_entry','submitted_at','opened_at','stop_loss','take_profit','risk_percent','requested_volume','executed_volume','order_retcode','order','deal','position','cost_model','news_status','news_detail','news_source','news_event','news_currency')
    return {key:signal[key] for key in fields if key in signal}

def _trade_event_signature(identity,status,payload):
    kind=payload.get('kind') if isinstance(payload,dict) else None
    signal=payload.get('signal') if isinstance(payload,dict) else None
    if not signal and kind=='last_signal': signal=payload
    stable={'kind':kind,'signal':_stable_signal(signal),'status':status}
    if kind=='closed_position':
        fields=('ticket','position_id','symbol','direction','entry_price','opened_at','closed_at','volume','price','profit','commission','swap','fees')
        stable['lifecycle']=payload.get('lifecycle')
        stable['closed_volume']=payload.get('closed_volume')
        stable['remaining_volume']=payload.get('remaining_volume')
        stable['deals']=[{key:d[key] for key in fields if key in d} for d in payload.get('deals',[]) if isinstance(d,dict)]
        wanted=identity.split(':',1)[1] if identity.startswith('position:') else ''
        pos_fields=('ticket','position_id','symbol','direction','volume','open_price','stop_loss','take_profit','swap','floating_profit')
        stable['position']=[{key:x[key] for key in pos_fields if key in x} for x in payload.get('positions',[]) if isinstance(x,dict) and str(x.get('position_id',''))==wanted]
    return digest(json.dumps({'identity':identity,'snapshot':stable},sort_keys=True,separators=(',',':'),default=str))

def _append_trade_ledger_event(c,pairing_id,identity):
    row=c.execute('SELECT * FROM trade_ledger WHERE pairing_id=? AND identity=?',(pairing_id,identity)).fetchone()
    if not row: return
    payload=json.loads(row['payload'])
    signature=_trade_event_signature(identity,row['status'],payload)
    previous=c.execute('SELECT signature FROM trade_ledger_events WHERE pairing_id=? AND identity=? ORDER BY id DESC LIMIT 1',(pairing_id,identity)).fetchone()
    if previous and previous['signature']==signature: return
    c.execute('''INSERT INTO trade_ledger_events(pairing_id,identity,account_mode,position_id,order_ticket,deal_ticket,symbol,direction,status,payload,signature,created)
                 VALUES(?,?,?,?,?,?,?,?,?,?,?,?)''',
              (pairing_id,identity,row['account_mode'],row['position_id'],row['order_ticket'],row['deal_ticket'],row['symbol'],row['direction'],row['status'],row['payload'],signature,row['updated']))

def ingest_trade_ledger(c, pairing_id, account_mode, payload, received):
    account=payload.get('account') or {}
    open_positions={str(x.get('position_id')):x for x in payload.get('open_positions',[]) if isinstance(x,dict) and x.get('position_id') is not None}
    signal=payload.get('last_signal') or {}
    signal_position=str(signal.get('position',''))
    try: executed=float(signal.get('executed_volume',0) or 0)
    except (TypeError,ValueError): executed=0.0
    for deal in payload.get('closed_deals',[]):
        position=str(deal.get('position_id',''))
        identity='position:'+position if position else 'deal:'+str(deal.get('ticket',''))
        if identity.endswith(':'): continue
        existing=c.execute('SELECT payload FROM trade_ledger WHERE pairing_id=? AND identity=?',(pairing_id,identity)).fetchone()
        preserved_signal=None
        if existing:
            old=json.loads(existing['payload'])
            deals=old.get('deals',[])
            preserved_signal=old.get('signal')
            if not preserved_signal and old.get('kind')=='last_signal': preserved_signal=old
            if not any(str(x.get('ticket'))==str(deal.get('ticket')) for x in deals): deals.append(deal)
        else:
            deals=[deal]
            if position and signal_position==position and executed>0:
                fill_doc={'kind':'confirmed_fill','account':account,'signal':signal,'positions':payload.get('open_positions',[])}
                c.execute('''INSERT OR IGNORE INTO trade_ledger(pairing_id,identity,account_mode,position_id,order_ticket,deal_ticket,symbol,direction,status,payload,first_seen,updated)
                             VALUES(?,?,?,?,?,?,?,?,?,?,?,?)''',
                          (pairing_id,identity,account_mode,position,str(signal.get('order','')) or None,str(signal.get('deal','')) or None,str(signal.get('symbol',deal.get('symbol',''))),str(signal.get('direction','')) or None,'OPEN',json.dumps(fill_doc,separators=(',',':')),received,received))
                _append_trade_ledger_event(c,pairing_id,identity)
                preserved_signal=signal
        doc={'kind':'closed_position','account':account,'deals':deals,'positions':payload.get('open_positions',[]),'signal':preserved_signal or (signal if position and signal_position==position and executed>0 else None)}
        try: closed_volume=sum(float(x.get('volume',0)) for x in deals)
        except (TypeError,ValueError): closed_volume=0.0
        remaining=open_positions.get(position,{}).get('volume',0.0) if position else 0.0
        try: remaining=float(remaining or 0.0)
        except (TypeError,ValueError): remaining=0.0
        doc['closed_volume']=closed_volume; doc['remaining_volume']=remaining
        lifecycle='OPEN' if remaining>1e-9 else 'CLOSED'; doc['lifecycle']=lifecycle
        c.execute('''INSERT INTO trade_ledger(pairing_id,identity,account_mode,position_id,order_ticket,deal_ticket,symbol,direction,status,payload,first_seen,updated)
                     VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(pairing_id,identity) DO UPDATE SET payload=excluded.payload,updated=excluded.updated,status=excluded.status,deal_ticket=excluded.deal_ticket''',
                  (pairing_id,identity,account_mode,position,str(preserved_signal.get('order','')) if isinstance(preserved_signal,dict) and preserved_signal.get('order') else None,str(deal.get('ticket','')),str(deal.get('symbol','')),str((preserved_signal or {}).get('direction') or deal.get('direction') or '') or None,lifecycle,json.dumps(doc,separators=(',',':')),received,received))
        _append_trade_ledger_event(c,pairing_id,identity)
    if signal and signal.get('position') and executed>0:
        position=str(signal.get('position')); identity='position:'+position
        exists=c.execute('SELECT status FROM trade_ledger WHERE pairing_id=? AND identity=?',(pairing_id,identity)).fetchone()
        if not exists or exists['status']!='CLOSED':
            document={'kind':'last_signal','account':account,'signal':signal,'positions':payload.get('open_positions',[])}
            c.execute('''INSERT INTO trade_ledger(pairing_id,identity,account_mode,position_id,order_ticket,deal_ticket,symbol,direction,status,payload,first_seen,updated)
                         VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(pairing_id,identity) DO UPDATE SET payload=excluded.payload,updated=excluded.updated,status=excluded.status,order_ticket=excluded.order_ticket,deal_ticket=excluded.deal_ticket''',
                      (pairing_id,identity,account_mode,position,str(signal.get('order','')) or None,str(signal.get('deal','')) or None,str(signal.get('symbol','')),str(signal.get('direction','')) or None,'OPEN',json.dumps(document,separators=(',',':')),received,received))
            _append_trade_ledger_event(c,pairing_id,identity)

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
        if cmd['action'] in DESTRUCTIVE_ACTIONS:
            with tx() as c:
                items=c.execute('SELECT ticket,ack_status,message,operational_status,remaining_volume,target_volume FROM command_items WHERE command_id=? ORDER BY rowid',(cmd['id'],)).fetchall()
            last_command['results']=[{'ticket':i['ticket'],'status':'REJECTED' if i['ack_status']=='REJECTED' else ('APPLIED' if i['operational_status']=='CONFIRMED' else ('PARTIAL' if i['operational_status']=='PARTIAL' else ('UNKNOWN' if i['ack_status']=='UNKNOWN' else 'PENDING'))),'ack_status':i['ack_status'] or 'PENDING','operational_status':i['operational_status'],'target_volume':i['target_volume'],'remaining_volume':i['remaining_volume'],'message':('Target absence confirmed by subsequent terminal evidence' if i['operational_status']=='CONFIRMED' else ('Partial result reconciled against fresh terminal volume' if i['operational_status']=='PARTIAL' else i['message']))} for i in items]
    with tx() as c:
        m=c.execute('SELECT access_type,revoked,account_mode FROM managed_accounts WHERE pairing_id=?',(p['id'],)).fetchone()
        job=c.execute('SELECT status,error_code,error_message FROM worker_jobs WHERE pairing_id=? ORDER BY created DESC LIMIT 1',(p['id'],)).fetchone()
    managed=None
    identity_verified=False
    if m:
        actual=fresh_heartbeat(p); worker_ready=bool(job and job['status']=='READY')
        claimed=json.loads(p['account']) if p['account'] else {}
        identity_verified=bool(actual and m['account_mode'] in ('DEMO','LIVE') and actual.get('account') and all(actual['account'].get(k)==claimed.get(k) for k in ('login','server','company','currency','account_mode')) and actual['account'].get('account_mode')==m['account_mode'])
        app_status='READY' if identity_verified and worker_ready and execution_state_eligible(actual) and heartbeat_news_clear(actual) else ('STALE' if worker_ready and p['heartbeat'] else ('AUTHENTICATING' if worker_ready else (job['status'] if job else 'QUEUED')))
        live_auth=bool(m['account_mode']=='LIVE' and identity_verified and p['live_authorized_until'] and p['live_authorized_until']>time.time() and p['live_authorized_login']==actual['account'].get('login') and p['live_authorized_server']==actual['account'].get('server'))
        managed={'status':app_status,'access_type':m['access_type'],'account_mode':m['account_mode'],'terminal_fresh':bool(actual),'account_identity_verified':identity_verified,'execution_state_eligible':execution_state_eligible(actual),'news_ready':heartbeat_news_clear(actual),'live_authorized':live_auth,'live_authorized_until':iso(datetime.fromtimestamp(p['live_authorized_until'],timezone.utc)) if p['live_authorized_until'] else None,'trade_allowed':bool(actual and actual.get('trade_allowed')),'error_code':job['error_code'] if job else None,'error_message':job['error_message'] if job else None}
        if actual:
            with tx(True) as c: ingest_trade_ledger(c,p['id'],m['account_mode'],actual,time.time())
    result={'pairing_status':'PAIRED' if p['consumed'] else 'PAIRING','terminal_online':online,'heartbeat_age_seconds':age,'heartbeat_stale_seconds':STALE,'ea_state':beat['state'] if online else 'OFFLINE','terminal_connected':beat['terminal_connected'] if online else False,'trade_allowed':beat['trade_allowed'] if online else False,'account':beat['account'] if online else None,'account_identity_verified':identity_verified,'configuration':beat['configuration'] if online else {},'news_verdict':beat.get('news_verdict',{'status':'UNKNOWN','detail':'NEWS UNKNOWN: authenticated verdict unavailable'}) if online else {'status':'UNKNOWN','detail':'NEWS UNKNOWN: terminal heartbeat unavailable'},'last_signal':beat['last_signal'] if online else None,'last_error':beat['last_error'] if online else ('Terminal heartbeat is stale or missing' if p['heartbeat'] else ''),'open_positions':beat['open_positions'] if online else None,'active_orders':beat.get('active_orders',[]) if online else None,'closed_deals':beat['closed_deals'] if online else None,'last_heartbeat_at':beat['timestamp'] if beat else None,'last_command':last_command}
    if managed is not None: result['managed_execution']=managed
    return result
@app.get('/v1/terminal/news-verdict')
def terminal_news_verdict(symbol:str=Query(min_length=1,max_length=64),authorization:str|None=Header(default=None)):
    auth(authorization,'terminal')
    verdict=provider_news(symbol.strip().upper())
    ok,detail=validate_news_verdict(verdict,symbol.strip().upper())
    if not ok and verdict.get('status')=='CLEAR': verdict['status']='UNKNOWN'
    verdict['detail']=detail; verdict['blackout_before_seconds']=NEWS_BEFORE; verdict['blackout_after_seconds']=NEWS_AFTER
    return verdict

@app.get('/v1/app/trades')
def app_trades(limit:int=Query(default=50,ge=1,le=200),cursor:int=Query(default=0,ge=0),updated_after:float=Query(default=0,ge=0),authorization:str|None=Header(default=None)):
    p=auth(authorization,'app'); watermark=time.time()
    with tx(True) as c:
        # Backfill current ledger rows after upgrading from the pre-event-feed schema.
        missing=c.execute('SELECT l.identity FROM trade_ledger l LEFT JOIN trade_ledger_events e ON e.pairing_id=l.pairing_id AND e.identity=l.identity WHERE l.pairing_id=? AND e.id IS NULL',(p['id'],)).fetchall()
        for item in missing: _append_trade_ledger_event(c,p['id'],item['identity'])
        rows=c.execute('SELECT * FROM trade_ledger_events WHERE pairing_id=? AND id>? ORDER BY id LIMIT ?', (p['id'],cursor,limit)).fetchall()
    next_cursor=max([cursor]+[r['id'] for r in rows])
    return {'cursor':next_cursor,'watermark':watermark,'trades':[{'id':r['id'],'identity':r['identity'],'account_mode':r['account_mode'],'position_id':r['position_id'],'order_ticket':r['order_ticket'],'deal_ticket':r['deal_ticket'],'symbol':r['symbol'],'direction':r['direction'],'status':r['status'],'payload':json.loads(r['payload']),'updated_at':iso(datetime.fromtimestamp(r['created'],timezone.utc))} for r in rows]}

@app.post('/v1/app/live-authorization')
def authorize_live(x:LiveAuthorization,authorization:str|None=Header(default=None)):
    require_durable_execution()
    p=auth(authorization,'app')
    if not x.confirm_live_trading: raise HTTPException(422,'Explicit LIVE trading confirmation is required')
    with tx() as c:
        m=c.execute('SELECT access_type,revoked,account_mode FROM managed_accounts WHERE pairing_id=?',(p['id'],)).fetchone()
        job=c.execute("SELECT status FROM worker_jobs WHERE pairing_id=? ORDER BY created DESC LIMIT 1",(p['id'],)).fetchone()
    beat=fresh_heartbeat(p)
    if not m or m['revoked'] or m['account_mode']!='LIVE' or m['access_type']!='MASTER': raise HTTPException(409,'LIVE authorization requires a non-revoked LIVE MASTER profile')
    if not beat or beat.get('state') not in ('READY','STOPPED','PAUSED') or not beat.get('terminal_connected') or not beat.get('trade_allowed') or not terminal_identity_matches(p,m['account_mode'],beat) or not job or job['status']!='READY':
        raise HTTPException(409,'LIVE authorization requires a fresh, ready terminal whose verified identity matches the LIVE profile')
    until=time.time()+900
    account=beat['account']
    with tx(True) as c:
        c.execute('UPDATE pairings SET live_authorized_until=?,live_authorized_login=?,live_authorized_server=? WHERE id=?',(until,account['login'],account['server'],p['id']))
    return {'authorized':True,'expires_at':iso(datetime.fromtimestamp(until,timezone.utc)),'duration_seconds':900}
@app.delete('/v1/app/live-authorization')
def revoke_live_authorization(authorization:str|None=Header(default=None)):
    p=auth(authorization,'app')
    with tx(True) as c: c.execute('UPDATE pairings SET live_authorized_until=NULL,live_authorized_login=NULL,live_authorized_server=NULL WHERE id=?',(p['id'],))
    return {'revoked':True}

@app.post('/v1/app/close-all-confirmation')
def close_all_confirmation(x:CloseAllConfirmation,authorization:str|None=Header(default=None)):
    require_durable_execution()
    p=auth(authorization,'app')
    if x.confirm_close_all is not True: raise HTTPException(422,'Explicit close-all confirmation is required')
    m,beat=destructive_context(p)
    account=beat['account']; raw=token(); expiry=time.time()+60
    with tx(True) as c:
        c.execute('INSERT INTO close_all_confirmations(token_hash,pairing_id,account_mode,login,server,expires_at,used) VALUES(?,?,?,?,?,?,0)',
                  (digest(raw),p['id'],m['account_mode'],account['login'],account['server'],expiry))
    return {'token':raw,'expires_at':iso(datetime.fromtimestamp(expiry,timezone.utc)),'duration_seconds':60}

def issue_destructive_command(x,p):
    try: raw_config=json.dumps(x.config,sort_keys=True,separators=(',',':'),allow_nan=False)
    except (TypeError,ValueError): raise HTTPException(422,'Invalid command config')
    with tx() as c:
        prior=c.execute('SELECT * FROM commands WHERE pairing_id=? AND request_id=?',(p['id'],x.request_id)).fetchone()
    if prior:
        if prior['action']!=x.action or prior['request_config']!=raw_config:
            raise HTTPException(409,'request_id was already used for a different command payload')
        return {'id':prior['id'],'request_id':prior['request_id'],'action':prior['action'],'config':json.loads(prior['config']),'status':'acknowledged' if prior['ack'] else 'queued'}
    managed,beat=destructive_context(p)
    cfg=x.config
    targets=[]
    confirmation=None
    if x.action=='CANCEL_PENDING_SELECTED':
        supplied=cfg.get('tickets')
        if not isinstance(supplied,list) or len(supplied)!=1: raise HTTPException(422,'Exactly one selected order ticket is required')
        ticket=canonical_ticket(supplied[0])
        matches=[o for o in beat.get('active_orders',[]) if isinstance(o,dict) and snapshot_ticket(o.get('ticket'))==ticket]
        if len(matches)!=1: raise HTTPException(409,'Selected order ticket is missing or ambiguous in the fresh authenticated snapshot')
        found=matches[0]
        targets=[(ticket,ticket,'ORDER',float(found['remaining_volume']))]
    elif x.action=='CLOSE_POSITION_SELECTED':
        supplied=cfg.get('tickets')
        if not isinstance(supplied,list) or len(supplied)!=1: raise HTTPException(422,'Exactly one selected position ID is required')
        ticket=canonical_ticket(supplied[0])
        matches=[v for v in beat.get('open_positions',[]) if isinstance(v,dict) and snapshot_ticket(v.get('position_id'))==ticket]
        if len(matches)!=1: raise HTTPException(409,'Selected position ID is missing or ambiguous in the fresh authenticated snapshot')
        found=matches[0]
        target_id=snapshot_ticket(found.get('position_id')) if found else None
        if not found or not target_id: raise HTTPException(409,'Selected position ID is not in the fresh authenticated snapshot')
        targets=[(ticket,target_id,'POSITION',float(found['volume']))]
    elif x.action=='CANCEL_EA_PENDING':
        snapshot=beat.get('active_orders')
        if not isinstance(snapshot,list) or not snapshot or any(not isinstance(o,dict) or not isinstance(o.get('managed_by_ea'),bool) for o in snapshot):
            raise HTTPException(409,'EA order ownership is unknown or unmarked in the fresh snapshot')
        for order in snapshot:
            if isinstance(order,dict) and order.get('managed_by_ea') is True:
                ticket=snapshot_ticket(order.get('ticket'))
                if not ticket: raise HTTPException(409,'EA-managed active order has an invalid ticket in the fresh snapshot')
                targets.append((ticket,ticket,'ORDER',float(order['remaining_volume'])))
        if not targets: raise HTTPException(409,'Fresh snapshot contains no EA-managed pending orders')
    elif x.action=='CLOSE_ALL':
        raw_token=cfg.get('confirmation')
        if not isinstance(raw_token,str) or not raw_token: raise HTTPException(422,'A close-all confirmation token is required')
        actual=beat['account']
        if (cfg.get('account_login')!=actual.get('login') or cfg.get('account_server')!=actual.get('server')
            or cfg.get('account_mode')!=managed['account_mode']):
            raise HTTPException(409,'Close-all account metadata does not match the fresh authenticated account')
        for pos in beat.get('open_positions',[]):
            ticket=snapshot_ticket(pos.get('position_id')) if isinstance(pos,dict) else None
            position_id=snapshot_ticket(pos.get('position_id')) if isinstance(pos,dict) else None
            if not ticket or not position_id: raise HTTPException(409,'Open position snapshot contains an invalid ticket')
            targets.append((ticket,position_id,'POSITION',float(pos['volume'])))
        if not targets: raise HTTPException(409,'Close-all requires at least one open position in the fresh snapshot')
        if len({item[0] for item in targets})!=len(targets): raise HTTPException(409,'Open position snapshot contains duplicate tickets')
        supplied=cfg.get('tickets')
        if not isinstance(supplied,list) or any(not isinstance(t,str) for t in supplied): raise HTTPException(422,'Close-all tickets must list the fresh open position IDs')
        supplied=[canonical_ticket(t) for t in supplied]
        if len(supplied)!=len(set(supplied)) or set(supplied)!={item[0] for item in targets}: raise HTTPException(409,'Close-all tickets do not exactly match fresh open position IDs')
        confirmation=(digest(raw_token),managed['account_mode'],actual['login'],actual['server'])
    if len({item[0] for item in targets})!=len(targets): raise HTTPException(409,'Fresh snapshot contains duplicate target tickets')
    expiry=time.time()+30
    config={'tickets':[item[0] for item in targets],'expires_at':iso(datetime.fromtimestamp(expiry,timezone.utc)),
            'account_id':beat['account']['login'],'account_login':beat['account']['login'],
            'account_server':beat['account']['server'],'account_mode':managed['account_mode']}
    if confirmation: config['confirmation']=cfg['confirmation']
    serialized=json.dumps(config,separators=(',',':'),allow_nan=False)
    with tx(True) as c:
        concurrent=c.execute('SELECT * FROM commands WHERE pairing_id=? AND request_id=?',(p['id'],x.request_id)).fetchone()
        if concurrent:
            if concurrent['action']!=x.action or concurrent['request_config']!=raw_config: raise HTTPException(409,'request_id was already used for a different command payload')
            return {'id':concurrent['id'],'request_id':concurrent['request_id'],'action':concurrent['action'],'config':json.loads(concurrent['config']),'status':'acknowledged' if concurrent['ack'] else 'queued'}
        if confirmation:
            row=c.execute('SELECT * FROM close_all_confirmations WHERE token_hash=? AND pairing_id=?',(confirmation[0],p['id'])).fetchone()
            if (not row or row['used'] or row['expires_at']<=time.time() or row['account_mode']!=confirmation[1]
                or row['login']!=confirmation[2] or row['server']!=confirmation[3]):
                raise HTTPException(409,'Close-all confirmation is expired, already used, or bound to a different account')
        for _,target_id,kind,_ in targets:
            unresolved=c.execute("SELECT 1 FROM command_items i JOIN commands cmd ON cmd.id=i.command_id WHERE cmd.pairing_id=? AND i.target_kind=? AND i.target_id=? AND i.operational_status IN ('PENDING','PARTIAL') AND (cmd.ack IS NOT NULL OR cmd.expires_at>?) LIMIT 1",(p['id'],kind,target_id,time.time())).fetchone()
            if unresolved: raise HTTPException(409,'An unresolved destructive operation already exists for this target')
        c.execute('INSERT INTO commands(pairing_id,request_id,action,config,expires_at,request_config) VALUES(?,?,?,?,?,?)',
                  (p['id'],x.request_id,x.action,serialized,expiry,raw_config))
        command_id=c.execute('SELECT last_insert_rowid()').fetchone()[0]
        c.executemany('INSERT INTO command_items(command_id,ticket,target_id,target_kind,target_volume) VALUES(?,?,?,?,?)',
                      [(command_id,ticket,target,kind,volume) for ticket,target,kind,volume in targets])
        if confirmation:
            updated=c.execute('UPDATE close_all_confirmations SET used=1 WHERE token_hash=? AND used=0 AND expires_at>?',(confirmation[0],time.time()))
            if updated.rowcount!=1: raise HTTPException(409,'Close-all confirmation was already used or expired')
    return {'id':command_id,'request_id':x.request_id,'action':x.action,'config':config,'status':'queued'}

@app.post('/v1/app/commands',status_code=202)
def command(x:Command,authorization:str|None=Header(default=None)):
    require_durable_execution()
    p=auth(authorization,'app'); check_json(x.config)
    try: uuid.UUID(x.request_id)
    except ValueError: raise HTTPException(422,'request_id must be a UUID')
    if x.action in DESTRUCTIVE_ACTIONS: return issue_destructive_command(x,p)
    if x.action not in ('START','STOP','PAUSE','RESUME','EMERGENCY_STOP'): raise HTTPException(422,'Invalid action')
    with tx() as c:
        m=c.execute('SELECT access_type,revoked,account_mode FROM managed_accounts WHERE pairing_id=?',(p['id'],)).fetchone()
    if x.action in ('START','RESUME'):
        if not m: raise HTTPException(409,'Managed account provisioning is required before trading can be enabled')
        beat=fresh_heartbeat(p)
        with tx() as c: job=c.execute("SELECT status FROM worker_jobs WHERE pairing_id=? ORDER BY created DESC LIMIT 1",(p['id'],)).fetchone()
        account=beat.get('account',{}) if beat else {}
        live_ok=(m['account_mode']!='LIVE' or (p['live_authorized_until'] is not None and p['live_authorized_until']>time.time() and p['live_authorized_login']==account.get('login') and p['live_authorized_server']==account.get('server')))
        symbols=x.config.get('symbols',[]) if isinstance(x.config,dict) else []
        if m['revoked'] or m['access_type']!='MASTER' or m['account_mode'] not in ('DEMO','LIVE') or not execution_state_eligible(beat) or not heartbeat_news_clear(beat,symbols) or not beat.get('terminal_connected') or not beat.get('trade_allowed') or not terminal_identity_matches(p,m['account_mode'],beat) or not job or job['status']!='READY' or not live_ok: raise HTTPException(409,'Managed execution is not authorized to start or resume: profile, MASTER access, eligible EA state, verified identity/mode, fresh CLEAR news verdict, verified identity/mode, fresh heartbeat, worker readiness, trade permission and current LIVE authorization are required')
    serialized=json.dumps(x.config,separators=(',',':'),allow_nan=False)
    with tx(True) as c:
        c.execute('INSERT OR IGNORE INTO commands(pairing_id,request_id,action,config) VALUES(?,?,?,?)',(p['id'],x.request_id,x.action,serialized))
        r=c.execute('SELECT * FROM commands WHERE pairing_id=? AND request_id=?',(p['id'],x.request_id)).fetchone()
        if r['action']!=x.action or r['config']!=serialized: raise HTTPException(409,'request_id was already used for a different command payload')
    return {'id':r['id'],'request_id':r['request_id'],'action':r['action'],'config':json.loads(r['config']),'status':'acknowledged' if r['ack'] else 'queued'}
@app.get('/v1/terminal/commands')
def poll(cursor:int=Query(default=0,ge=0),authorization:str|None=Header(default=None)):
    p=auth(authorization,'terminal')
    with tx() as c: rows=c.execute('SELECT * FROM commands WHERE pairing_id=? AND (id>? OR ack IS NULL) AND (expires_at IS NULL OR expires_at>?) ORDER BY id',(p['id'],cursor,time.time())).fetchall()
    commands=[{'id':r['id'],'request_id':r['request_id'],'action':r['action'],'config':json.loads(r['config']),**({'expires_at':r['config'] and json.loads(r['config']).get('expires_at')} if r['expires_at'] is not None else {})} for r in rows]
    return {'cursor':max([cursor]+[r['id'] for r in rows]),'commands':commands}
@app.post('/v1/terminal/commands/{command_id}/ack')
def acknowledge(command_id:int,x:Ack,authorization:str|None=Header(default=None)):
    require_durable_execution()
    p=auth(authorization,'terminal')
    if x.outcome not in ('APPLIED','REJECTED','UNKNOWN') or x.state not in ('READY','RUNNING','PAUSED','STOPPED','ERROR'): raise HTTPException(422,'Invalid acknowledgement')
    with tx(True) as c:
        r=c.execute('SELECT * FROM commands WHERE id=? AND pairing_id=?',(command_id,p['id'])).fetchone()
        if not r: raise HTTPException(404,'Command not found')
        if r['expires_at'] is not None and r['expires_at']<=time.time() and not r['ack']: raise HTTPException(410,'Command expired')
        if r['action'] in DESTRUCTIVE_ACTIONS:
            items=c.execute('SELECT ticket FROM command_items WHERE command_id=? ORDER BY rowid',(command_id,)).fetchall()
            requested=[i['ticket'] for i in items]
            if x.results is None or any(result.status not in ('APPLIED','REJECTED','UNKNOWN') for result in x.results): raise HTTPException(422,'Per-ticket results are required for destructive commands')
            if not x.results: raise HTTPException(422,'At least one per-ticket result is required')
            try: result_tickets=[canonical_ticket(result.ticket) for result in x.results]
            except HTTPException: raise
            if len(result_tickets)!=len(set(result_tickets)) or set(result_tickets)!=set(requested): raise HTTPException(422,'Acknowledgement must contain exactly one result for every requested ticket')
            prior_ack=json.loads(r['ack']) if r['ack'] else None
            previous={item['ticket']:item for item in (prior_ack or {}).get('results',[])}
            incoming={result.ticket:result for result in x.results}
            effective={}
            upgrades=set()
            for ticket in requested:
                old=previous.get(ticket,{'status':'UNKNOWN','message':'No result returned for this ticket'})
                new=incoming.get(ticket)
                if not new:
                    effective[ticket]={'ticket':ticket,'status':old['status'],'message':old.get('message','')}
                    continue
                old_status=old['status']; new_status=new.status
                if old_status in ('APPLIED','REJECTED') and new_status!=old_status:
                    raise HTTPException(409,'Per-ticket terminal result cannot be downgraded or changed after finalization')
                if old_status=='UNKNOWN' and new_status in ('APPLIED','REJECTED'): upgrades.add(ticket)
                effective[ticket]={'ticket':ticket,'status':new_status,'message':new.message}
            if x.outcome=='APPLIED' and any(effective[t]['status']!='APPLIED' for t in requested): raise HTTPException(422,'Overall APPLIED requires every ticket result to be APPLIED')
            if x.outcome=='REJECTED' and all(effective[t]['status']=='APPLIED' for t in requested): raise HTTPException(422,'Overall REJECTED conflicts with all per-ticket results')
            statuses={effective[t]['status'] for t in requested}
            merged_outcome='APPLIED' if statuses=={'APPLIED'} else ('REJECTED' if statuses=={'REJECTED'} else 'UNKNOWN')
            ack=json.dumps({'outcome':merged_outcome,'state':x.state,'message':x.message,'results':[effective[t] for t in requested]},separators=(',',':'))
            if r['ack'] and not upgrades and all(effective[t]['status']==previous[t]['status'] for t in requested):
                return {'id':command_id,'acknowledged':True}
            current_seq=c.execute('SELECT heartbeat_seq FROM pairings WHERE id=?',(p['id'],)).fetchone()['heartbeat_seq']
            for ticket,result in effective.items():
                if not r['ack'] or ticket in upgrades:
                    c.execute('UPDATE command_items SET ack_status=?,message=?,ack_heartbeat_seq=?,operational_status=? WHERE command_id=? AND ticket=?',
                              (result['status'],result['message'],current_seq,'REJECTED' if result['status']=='REJECTED' else 'PENDING',command_id,ticket))
            c.execute('UPDATE commands SET ack=? WHERE id=?',(ack,command_id))
            return {'id':command_id,'acknowledged':True}
        expected_state={'START':'RUNNING','RESUME':'RUNNING','PAUSE':'PAUSED','STOP':'STOPPED','EMERGENCY_STOP':'STOPPED'}.get(r['action'])
        if x.outcome=='APPLIED' and x.state!=expected_state: raise HTTPException(422,'Applied acknowledgement state does not match command action')
        ack=json.dumps({'outcome':x.outcome,'state':x.state,'message':x.message},separators=(',',':'))
        if r['ack'] and r['ack']!=ack: raise HTTPException(409,'Command already acknowledged differently')
        c.execute('UPDATE commands SET ack=? WHERE id=?',(ack,command_id))
    return {'id':command_id,'acknowledged':True}
@app.post('/v1/terminal/heartbeat')
def heartbeat(x:Heartbeat,authorization:str|None=Header(default=None)):
    require_durable_execution()
    p=auth(authorization,'terminal'); check_json(x.model_dump())
    if x.state not in ('READY','RUNNING','PAUSED','STOPPED','ERROR'): raise HTTPException(422,'Invalid state')
    try: dt=datetime.fromisoformat(x.timestamp.replace('Z','+00:00'))
    except Exception: raise HTTPException(422,'Invalid UTC timestamp')
    if dt.tzinfo is None or abs((now()-dt.astimezone(timezone.utc)).total_seconds())>STALE: raise HTTPException(422,'Stale or future heartbeat timestamp')
    expected=json.loads(p['account'])
    if x.account.account_mode not in ('DEMO','LIVE') or any(getattr(x.account,k)!=expected.get(k) for k in ('login','server','company','currency','account_mode')):
        with tx(True) as c:
            c.execute('UPDATE pairings SET heartbeat=NULL,live_authorized_until=NULL,live_authorized_login=NULL,live_authorized_server=NULL WHERE id=?',(p['id'],))
            c.execute("UPDATE worker_jobs SET status='FAILED',error_code='TERMINAL_IDENTITY_MISMATCH',error_message='Terminal identity or account mode changed' WHERE pairing_id=? AND kind='PROVISION' AND status='READY'",(p['id'],))
        raise HTTPException(409,'Terminal account identity or mode mismatch')
    payload=x.model_dump(); payload['account']=x.account.model_dump(); payload['open_positions']=[v.model_dump() for v in x.open_positions]; payload['closed_deals']=[v.model_dump() for v in x.closed_deals]; payload['timestamp']=iso(dt.astimezone(timezone.utc))
    for cancelled_ticket in x.cancelled_order_tickets: canonical_ticket(cancelled_ticket)
    with tx(True) as c:
        c.execute('UPDATE pairings SET heartbeat=?,heartbeat_seq=heartbeat_seq+1 WHERE id=?',(json.dumps(payload,separators=(',',':'),allow_nan=False),p['id']))
        seq=c.execute('SELECT heartbeat_seq FROM pairings WHERE id=?',(p['id'],)).fetchone()['heartbeat_seq']
        managed=c.execute('SELECT access_type,revoked,account_mode FROM managed_accounts WHERE pairing_id=?',(p['id'],)).fetchone()
        job=c.execute("SELECT status FROM worker_jobs WHERE pairing_id=? ORDER BY created DESC LIMIT 1",(p['id'],)).fetchone()
        if (managed and not managed['revoked'] and managed['access_type']=='MASTER' and managed['account_mode']==x.account.account_mode
            and job and job['status']=='READY' and x.terminal_connected and terminal_identity_matches(p,managed['account_mode'],payload)):
            reconcile_command_items(c,p['id'],payload,seq)
        # Heartbeats establish terminal telemetry only.  A heartbeat must never
        # promote a provisioning job: after lease expiry, an old terminal could
        # otherwise mark a job reclaimed by another worker as READY.  Only the
        # lease-owning worker's authenticated status poll may perform that
        # transition (see worker_job_status above).
    with tx() as c:
        row=c.execute('SELECT live_authorized_until,live_authorized_login,live_authorized_server FROM pairings WHERE id=?',(p['id'],)).fetchone()
    live_until=row['live_authorized_until'] if row and x.account.account_mode=='LIVE' and row['live_authorized_login']==x.account.login and row['live_authorized_server']==x.account.server else None
    if live_until is not None and live_until <= time.time(): live_until=None
    return {'accepted':True,'received_at':iso(),'live_authorized_until':live_until}
@app.delete('/v1/app/pairing')
def revoke_pairing(authorization:str|None=Header(default=None)):
    require_durable_execution()
    p=auth(authorization,'app')
    with tx(True) as c:
        c.execute('UPDATE managed_accounts SET revoked=1,login_enc=?,server_enc=?,password_enc=? WHERE pairing_id=?',('', '', '', p['id']))
        c.execute("INSERT INTO worker_jobs(id,pairing_id,kind,status,created) SELECT ?,id,'REVOKE','QUEUED',? FROM pairings WHERE id=?",(str(uuid.uuid4()),time.time(),p['id']))
        c.execute('UPDATE pairings SET app_hash=?,terminal_hash=NULL,consumed=1,heartbeat=NULL,live_authorized_until=NULL,live_authorized_login=NULL,live_authorized_server=NULL WHERE id=?',(digest(secrets.token_urlsafe(32)),p['id']))
        c.execute('DELETE FROM commands WHERE pairing_id=?',(p['id'],))
    return {'revoked':True,'deprovision_queued':True}

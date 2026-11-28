import asyncio, os, sqlite3, secrets, hashlib, hmac, json, time, math, uuid, ipaddress, urllib.request, urllib.error, base64
import re
import threading
import stat
import logging
import subprocess
from datetime import datetime, timezone, timedelta
from contextlib import contextmanager
from functools import lru_cache
from typing import Any
from urllib.parse import urlparse, quote, urlencode
from fastapi import FastAPI, Request, HTTPException, Header, Query
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, ConfigDict, StrictBool, StrictFloat, StrictInt, StrictStr, model_validator
from cryptography.fernet import Fernet, InvalidToken
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
import jwt
from jwt import PyJWKClient
from host_lifecycle import backup_database, cancel_stop, inspect_shutdown_safety, read_status, touch_activity
from pathlib import Path
from quantitative import STRATEGY_RULESETS, analyze_json
from mt5_server_catalog import search_mt5_servers


DB_PATH=os.getenv('BRIDGE_DB_PATH','/data/bridge.sqlite3')
HOST_CONTROL_DIR=os.getenv('DESTINEDFX_HOST_CONTROL_DIR',os.path.join(os.path.dirname(os.path.abspath(DB_PATH)),'runtime'))


def secret_value(name):
    """Resolve a secret from the process environment or a protected systemd credential file."""
    value=os.getenv(name,'').strip()
    if value:
        return value
    credential=os.getenv(name+'_FILE','').strip()
    if not credential:
        return ''
    fd=None
    try:
        fd=os.open(credential,os.O_RDONLY|getattr(os,'O_NOFOLLOW',0)|getattr(os,'O_CLOEXEC',0))
        info=os.fstat(fd)
        if (not stat.S_ISREG(info.st_mode) or info.st_nlink!=1
                or stat.S_IMODE(info.st_mode)&0o077
                or info.st_uid not in (0,os.geteuid())):
            return ''
        with os.fdopen(fd,'r',encoding='utf-8') as stream:
            fd=None
            return stream.read(8193).strip()
    except (OSError,UnicodeError):
        return ''
    finally:
        if fd is not None:
            os.close(fd)
def text_setting(name):
    """Resolve non-secret multiline configuration from the environment or an owner-managed file."""
    value=os.getenv(name,'')
    if value:
        return value.strip()
    file_path=os.getenv(name+'_FILE','').strip()
    if not file_path:
        return ''
    try:
        fd=os.open(file_path,os.O_RDONLY|getattr(os,'O_NOFOLLOW',0)|getattr(os,'O_CLOEXEC',0))
        with os.fdopen(fd,'r',encoding='utf-8') as stream:
            return stream.read(16385).strip()
    except (OSError,UnicodeError):
        return ''


def configured_host_profile(value=None):
    """Resolve an explicit host profile; an unspecified deployment is never trusted as persistent."""
    configured=os.getenv('BRIDGE_HOST_PROFILE','EPHEMERAL') if value is None else value
    profile=str(configured).strip().upper()
    if profile not in ('PERSISTENT','EPHEMERAL'):
        raise ValueError('BRIDGE_HOST_PROFILE must be PERSISTENT or EPHEMERAL')
    return profile

HOST_PROFILE=configured_host_profile()
HOST_GENERATION=os.getenv('HOST_GENERATION','persistent-default').strip()
if not HOST_GENERATION or len(HOST_GENERATION)>128:
    raise ValueError('HOST_GENERATION must be a bounded non-empty identifier')
HOST_IDLE_TIMEOUT_SECONDS=max(300,min(14400,int(os.getenv('HOST_IDLE_TIMEOUT_SECONDS','14400'))))
HOST_STARTED_AT_EPOCH_SECONDS=max(0,int(os.getenv('HOST_STARTED_AT_EPOCH_SECONDS',str(int(time.time())))))
HOST_MAX_LIFETIME_SECONDS=max(300,min(43200,int(os.getenv('HOST_MAX_LIFETIME_SECONDS','43200'))))
HOST_WARNING_SECONDS=max(60,min(HOST_MAX_LIFETIME_SECONDS,int(os.getenv('HOST_WARNING_SECONDS','3600'))))
HOST_EMERGENCY_STOP_LEAD_SECONDS=max(60,min(1800,int(os.getenv('HOST_EMERGENCY_STOP_LEAD_SECONDS','600'))))
LIFECYCLE_LOG=logging.getLogger('destinedfx.bridge.host-lifecycle')

def host_lifecycle_state(now_epoch=None):
    """Describe the absolute Codespaces lifetime separately from resettable idle timeout."""
    if HOST_PROFILE != 'EPHEMERAL' or HOST_STARTED_AT_EPOCH_SECONDS <= 0:
        return {
            'host_lifecycle_status':'PERSISTENT' if HOST_PROFILE == 'PERSISTENT' else 'UNKNOWN',
            'max_lifetime_seconds':None,
            'host_warning_seconds':HOST_WARNING_SECONDS,
            'host_emergency_stop_lead_seconds':HOST_EMERGENCY_STOP_LEAD_SECONDS,
            'host_expiry_at_epoch_seconds':None,
            'host_remaining_seconds':None,
            'host_shutdown_pending':False,
        }
    current=time.time() if now_epoch is None else float(now_epoch)
    expiry=HOST_STARTED_AT_EPOCH_SECONDS + HOST_MAX_LIFETIME_SECONDS
    remaining=max(0,int(expiry-current))
    pending=remaining <= HOST_EMERGENCY_STOP_LEAD_SECONDS
    warning=remaining <= HOST_WARNING_SECONDS
    return {
        'host_lifecycle_status':'SHUTDOWN_PENDING' if pending else ('WARNING' if warning else 'ACTIVE'),
        'max_lifetime_seconds':HOST_MAX_LIFETIME_SECONDS,
        'host_warning_seconds':HOST_WARNING_SECONDS,
        'host_emergency_stop_lead_seconds':HOST_EMERGENCY_STOP_LEAD_SECONDS,
        'host_expiry_at_epoch_seconds':expiry,
        'host_remaining_seconds':remaining,
        'host_shutdown_pending':pending,
    }
ALLOW_LOOPBACK=os.getenv('ALLOW_LOOPBACK_HTTP','false').lower()=='true'
MAX_BODY=int(os.getenv('MAX_BODY_BYTES','524288'))
PAIR_RATE=int(os.getenv('PAIR_RATE_LIMIT','10'))
OWNER_BOOTSTRAP_RATE=int(os.getenv('OWNER_BOOTSTRAP_RATE_LIMIT','10'))
TOKEN_RATE=int(os.getenv('TOKEN_RATE_LIMIT','180'))
# K3 public-exposure hardening. All of it is inert unless BRIDGE_PUBLIC_EXPOSURE=true.
PUBLIC_EXPOSURE=os.getenv('BRIDGE_PUBLIC_EXPOSURE','false').strip().lower()=='true'
EXPECTED_PUBLIC_HOST=os.getenv('EXPECTED_FORWARDED_HOST','').strip().lower().rstrip('.')
if PUBLIC_EXPOSURE and not EXPECTED_PUBLIC_HOST:
    raise ValueError('BRIDGE_PUBLIC_EXPOSURE requires EXPECTED_FORWARDED_HOST')
IP_RATE=int(os.getenv('IP_RATE_LIMIT','600'))
AUTH_FAIL_LIMIT=int(os.getenv('AUTH_FAIL_LIMIT','10'))
AUTH_FAIL_WINDOW_SECONDS=int(os.getenv('AUTH_FAIL_WINDOW_SECONDS','300'))
LOCKOUT_SECONDS=int(os.getenv('AUTH_LOCKOUT_SECONDS','900'))
BODY_READ_TIMEOUT_SECONDS=float(os.getenv('BODY_READ_TIMEOUT_SECONDS','10'))
BOOTSTRAP_TTL_SECONDS=600
LOOPBACK_HOSTS=('127.0.0.1','::1','localhost')
STALE=int(os.getenv('HEARTBEAT_STALE_SECONDS','15'))
RECOVERY_GRACE=int(os.getenv('HEARTBEAT_RECOVERY_GRACE_SECONDS','120'))
CONNECT_JOB_TTL_SECONDS=600
def _isolation_evidence_verified():
    """Return whether an approved worker has supplied independently verified slot evidence.

    The production default is deliberately fail-closed.  Test fixtures may replace this
    hook to exercise legacy downstream flows without claiming production isolation.
    """
    return False


def _attestation_signature(secret, evidence):
    payload = {key: evidence[key] for key in sorted(evidence) if key != "signature"}
    raw = json.dumps(payload, sort_keys=True, separators=(",", ":"), allow_nan=False)
    return hmac.new(secret.encode(), raw.encode(), hashlib.sha256).hexdigest()
NEWS_BEFORE=int(os.getenv('NEWS_BLACKOUT_BEFORE_SECONDS','1800'))
NEWS_AFTER=int(os.getenv('NEWS_BLACKOUT_AFTER_SECONDS','1800'))
NEWS_MAX_AGE=min(120,max(1,int(os.getenv('NEWS_MAX_AGE_SECONDS','120'))))
NEWS_CACHE_TTL=min(NEWS_MAX_AGE,max(1,int(os.getenv('NEWS_CACHE_TTL_SECONDS','10'))))
NEWS_PROVIDER_URL=os.getenv('NEWS_PROVIDER_URL','').strip()
NEWS_PROVIDER_TOKEN=secret_value('NEWS_PROVIDER_TOKEN')

def news_provider_configuration():
    """Expose a safe readiness flag without revealing provider URL or bearer token."""
    try:
        parsed=urlparse(NEWS_PROVIDER_URL)
        host=(parsed.hostname or '').lower().rstrip('.')
        configured=bool(
            parsed.scheme.lower()=='https' and host and
            not parsed.username and not parsed.password and
            not parsed.query and not parsed.fragment and
            parsed.path not in ('/.well-known/security.txt',)
        )
        if configured and host in ('showmytrades.com','www.showmytrades.com'):
            configured=parsed.path.rstrip('/')=='/api/public/calendar/v1'
            kind='showmytrades-public-calendar' if configured else 'invalid'
        else:
            kind='custom-json-provider' if configured else 'unconfigured'
        return {'configured':configured,'kind':kind if configured else 'unconfigured'}
    except Exception:
        return {'configured':False,'kind':'unconfigured'}
MANAGEMENT_STEP_UP_PUBLIC_KEY=text_setting('MANAGEMENT_STEP_UP_PUBLIC_KEY')
MANAGEMENT_STEP_UP_ISSUER=os.getenv('MANAGEMENT_STEP_UP_ISSUER','destinedfx-management').strip()
MANAGEMENT_STEP_UP_AUDIENCE=os.getenv('MANAGEMENT_STEP_UP_AUDIENCE','destinedfx-bridge').strip()

def management_step_up_key_fingerprint():
    """Return the configured Ed25519 public-key fingerprint, or empty when invalid/missing."""
    if not MANAGEMENT_STEP_UP_PUBLIC_KEY:
        return ''
    try:
        key=serialization.load_pem_public_key(MANAGEMENT_STEP_UP_PUBLIC_KEY.encode('utf-8'))
        if not isinstance(key,Ed25519PublicKey):
            return ''
        der=key.public_bytes(
            encoding=serialization.Encoding.DER,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        return hashlib.sha256(der).hexdigest()
    except Exception:
        return ''
LINUX_HOST_MODE=os.getenv('BRIDGE_HOST_MODE','').strip().lower()=='linux-host'
HOST_AGENT_URL=os.getenv('HOST_AGENT_URL','http://127.0.0.1:8787/health/ready').strip()
ANALYSIS_CLOCK_SKEW_MS=max(0,int(os.getenv('ANALYSIS_CLOCK_SKEW_SECONDS','120')))*1000
ANALYSIS_QUOTE_MAX_AGE_MS=max(1,int(os.getenv('ANALYSIS_QUOTE_MAX_AGE_SECONDS','15')))*1000
ANALYSIS_TIMEFRAME_POLICY={
    'M1':(60_000,300_000),'M5':(300_000,900_000),'M15':(900_000,2_700_000),
    'M30':(1_800_000,5_400_000),'H1':(3_600_000,10_800_000),
    'H4':(14_400_000,43_200_000),'D1':(86_400_000,172_800_000),
    'W1':(604_800_000,1_209_600_000),
}
ANALYSIS_SYMBOL_POLICY={
    'EUR/USD':('FOREX',5,0.005,0.01),'GBP/USD':('FOREX',5,0.005,0.01),
    'USD/JPY':('FOREX',3,0.5,0.01),'XAU/USD':('METAL',2,5.0,0.02),
    'BTC/USD':('CRYPTO',2,200.0,0.05),
}
ANALYSIS_SESSION_POLICY={'FOREX':True,'METAL':True,'CRYPTO':False}
_NEWS_CACHE={}
app=FastAPI(title='DestinedFX MT5 Bridge', version='1.0')
_HOST_LIFECYCLE_TASK=None

# Analyzer data is a short-lived, read-only projection, separate from Bot pairings and commands.
_ANALYZER_FEED_LOCK=threading.Lock()
_ANALYZER_FEED_STATE={
    'heartbeat_at_ms':0,
    'terminal_connected':False,
    'account_identity_sha256':'',
    'symbols':[],
    'broker_quotes':[],
    'broker_candles':[],
}
_ANALYZER_MARKET_SELECTION={'symbol':'','timeframe':'M15'}

async def _host_lifecycle_guard():
    """Queue an idempotent safety stop before an EPHEMERAL host reaches its hard limit."""
    while True:
        try:
            queued=await asyncio.to_thread(queue_host_expiry_safety_stops)
            if queued:
                LIFECYCLE_LOG.warning("Host-expiry safety stop queued for %s managed pairing(s)",queued)
        except Exception:
            # Never log job payloads, broker identity, or credentials. Retry on the next tick.
            LIFECYCLE_LOG.error("Host-expiry safety stop scheduling failed; retrying")
        await asyncio.sleep(5)

@app.on_event('startup')
async def _start_host_lifecycle_guard():
    global _HOST_LIFECYCLE_TASK
    if HOST_PROFILE == 'EPHEMERAL' and HOST_STARTED_AT_EPOCH_SECONDS > 0 and _HOST_LIFECYCLE_TASK is None:
        _HOST_LIFECYCLE_TASK=asyncio.create_task(_host_lifecycle_guard())

@app.on_event('shutdown')
async def _stop_host_lifecycle_guard():
    global _HOST_LIFECYCLE_TASK
    task=_HOST_LIFECYCLE_TASK
    _HOST_LIFECYCLE_TASK=None
    if task is not None:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass

def cloudflare_access_settings():
    team_domain=os.getenv('CF_ACCESS_TEAM_DOMAIN','').strip().rstrip('/')
    audience=os.getenv('CF_ACCESS_AUD','').strip()
    if not team_domain and not audience: return None
    parsed=urlparse(team_domain)
    host=(parsed.hostname or '').lower().rstrip('.')
    if (parsed.scheme!='https' or parsed.port is not None or parsed.path or parsed.query or parsed.fragment or parsed.username or parsed.password or
        not re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.cloudflareaccess\.com',host) or
        not audience or len(audience)>2048):
        raise ValueError('Cloudflare Access origin validation is incompletely or incorrectly configured')
    return team_domain,audience

@lru_cache(maxsize=4)
def _cloudflare_jwks_client(team_domain):
    return PyJWKClient(team_domain+'/cdn-cgi/access/certs',cache_jwk_set=True,lifespan=300,timeout=5)

def validate_cloudflare_assertion(assertion):
    settings=cloudflare_access_settings()
    if settings is None: return False
    team_domain,audience=settings
    signing_key=_cloudflare_jwks_client(team_domain).get_signing_key_from_jwt(assertion)
    claims=jwt.decode(assertion,signing_key.key,algorithms=['RS256'],audience=audience,issuer=team_domain,
        options={'require':['exp','iat','iss','aud']},leeway=30)
    return isinstance(claims,dict)

def now(): return datetime.now(timezone.utc)
def iso(dt=None): return (dt or now()).isoformat().replace('+00:00','Z')
def digest(s): return hashlib.sha256(s.encode()).hexdigest()
def linux_host_report():
    if not LINUX_HOST_MODE: return {'healthy': True, 'mode': 'windows-or-managed-host'}
    try:
        try:
            with urllib.request.urlopen(HOST_AGENT_URL, timeout=2) as response:
                payload=json.loads(response.read(128000).decode('utf-8'))
        except urllib.error.HTTPError as response:
            payload=json.loads(response.read(128000).decode('utf-8'))
        if not isinstance(payload,dict): raise ValueError('host-agent response is not an object')
    except Exception as exc:
        raise HTTPException(503, detail={'code':'LINUX_HOST_AGENT_UNHEALTHY','message':'Linux host agent is unavailable or reports blocked readiness'}) from exc
    if payload.get('wine_ok') is False:
        raise HTTPException(503, detail={'code':'WINE_VERSION_UNSUPPORTED','message':'Host Wine version is unsupported; run the guard fix'})
    if not payload.get('healthy'):
        raise HTTPException(503, detail={'code':'LINUX_HOST_AGENT_UNHEALTHY','message':'Linux host agent is unavailable or reports blocked readiness'})
    isolation=payload.get('slot_isolation') or {}
    if LINUX_HOST_MODE and (not isolation.get('enabled') or not isolation.get('ready')):
        raise HTTPException(503,detail={'code':'PER_USER_TERMINAL_ISOLATION_REQUIRED','message':'The root host agent has not verified the isolated per-pairing MT5 slot runtime'})
    return payload


def linux_slot_isolation(host):
    """Return slot isolation evidence, defaulting to unverified when no host report exists."""
    if not isinstance(host, dict):
        return {}
    isolation = host.get('slot_isolation')
    return isolation if isinstance(isolation, dict) else {}


def require_live_host_isolation(minimum_active_slots=2):
    """Require root-attested per-user Linux process isolation for LIVE.

    EPHEMERAL Codespaces can run LIVE after explicit owner risk acceptance; it
    needs one isolated terminal slot. Persistent Linux retains its two-slot and
    verified-backup requirements.
    """
    if LINUX_HOST_MODE:
        host = linux_host_report()
        isolation = linux_slot_isolation(host)
        active = isolation.get('active_slots')
        maximum = isolation.get('max_slots')
        valid_active = isinstance(active, int) and not isinstance(active, bool)
        valid_maximum = isinstance(maximum, int) and not isinstance(maximum, bool)
        required_slots = 1 if HOST_PROFILE == 'EPHEMERAL' else max(1, minimum_active_slots)
        if isolation.get('enabled') is not True or isolation.get('ready') is not True:
            raise HTTPException(503, detail={
                'code': 'PER_USER_TERMINAL_ISOLATION_REQUIRED',
                'message': 'The root host agent has not verified isolated per-user MT5 terminals',
            })
        if not valid_maximum or maximum < required_slots or not valid_active or active < required_slots:
            raise HTTPException(409, detail={
                'code': 'LIVE_ISOLATION_CAPACITY_NOT_READY',
                'message': f'LIVE requires {required_slots} active root-attested MT5 terminal slot(s) on this host',
                'active_slots': active if valid_active else 0,
                'max_slots': maximum if valid_maximum else 0,
            })
        backup = host.get('backup') if isinstance(host, dict) else None
        if HOST_PROFILE == 'PERSISTENT' and (not isinstance(backup, dict) or backup.get('status') != 'PASS'):
            raise HTTPException(503, detail={
                'code': 'LIVE_BACKUP_NOT_VERIFIED',
                'message': 'LIVE requires recent checksum-verified Bridge and Management database backups on the persistent Linux host',
                'backup_status': backup.get('status', 'NOT_VERIFIED') if isinstance(backup, dict) else 'NOT_VERIFIED',
            })
        return isolation
    if not _isolation_evidence_verified():
        raise HTTPException(409, detail={
            'code': 'MULTI_USER_OS_ISOLATION_NOT_VERIFIED',
            'message': 'This Windows worker has not proved separate per-user process identities and account ACLs',
        })
    return {}


def host_agent_shared_token():
    """Read the root-agent secret from a systemd credential, with env fallback for test/dev hosts."""
    secret=os.getenv('HOST_AGENT_TOKEN','').strip()
    if secret:
        return secret
    credential_path=os.getenv('HOST_AGENT_TOKEN_FILE','').strip()
    if not credential_path:
        return ''
    try:
        path=Path(credential_path)
        info=path.lstat()
        if (not stat.S_ISREG(info.st_mode) or info.st_nlink!=1
                or stat.S_IMODE(info.st_mode)&0o077
                or info.st_uid not in (0,os.geteuid())):
            return ''
        return path.read_text(encoding='utf-8').strip()
    except (OSError,UnicodeError):
        return ''


def host_agent_slot_request(path, payload):
    """Request root-only validation of a reserved or running per-user MT5 slot."""
    if not LINUX_HOST_MODE or not path.startswith('/internal/slot/'):
        raise HTTPException(503,detail={'code':'SLOT_ATTESTATION_UNAVAILABLE','message':'Isolated slot verification is unavailable'})
    secret=host_agent_shared_token()
    parsed=urlparse(HOST_AGENT_URL)
    health_path=parsed.path.rstrip('/')
    if health_path.endswith('/health/ready'):
        health_path=health_path[:-len('/health/ready')]
    if (len(secret)<32 or parsed.scheme!='http' or parsed.hostname not in LOOPBACK_HOSTS
            or parsed.username or parsed.password or parsed.query or parsed.fragment
            or health_path not in ('','/')):
        raise HTTPException(503,detail={'code':'SLOT_ATTESTATION_UNAVAILABLE','message':'Root host-agent authentication is not configured'})
    # HOST_AGENT_URL is the readiness URL used by linux_host_report(); internal
    # slot checks must target the same loopback agent origin, not append to /health/ready.
    agent_origin=f'{parsed.scheme}://{parsed.netloc}'
    raw=json.dumps(payload,separators=(',',':'),allow_nan=False).encode('utf-8')
    req=urllib.request.Request(f'{agent_origin}{path}',data=raw,headers={
        'Authorization':'Bearer '+secret,'Content-Type':'application/json','Accept':'application/json'
    },method='POST')
    try:
        with urllib.request.urlopen(req,timeout=4) as response:
            result=json.loads(response.read(32768).decode('utf-8'))
    except urllib.error.HTTPError as error:
        try: detail=json.loads(error.read(4096).decode('utf-8'))
        except Exception: detail={}
        code=detail.get('code','SLOT_AGENT_REJECTED') if isinstance(detail,dict) else 'SLOT_AGENT_REJECTED'
        raise HTTPException(409,detail={'code':code,'message':'Root host agent rejected isolated MT5 process evidence'}) from None
    except Exception:
        raise HTTPException(503,detail={'code':'SLOT_AGENT_UNAVAILABLE','message':'Root host agent could not verify the isolated MT5 process'}) from None
    if not isinstance(result,dict) or result.get('verified') is not True:
        raise HTTPException(409,detail={'code':'SLOT_AGENT_REJECTED','message':'Root host agent did not verify the isolated MT5 process'})
    return result


def _slot_process_payload(row, pid=None, start_ticks=None):
    return {
        'pairing_id':row['pairing_id'],'job_id':row['job_id'],
        'linux_user':row['linux_user'],'linux_uid':row['linux_uid'],
        'slot_root':row['slot_root'],'terminal_home':row['terminal_home'],
        'wine_prefix':row['wine_prefix'],'terminal_path':row['terminal_path'],
        'xauthority':row['xauthority'],'display':row['display'],
        'xvfb_pid':row['xvfb_pid'],'xvfb_start_ticks':row['xvfb_start_ticks'],
        'pid':pid if pid is not None else row['process_pid'],
        'start_ticks':start_ticks if start_ticks is not None else row['process_start_ticks'],
    }


def verify_linux_job_process(row, pid=None, start_ticks=None):
    if not LINUX_HOST_MODE:
        return True
    required=('job_id','linux_user','slot_root','terminal_home','wine_prefix','terminal_path',
              'xauthority','display','xvfb_pid','xvfb_start_ticks')
    if any(not row[key] for key in required) or (pid is None and not row['process_pid']) or (start_ticks is None and not row['process_start_ticks']):
        raise HTTPException(409,detail={'code':'LINUX_SLOT_EVIDENCE_INCOMPLETE','message':'Finalized per-user MT5 process evidence is incomplete'})
    host_agent_slot_request('/internal/slot/process',_slot_process_payload(row,pid,start_ticks))
    return True
def broker_company_matches(brand, company):
    """Bind an MT5 profile to the broker identity actually reported by its terminal."""
    key=str(brand or 'UNKNOWN').strip().upper()
    normalized=''.join(ch for ch in str(company or '').upper() if ch.isalnum())
    if key not in ('FBS', 'EXNESS', 'HFM', 'DERIV'):
        return False
    aliases={
        'FBS': ('FBS',),
        'EXNESS': ('EXNESS',),
        'HFM': ('HFMARKETS', 'HOTFOREX'),
        'DERIV': ('DERIV',),
    }
    markers=aliases.get(key)
    return bool(normalized and markers and any(marker in normalized for marker in markers))

def broker_server_matches(brand, expected_server, actual_server):
    """Require the authenticated terminal to use the exact server pinned for this broker profile."""
    key=str(brand or 'UNKNOWN').strip().upper()
    expected=str(expected_server or '').strip()
    actual=str(actual_server or '').strip()
    return key in ('FBS', 'EXNESS', 'HFM', 'DERIV') and bool(expected) and hmac.compare_digest(expected, actual)

def token(): return secrets.token_urlsafe(32)
def db():
    os.makedirs(os.path.dirname(os.path.abspath(DB_PATH)),exist_ok=True)
    c=sqlite3.connect(DB_PATH, timeout=10, isolation_level=None)
    c.row_factory=sqlite3.Row
    c.execute('PRAGMA journal_mode=WAL'); c.execute('PRAGMA foreign_keys=ON'); c.execute('PRAGMA busy_timeout=10000')
    c.executescript('''        CREATE TABLE IF NOT EXISTS pairings(id TEXT PRIMARY KEY, code_hash TEXT NOT NULL, app_hash TEXT NOT NULL, terminal_hash TEXT, expires REAL NOT NULL, consumed INTEGER NOT NULL DEFAULT 0, terminal_id TEXT, instance_id TEXT, account TEXT, heartbeat TEXT, created REAL NOT NULL, live_authorized_until REAL, live_authorized_login TEXT, live_authorized_server TEXT, heartbeat_seq INTEGER NOT NULL DEFAULT 0, owner_subject TEXT, owner_tenant TEXT, revocation_pending INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS commands(id INTEGER PRIMARY KEY AUTOINCREMENT, pairing_id TEXT NOT NULL REFERENCES pairings(id), request_id TEXT NOT NULL, action TEXT NOT NULL, config TEXT NOT NULL, ack TEXT, expires_at REAL, request_config TEXT, host_generation TEXT, UNIQUE(pairing_id,request_id));
    CREATE TABLE IF NOT EXISTS host_control(key TEXT PRIMARY KEY, value TEXT NOT NULL, updated REAL NOT NULL);
    CREATE TABLE IF NOT EXISTS close_all_confirmations(token_hash TEXT PRIMARY KEY, pairing_id TEXT NOT NULL REFERENCES pairings(id) ON DELETE CASCADE, account_mode TEXT NOT NULL, login TEXT NOT NULL, server TEXT NOT NULL, expires_at REAL NOT NULL, used INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS command_items(command_id INTEGER NOT NULL REFERENCES commands(id) ON DELETE CASCADE, ticket TEXT NOT NULL, target_id TEXT NOT NULL, target_kind TEXT NOT NULL, target_volume REAL NOT NULL DEFAULT 0.0, remaining_volume REAL, ack_status TEXT, message TEXT NOT NULL DEFAULT '', operational_status TEXT NOT NULL DEFAULT 'PENDING', ack_heartbeat_seq INTEGER, PRIMARY KEY(command_id,ticket));
    CREATE TABLE IF NOT EXISTS rate(ip TEXT PRIMARY KEY, window REAL NOT NULL, count INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS request_rate(token_hash TEXT PRIMARY KEY, window INTEGER NOT NULL, count INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS managed_accounts(pairing_id TEXT PRIMARY KEY REFERENCES pairings(id) ON DELETE CASCADE, login_enc TEXT NOT NULL, server_enc TEXT NOT NULL, password_enc TEXT NOT NULL, access_type TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0, account_mode TEXT NOT NULL DEFAULT 'UNVERIFIED', broker_brand TEXT NOT NULL DEFAULT 'UNKNOWN', credential_generation TEXT NOT NULL DEFAULT '');
    CREATE TABLE IF NOT EXISTS worker_identities(worker_id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, status TEXT NOT NULL DEFAULT 'ACTIVE', created REAL NOT NULL, expires_at REAL NOT NULL, last_seen REAL, generation INTEGER NOT NULL DEFAULT 1);
    CREATE TABLE IF NOT EXISTS worker_jobs(id TEXT PRIMARY KEY, pairing_id TEXT NOT NULL REFERENCES pairings(id), kind TEXT NOT NULL DEFAULT 'PROVISION', status TEXT NOT NULL, worker_id TEXT, lease_until REAL, lease_started REAL, stale_since REAL, recovery_deadline REAL, error_code TEXT, error_message TEXT, created REAL NOT NULL, requested_account_mode TEXT);
    CREATE TABLE IF NOT EXISTS worker_attestations(id TEXT PRIMARY KEY, pairing_id TEXT NOT NULL REFERENCES pairings(id), worker_id TEXT NOT NULL, slot_id TEXT NOT NULL, linux_uid INTEGER NOT NULL, wine_prefix TEXT NOT NULL, terminal_path TEXT NOT NULL, process_identity TEXT NOT NULL, ea_identity TEXT NOT NULL, broker_brand TEXT NOT NULL, login TEXT NOT NULL, server TEXT NOT NULL, account_mode TEXT NOT NULL, host_generation TEXT NOT NULL, issued_at REAL NOT NULL, expires_at REAL NOT NULL, nonce TEXT NOT NULL UNIQUE, signature TEXT NOT NULL, used INTEGER NOT NULL DEFAULT 0, finalized INTEGER NOT NULL DEFAULT 0, job_id TEXT REFERENCES worker_jobs(id) ON DELETE SET NULL, linux_user TEXT, slot_root TEXT, terminal_home TEXT, xauthority TEXT, display TEXT, xvfb_pid INTEGER, xvfb_start_ticks INTEGER, process_pid INTEGER, process_start_ticks INTEGER);
    CREATE TABLE IF NOT EXISTS trade_ledger(pairing_id TEXT NOT NULL REFERENCES pairings(id) ON DELETE CASCADE, identity TEXT NOT NULL, account_mode TEXT NOT NULL, position_id TEXT, order_ticket TEXT, deal_ticket TEXT, symbol TEXT NOT NULL, direction TEXT, status TEXT NOT NULL, payload TEXT NOT NULL, first_seen REAL NOT NULL, updated REAL NOT NULL, PRIMARY KEY(pairing_id,identity));
    CREATE TABLE IF NOT EXISTS trade_ledger_events(id INTEGER PRIMARY KEY AUTOINCREMENT, pairing_id TEXT NOT NULL REFERENCES pairings(id) ON DELETE CASCADE, identity TEXT NOT NULL, account_mode TEXT NOT NULL, position_id TEXT, order_ticket TEXT, deal_ticket TEXT, symbol TEXT NOT NULL, direction TEXT, status TEXT NOT NULL, payload TEXT NOT NULL, signature TEXT NOT NULL, created REAL NOT NULL);
    CREATE INDEX IF NOT EXISTS idx_trade_ledger_events_pair_cursor ON trade_ledger_events(pairing_id,id);
    CREATE TABLE IF NOT EXISTS analysis_requests(pairing_id TEXT NOT NULL REFERENCES pairings(id) ON DELETE CASCADE, request_id TEXT NOT NULL, payload_hash TEXT NOT NULL, response TEXT NOT NULL, created REAL NOT NULL, PRIMARY KEY(pairing_id,request_id));
    CREATE TABLE IF NOT EXISTS ip_guard(ip TEXT PRIMARY KEY, window REAL NOT NULL, count INTEGER NOT NULL, fails INTEGER NOT NULL, fail_window REAL NOT NULL, locked_until REAL NOT NULL);
    CREATE TABLE IF NOT EXISTS owner_bootstrap(code_hash TEXT PRIMARY KEY, expires REAL NOT NULL, used INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS demo_acceptances(
        id TEXT PRIMARY KEY, pairing_id TEXT NOT NULL UNIQUE REFERENCES pairings(id) ON DELETE CASCADE,
        broker_brand TEXT NOT NULL, login TEXT NOT NULL, server TEXT NOT NULL, company TEXT NOT NULL,
        currency TEXT NOT NULL, account_mode TEXT NOT NULL, heartbeat_seq INTEGER NOT NULL,
        evidence_digest TEXT NOT NULL, accepted_at REAL NOT NULL, expires_at REAL NOT NULL,
        credential_generation TEXT NOT NULL DEFAULT '', worker_id TEXT NOT NULL DEFAULT '',
        worker_generation INTEGER NOT NULL DEFAULT 0, host_generation TEXT NOT NULL DEFAULT '',
        pass_evidence_digest TEXT NOT NULL DEFAULT '', revoked INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS demo_soak_daily(
        pairing_id TEXT NOT NULL REFERENCES pairings(id) ON DELETE CASCADE,
        day_utc TEXT NOT NULL, first_seen REAL NOT NULL, last_seen REAL NOT NULL,
        heartbeat_count INTEGER NOT NULL DEFAULT 0, risk_clear_count INTEGER NOT NULL DEFAULT 0,
        news_clear_count INTEGER NOT NULL DEFAULT 0, reconciliation_count INTEGER NOT NULL DEFAULT 0,
        ea_instance_ids TEXT NOT NULL DEFAULT '[]', account_digest TEXT NOT NULL,
        credential_generation TEXT NOT NULL, worker_id TEXT NOT NULL, worker_generation INTEGER NOT NULL,
        host_generation TEXT NOT NULL, invalid INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY(pairing_id,day_utc)
    );
    CREATE INDEX IF NOT EXISTS idx_demo_soak_pair_day ON demo_soak_daily(pairing_id,day_utc);
    CREATE TABLE IF NOT EXISTS live_challenges(
        id TEXT PRIMARY KEY, pairing_id TEXT NOT NULL REFERENCES pairings(id) ON DELETE CASCADE,
        challenge_hash TEXT NOT NULL UNIQUE, demo_acceptance_id TEXT NOT NULL REFERENCES demo_acceptances(id),
        demo_pairing_id TEXT, broker_brand TEXT NOT NULL, login TEXT NOT NULL, server TEXT NOT NULL, company TEXT NOT NULL,
        worker_id TEXT NOT NULL, worker_generation INTEGER NOT NULL, host_generation TEXT NOT NULL, credential_generation TEXT NOT NULL DEFAULT '',
        expires_at REAL NOT NULL, used INTEGER NOT NULL DEFAULT 0, created_at REAL NOT NULL
    );''')
    c.execute('''CREATE TABLE IF NOT EXISTS live_profile_links(
        pairing_id TEXT PRIMARY KEY REFERENCES pairings(id) ON DELETE CASCADE,
        demo_acceptance_id TEXT NOT NULL REFERENCES demo_acceptances(id),
        demo_pairing_id TEXT,
        broker_brand TEXT NOT NULL, login TEXT NOT NULL, server TEXT NOT NULL,
        credential_generation TEXT NOT NULL, created_at REAL NOT NULL, revoked INTEGER NOT NULL DEFAULT 0
    )''')
    c.execute('CREATE INDEX IF NOT EXISTS idx_pairings_app_hash ON pairings(app_hash)')
    c.execute('CREATE INDEX IF NOT EXISTS idx_pairings_terminal_hash ON pairings(terminal_hash)')
    pairing_columns={r['name'] for r in c.execute('PRAGMA table_info(pairings)').fetchall()}
    for name, definition in (('live_authorized_until','REAL'),('live_authorized_login','TEXT'),('live_authorized_server','TEXT'),('heartbeat_seq','INTEGER NOT NULL DEFAULT 0'),('revocation_pending','INTEGER NOT NULL DEFAULT 0')):
        if name not in pairing_columns: c.execute(f'ALTER TABLE pairings ADD COLUMN {name} {definition}')
    if 'owner_subject' not in pairing_columns: c.execute("ALTER TABLE pairings ADD COLUMN owner_subject TEXT")
    if 'owner_tenant' not in pairing_columns: c.execute("ALTER TABLE pairings ADD COLUMN owner_tenant TEXT")
    managed_columns={r['name'] for r in c.execute('PRAGMA table_info(managed_accounts)').fetchall()}
    if 'credential_generation' not in managed_columns: c.execute("ALTER TABLE managed_accounts ADD COLUMN credential_generation TEXT NOT NULL DEFAULT ''")
    demo_columns={r['name'] for r in c.execute('PRAGMA table_info(demo_acceptances)').fetchall()}
    for name, definition in (
        ('credential_generation',"TEXT NOT NULL DEFAULT ''"),
        ('worker_id',"TEXT NOT NULL DEFAULT ''"),
        ('worker_generation','INTEGER NOT NULL DEFAULT 0'),
        ('host_generation',"TEXT NOT NULL DEFAULT ''"),
        ('pass_evidence_digest',"TEXT NOT NULL DEFAULT ''"),
    ):
        if name not in demo_columns: c.execute(f'ALTER TABLE demo_acceptances ADD COLUMN {name} {definition}')
    challenge_columns={r['name'] for r in c.execute('PRAGMA table_info(live_challenges)').fetchall()}
    for name, definition in (('credential_generation',"TEXT NOT NULL DEFAULT ''"),('demo_pairing_id','TEXT')):
        if name not in challenge_columns: c.execute(f'ALTER TABLE live_challenges ADD COLUMN {name} {definition}')
    link_columns={r['name'] for r in c.execute('PRAGMA table_info(live_profile_links)').fetchall()}
    if 'demo_pairing_id' not in link_columns: c.execute('ALTER TABLE live_profile_links ADD COLUMN demo_pairing_id TEXT')
    command_columns={r['name'] for r in c.execute('PRAGMA table_info(commands)').fetchall()}
    for name, definition in (('expires_at','REAL'),('request_config','TEXT'),('host_generation','TEXT')):
        if name not in command_columns: c.execute(f'ALTER TABLE commands ADD COLUMN {name} {definition}')
    item_columns={r['name'] for r in c.execute('PRAGMA table_info(command_items)').fetchall()}
    for name, definition in (('target_volume','REAL NOT NULL DEFAULT 0.0'),('remaining_volume','REAL')):
        if name not in item_columns: c.execute(f'ALTER TABLE command_items ADD COLUMN {name} {definition}')
    managed_columns={r['name'] for r in c.execute('PRAGMA table_info(managed_accounts)').fetchall()}
    if 'account_mode' not in managed_columns: c.execute("ALTER TABLE managed_accounts ADD COLUMN account_mode TEXT NOT NULL DEFAULT 'UNVERIFIED'")
    if 'broker_brand' not in managed_columns: c.execute("ALTER TABLE managed_accounts ADD COLUMN broker_brand TEXT NOT NULL DEFAULT 'UNKNOWN'")
    for name, definition in (('cost_model',"TEXT NOT NULL DEFAULT 'OBSERVED_ROUND_TURN'"),('commission_per_lot','REAL NOT NULL DEFAULT 0.0'),('cost_evidence',"TEXT NOT NULL DEFAULT ''")):
        if name not in managed_columns: c.execute(f'ALTER TABLE managed_accounts ADD COLUMN {name} {definition}')
    job_columns={r['name'] for r in c.execute('PRAGMA table_info(worker_jobs)').fetchall()}
    if 'lease_started' not in job_columns: c.execute('ALTER TABLE worker_jobs ADD COLUMN lease_started REAL')
    if 'stale_since' not in job_columns: c.execute('ALTER TABLE worker_jobs ADD COLUMN stale_since REAL')
    if 'recovery_deadline' not in job_columns: c.execute('ALTER TABLE worker_jobs ADD COLUMN recovery_deadline REAL')
    if 'requested_account_mode' not in job_columns: c.execute("ALTER TABLE worker_jobs ADD COLUMN requested_account_mode TEXT")
    attestation_columns={r['name'] for r in c.execute('PRAGMA table_info(worker_attestations)').fetchall()}
    for name,definition in (
        ('finalized','INTEGER NOT NULL DEFAULT 0'),('linux_user','TEXT'),('slot_root','TEXT'),
        ('terminal_home','TEXT'),('xauthority','TEXT'),('display','TEXT'),('xvfb_pid','INTEGER'),
        ('xvfb_start_ticks','INTEGER'),('process_pid','INTEGER'),('process_start_ticks','INTEGER'),
    ):
        if name not in attestation_columns: c.execute(f'ALTER TABLE worker_attestations ADD COLUMN {name} {definition}')
    return c
@contextmanager
def tx(immediate=False):
    c=db(); c.execute('BEGIN IMMEDIATE' if immediate else 'BEGIN')
    try: yield c; c.commit()
    except: c.rollback(); raise
    finally: c.close()


def _host_stop_prepared(connection):
    row = connection.execute(
        "SELECT value FROM host_control WHERE key='shutdown_prepared'"
    ).fetchone()
    return bool(row and row["value"] == HOST_GENERATION)


def _require_host_accepting_commands(connection):
    if _host_stop_prepared(connection):
        raise HTTPException(
            409,
            detail={
                "code": "HOST_SHUTDOWN_PREPARED",
                "message": "This Codespaces host is prepared for shutdown; new trading commands are blocked until shutdown is cancelled or the host restarts",
            },
        )


def queue_host_expiry_safety_stops(now_epoch=None):
    """Queue one non-liquidating EMERGENCY_STOP per connected account before host expiry."""
    lifecycle=host_lifecycle_state(now_epoch)
    if not lifecycle['host_shutdown_pending']:
        return 0
    queued=0
    with tx(True) as c:
        if _host_stop_prepared(c):
            return 0
        rows=c.execute(
            """SELECT p.id FROM pairings p
               JOIN managed_accounts m ON m.pairing_id=p.id
               WHERE p.consumed=1 AND m.revoked=0 AND m.account_mode IN ('DEMO','LIVE')
               ORDER BY p.id"""
        ).fetchall()
        for row in rows:
            pairing_id=row['id']
            request_id=str(uuid.uuid5(
                uuid.NAMESPACE_URL,
                'destinedfx-host-expiry-stop:' + HOST_GENERATION + ':' + pairing_id
            ))
            inserted=c.execute(
                """INSERT OR IGNORE INTO commands
                   (pairing_id,request_id,action,config,host_generation)
                   VALUES(?,?,'EMERGENCY_STOP','{}',?)""",
                (pairing_id,request_id,HOST_GENERATION)
            )
            queued += max(0,inserted.rowcount)
    return queued

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
    # Only the local MT5 market publisher uses plain HTTP; these routes cannot place trades.
    local_analyzer_path=request.url.path in (
        '/v1/analyzer/market-heartbeat',
        '/v1/analyzer/market-selection',
    )
    if local_analyzer_path and peer in ('127.0.0.1','::1','localhost','172.30.250.1'): return
    if ALLOW_LOOPBACK and peer in ('127.0.0.1','::1','localhost'): return
    raise HTTPException(400,'HTTPS is required')
def request_host(request):
    raw=(request.headers.get('host') or getattr(request.url,'hostname','') or '').strip().lower().rstrip('.')
    if raw.startswith('['): return raw[1:].split(']',1)[0]
    if raw.count(':')==1: return raw.rsplit(':',1)[0]
    return raw
def host_is_loopback(request):
    return request_host(request) in LOOPBACK_HOSTS
def client_ip(request):
    peer=request.client.host if request.client else 'unknown'
    if host_is_loopback(request): return 'local-loopback'
    if peer in LOOPBACK_HOSTS:
        # Cloudflare Tunnel connects over loopback. Prefer Cloudflare's single-IP
        # visitor header, then retain the existing last-entry X-Forwarded-For fallback.
        candidates=[
            request.headers.get('cf-connecting-ip',''),
            request.headers.get('x-forwarded-for','').split(',')[-1],
        ]
        for candidate in candidates:
            candidate=candidate.strip()
            if not candidate or len(candidate)>64:
                continue
            try:
                return str(ipaddress.ip_address(candidate))
            except ValueError:
                continue
        return 'public-unknown'
    return peer
def public_host_guard(request):
    if not PUBLIC_EXPOSURE: return None
    if (request.url.path in ('/v1/analyzer/market-heartbeat','/v1/analyzer/market-selection')
            and analyzer_feed_local_peer(request)):
        return None
    host=request.headers.get('host','').split(':')[0].strip('[]').lower().rstrip('.')
    if host==EXPECTED_PUBLIC_HOST or host in LOOPBACK_HOSTS: return None
    return JSONResponse({'detail':{'code':'HOST_NOT_ALLOWED'}},421)
def ip_guard_precheck(request):
    if not PUBLIC_EXPOSURE or request.url.path=='/health/live' or host_is_loopback(request): return None
    ip=client_ip(request); t=time.time()
    with tx(immediate=True) as c:
        row=c.execute('SELECT * FROM ip_guard WHERE ip=?',(ip,)).fetchone()
        if row and row['locked_until']>t:
            return JSONResponse({'detail':{'code':'TEMPORARILY_LOCKED'}},429,headers={'Retry-After':str(int(row['locked_until']-t)+1)})
        if row and t-row['window']<60:
            if row['count']>=IP_RATE:
                return JSONResponse({'detail':'Per-IP request rate limit exceeded'},429,headers={'Retry-After':'60'})
            c.execute('UPDATE ip_guard SET count=count+1 WHERE ip=?',(ip,))
        elif row:
            c.execute('UPDATE ip_guard SET window=?,count=1 WHERE ip=?',(t,ip))
        else:
            c.execute('INSERT INTO ip_guard(ip,window,count,fails,fail_window,locked_until) VALUES(?,?,1,0,?,0)',(ip,t,t))
    return None
def record_auth_failure(request):
    if not PUBLIC_EXPOSURE or request.url.path.startswith('/health/') or host_is_loopback(request): return
    ip=client_ip(request); t=time.time()
    with tx(immediate=True) as c:
        row=c.execute('SELECT fails,fail_window FROM ip_guard WHERE ip=?',(ip,)).fetchone()
        if row is None: return
        fails=row['fails']+1 if t-row['fail_window']<AUTH_FAIL_WINDOW_SECONDS else 1
        window_start=row['fail_window'] if fails>1 else t
        locked=t+LOCKOUT_SECONDS if fails>=AUTH_FAIL_LIMIT else 0
        c.execute('UPDATE ip_guard SET fails=?,fail_window=?,locked_until=? WHERE ip=?',(0 if locked else fails,window_start,locked,ip))
def issue_owner_bootstrap_code():
    code=secrets.token_urlsafe(16); t=time.time()
    with tx(immediate=True) as c:
        c.execute('DELETE FROM owner_bootstrap WHERE expires<? OR used=1',(t,))
        c.execute('INSERT INTO owner_bootstrap(code_hash,expires,used) VALUES(?,?,0)',(digest(code),t+BOOTSTRAP_TTL_SECONDS))
    return code,int(BOOTSTRAP_TTL_SECONDS)
def consume_owner_bootstrap_code(code):
    if not code or len(code)>200: return False
    t=time.time()
    with tx(immediate=True) as c:
        rows=c.execute('SELECT code_hash FROM owner_bootstrap WHERE used=0 AND expires>?',(t,)).fetchall()
        wanted=digest(code)
        found=next((r for r in rows if hmac.compare_digest(r['code_hash'],wanted)),None)
        if found is None: return False
        c.execute('UPDATE owner_bootstrap SET used=1 WHERE code_hash=? AND used=0',(wanted,))
        return c.total_changes>=1
def rotate_all_app_tokens():
    # Existing app tokens stop working; the app shows its re-pair state. Ledgers are kept.
    with tx(immediate=True) as c:
        c.execute("UPDATE pairings SET app_hash='revoked:'||id")
        return c.total_changes
@app.middleware('http')
async def limits(request:Request, call_next):
    stop_marker=os.path.join(HOST_CONTROL_DIR,'stop.request')
    if request.method in ('POST','PUT','PATCH','DELETE') and os.path.exists(stop_marker) and request.url.path not in ('/v1/app/host/cancel-stop',):
        return JSONResponse({'detail':{'code':'HOST_DRAINING','message':'Host shutdown is pending; new commands are temporarily blocked'}},503)
    secure(request)
    blocked=public_host_guard(request)
    if blocked is not None: return blocked
    blocked=ip_guard_precheck(request)
    if blocked is not None: return blocked
    try:
        access_settings=cloudflare_access_settings()
    except ValueError:
        return JSONResponse({'detail':{'code':'ACCESS_VALIDATION_MISCONFIGURED'}},503)
    local_analyzer_publisher = (
        request.url.path in ('/v1/analyzer/market-heartbeat','/v1/analyzer/market-selection')
        and analyzer_feed_local_peer(request)
    )
    if access_settings is not None and request.url.path != '/health/live' and not local_analyzer_publisher:
        assertion=request.headers.get('cf-access-jwt-assertion','').strip()
        if not assertion: return JSONResponse({'detail':{'code':'ACCESS_ASSERTION_REQUIRED'}},401)
        try: valid_access=validate_cloudflare_assertion(assertion)
        except Exception: valid_access=False
        if not valid_access: return JSONResponse({'detail':{'code':'ACCESS_ASSERTION_INVALID'}},401)
    authorization=request.headers.get('authorization','').strip()
    if authorization and not request.url.path.startswith('/health/'):
        token_hash=digest(authorization)
        window=int(time.time()//60)
        with tx(immediate=True) as c:
            row=c.execute('SELECT window,count FROM request_rate WHERE token_hash=?',(token_hash,)).fetchone()
            if row and row['window']==window and row['count']>=TOKEN_RATE:
                return JSONResponse({'detail':'Per-token request rate limit exceeded'},429,headers={'Retry-After':'60'})
            c.execute('''INSERT INTO request_rate(token_hash,window,count) VALUES(?,?,1)
                ON CONFLICT(token_hash) DO UPDATE SET
                count=CASE WHEN request_rate.window=excluded.window THEN request_rate.count+1 ELSE 1 END,
                window=excluded.window''',(token_hash,window))
    if request.method in ('POST','PUT','PATCH'):
        try: length=int(request.headers.get('content-length','0'))
        except ValueError: return JSONResponse({'detail':'Invalid content length'},400)
        if length>MAX_BODY: return JSONResponse({'detail':'Request body too large'},413)
        body=bytearray()
        async def read_body():
            async for chunk in request.stream():
                if len(body)+len(chunk)>MAX_BODY: return False
                body.extend(chunk)
            return True
        try: within_limit=await asyncio.wait_for(read_body(),BODY_READ_TIMEOUT_SECONDS)
        except asyncio.TimeoutError: return JSONResponse({'detail':'Request body timed out'},408)
        if not within_limit: return JSONResponse({'detail':'Request body too large'},413)
        request._body=bytes(body)
    response=await call_next(request)
    if response.status_code==401: record_auth_failure(request)
    return response
class Pair(BaseModel): model_config=ConfigDict(extra='forbid')
class GitHubTokenRevoke(BaseModel):
    model_config=ConfigDict(extra='forbid')
    access_token:str=Field(min_length=20,max_length=4096)


class ManagedAccount(BaseModel):
    model_config=ConfigDict(extra='forbid')
    login:str=Field(min_length=1,max_length=20); server:str=Field(min_length=1,max_length=128); password:str=Field(min_length=1,max_length=512); access_type:str; account_mode:str|None=None; consent:bool
    broker_brand:str=Field(default='UNKNOWN',min_length=1,max_length=20)
    cost_model:str=Field(default='OBSERVED_ROUND_TURN',min_length=1,max_length=40)
    commission_per_lot:float=Field(default=0.0,ge=0,allow_inf_nan=False)
    cost_evidence:str=Field(default='',max_length=512)
    isolation_attestation_id:str|None=Field(default=None,min_length=1,max_length=128)
    credential_generation:str=Field(default='',min_length=0,max_length=128)
class WorkerClaim(BaseModel):
    model_config=ConfigDict(extra='forbid')
    worker_id:str=Field(min_length=1,max_length=128)
    active_terminals:int=Field(default=0,ge=0,le=16)
    max_terminals:int=Field(default=1,ge=1,le=16)
    pause_provisioning:bool=False
    attestation:dict|None=None
class WorkerProvision(BaseModel):
    model_config=ConfigDict(extra='forbid')
    worker_id:str=Field(pattern=r'^[A-Za-z0-9._-]{1,128}$')
    expires_in_seconds:int=Field(default=86400,ge=300,le=90*86400)
class WorkerAttestation(BaseModel):
    model_config=ConfigDict(extra='forbid')
    pairing_id:str=Field(min_length=1,max_length=128)
    worker_id:str=Field(min_length=1,max_length=128)
    job_id:str|None=Field(default=None,min_length=1,max_length=128)
    slot_id:str=Field(min_length=1,max_length=128)
    linux_user:str|None=Field(default=None,min_length=1,max_length=32)
    linux_uid:int=Field(gt=0)
    slot_root:str|None=Field(default=None,min_length=1,max_length=512)
    terminal_home:str|None=Field(default=None,min_length=1,max_length=512)
    wine_prefix:str=Field(min_length=1,max_length=512)
    terminal_path:str=Field(min_length=1,max_length=512)
    xauthority:str|None=Field(default=None,min_length=1,max_length=512)
    display:str|None=Field(default=None,min_length=2,max_length=4)
    xvfb_pid:int|None=Field(default=None,gt=1)
    xvfb_start_ticks:int|None=Field(default=None,gt=0)
    process_identity:str=Field(min_length=1,max_length=512)
    ea_identity:str=Field(min_length=1,max_length=256)
    broker_brand:str=Field(min_length=1,max_length=20)
    login:str=Field(pattern=r'^[0-9]{1,20}$')
    server:str=Field(min_length=1,max_length=128)
    account_mode:str=Field(pattern=r'^(DEMO|LIVE)$')
    host_generation:str=Field(min_length=1,max_length=128)
    issued_at:float=Field(gt=0)
    expires_at:float=Field(gt=0)
    nonce:str=Field(min_length=16,max_length=128)
    signature:str=Field(pattern=r'^[0-9a-f]{64}$')
class WorkerAttestationFinalize(BaseModel):
    model_config=ConfigDict(extra='forbid')
    attestation_id:str=Field(min_length=1,max_length=128)
    worker_id:str=Field(min_length=1,max_length=128)
    terminal_path:str=Field(min_length=1,max_length=512)
    process_identity:str=Field(min_length=1,max_length=512)
    ea_identity:str=Field(min_length=1,max_length=256)
    pid:int|None=Field(default=None,gt=1)
    process_start_ticks:int|None=Field(default=None,gt=0)
    signature:str=Field(pattern=r'^[0-9a-f]{64}$')

class WorkerUpdate(BaseModel):
    model_config=ConfigDict(extra='forbid')
    status:str; worker_id:str=Field(min_length=1,max_length=128); error_code:str|None=Field(default=None,max_length=64); error_message:str|None=Field(default=None,max_length=200)

class Claim(BaseModel): model_config=ConfigDict(extra='forbid'); pairing_code:str=Field(min_length=8,max_length=64); terminal_instance_id:str=Field(min_length=1,max_length=128); account:dict[str,str]
class Command(BaseModel): model_config=ConfigDict(extra='forbid'); request_id:str=Field(min_length=1,max_length=64); action:str; config:dict[str,Any]
class AckResult(BaseModel): model_config=ConfigDict(extra='forbid'); ticket:str; status:str; message:str=Field(default='',max_length=1000)
class Ack(BaseModel): model_config=ConfigDict(extra='forbid'); outcome:str; state:str; message:str=Field(default='',max_length=1000); results:list[AckResult]|None=None
class LiveAuthorization(BaseModel):
    model_config=ConfigDict(extra='forbid')
    challenge_id:str=Field(min_length=1,max_length=128)
    typed_confirmation:str=Field(min_length=8,max_length=256)
    step_up_assertion:str=Field(min_length=100,max_length=8192)
class DemoPassEvidence(BaseModel):
    model_config=ConfigDict(extra='forbid')
    evidence_version:StrictInt=Field(ge=1,le=1)
    pairing_id:StrictStr=Field(min_length=1,max_length=128)
    broker:StrictStr=Field(min_length=1,max_length=20)
    mode:StrictStr=Field(min_length=1,max_length=16)
    status:StrictStr=Field(min_length=1,max_length=16)
    account_login:StrictStr=Field(pattern=r'^[0-9]{1,20}$')
    server:StrictStr=Field(min_length=1,max_length=128)
    credential_generation:StrictStr=Field(min_length=1,max_length=128)
    worker_id:StrictStr=Field(min_length=1,max_length=128)
    worker_generation:StrictInt=Field(ge=1)
    host_generation:StrictStr=Field(min_length=1,max_length=128)
    soak_days:StrictInt=Field(ge=5,le=7)
    accepted_days:list[StrictStr]=Field(min_length=5,max_length=7)
    restart_instances:StrictInt=Field(ge=2)
    closed_trade_count:StrictInt=Field(ge=1)
    order_lifecycle_pass:StrictBool
    daily_reconciliation_pass:StrictBool
    restart_test_pass:StrictBool
    risk_controls_pass:StrictBool
    evidence_paths:list[StrictStr]=Field(min_length=5,max_length=7)
    bridge_proof:StrictStr=Field(pattern=r'^[0-9a-f]{64}$')

def demo_pass_bridge_proof(evidence):
    """Checksum the canonical evidence payload, excluding its own checksum.

    This binds the submitted fields to one exact document; it is not a broker
    signature or proof that referenced files were independently inspected.
    """
    payload=evidence.model_dump(mode='json') if isinstance(evidence,DemoPassEvidence) else dict(evidence)
    payload.pop('bridge_proof',None)
    raw=json.dumps(payload,sort_keys=True,separators=(',',':'),allow_nan=False)
    return hashlib.sha256(raw.encode('utf-8')).hexdigest()

class DemoAcceptanceRequest(BaseModel):
    model_config=ConfigDict(extra='forbid')
    confirm_demo:StrictBool
    evidence_document:StrictStr=Field(min_length=1,max_length=65536)
class LiveProfileAssociationRequest(BaseModel):
    model_config=ConfigDict(extra='forbid')
    demo_pairing_id:str=Field(min_length=1,max_length=128)
    demo_acceptance_id:str=Field(min_length=1,max_length=128)
    confirm_association:StrictBool
class CloseAllConfirmation(BaseModel): model_config=ConfigDict(extra='forbid'); confirm_close_all:StrictBool
class AnalysisCandle(BaseModel):
    model_config=ConfigDict(extra='forbid')
    timestamp_ms:StrictInt=Field(gt=0)
    open:float=Field(gt=0,allow_inf_nan=False)
    high:float=Field(gt=0,allow_inf_nan=False)
    low:float=Field(gt=0,allow_inf_nan=False)
    close:float=Field(gt=0,allow_inf_nan=False)
    volume:float=Field(default=0.0,ge=0,allow_inf_nan=False)
    complete:StrictBool=True
    source:StrictStr=Field(min_length=1,max_length=128)
    retrieved_at_ms:StrictInt=Field(gt=0)
class AnalysisRequest(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_id:StrictStr=Field(min_length=1,max_length=64)
    symbol:StrictStr=Field(min_length=1,max_length=64)
    timeframe:StrictStr=Field(min_length=1,max_length=16)
    strategy_id:StrictStr=Field(min_length=1,max_length=64)
class ProviderCandle(BaseModel):
    model_config=ConfigDict(extra='forbid')
    timestamp_ms:StrictInt=Field(gt=0)
    open:StrictFloat=Field(gt=0,allow_inf_nan=False)
    high:StrictFloat=Field(gt=0,allow_inf_nan=False)
    low:StrictFloat=Field(gt=0,allow_inf_nan=False)
    close:StrictFloat=Field(gt=0,allow_inf_nan=False)
    volume:StrictFloat=Field(ge=0,allow_inf_nan=False)
    complete:StrictBool
class ProviderQuote(BaseModel):
    model_config=ConfigDict(extra='forbid')
    symbol:StrictStr=Field(min_length=1,max_length=64)
    bid:StrictFloat=Field(gt=0,allow_inf_nan=False)
    ask:StrictFloat=Field(gt=0,allow_inf_nan=False)
    timestamp_ms:StrictInt=Field(gt=0)
class ProviderSnapshot(BaseModel):
    model_config=ConfigDict(extra='forbid')
    provider_id:StrictStr=Field(min_length=1,max_length=128)
    symbol:StrictStr=Field(min_length=1,max_length=64)
    timeframe:StrictStr=Field(min_length=1,max_length=16)
    quote:ProviderQuote
    candles:list[ProviderCandle]=Field(min_length=1,max_length=500)
class AnalysisResponse(BaseModel):
    model_config=ConfigDict(extra='forbid')
    symbol:StrictStr
    strategy_id:StrictStr
    strategy_version:StrictStr
    timeframe:StrictStr
    state:StrictStr
    direction:StrictStr
    entry:float|None=None
    stop_loss:float|None=None
    take_profit:float|None=None
    reward_to_risk:float|None=None
    confidence_score:StrictInt
    score_basis:list[StrictStr]
    evidence:list[StrictStr]
    reason:StrictStr
    rejection_reasons:list[StrictStr]
    source:StrictStr
    candle_timestamp_ms:StrictInt
    provider_retrieved_at_ms:StrictInt
    quote_bid:float
    quote_ask:float
    quote_retrieved_at_ms:StrictInt
    quote_source:StrictStr
    server_now_ms:StrictInt
    executable:StrictBool

    @model_validator(mode='after')
    def validate_candidate_geometry(self):
        if self.state != 'CANDIDATE':
            return self
        if self.direction not in ('LONG', 'SHORT') or None in (self.entry, self.stop_loss, self.take_profit, self.reward_to_risk):
            raise ValueError('Candidate requires direction, entry, stop, target and reward-to-risk')
        entry=float(self.entry); stop=float(self.stop_loss); target=float(self.take_profit)
        if not all(math.isfinite(value) and value > 0 for value in (entry, stop, target)):
            raise ValueError('Candidate prices must be finite and positive')
        risk=entry-stop if self.direction=='LONG' else stop-entry
        reward=target-entry if self.direction=='LONG' else entry-target
        rr=float(self.reward_to_risk)
        computed=reward/risk if risk>0 else float('nan')
        if risk<=0 or reward<=0 or not math.isfinite(computed) or computed<2.0 or not math.isfinite(rr) or abs(computed-rr)>0.02:
            raise ValueError('Candidate stop/target geometry is invalid for its direction')
        return self

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
    balance:float|None=Field(default=None,ge=0,allow_inf_nan=False); equity:float|None=Field(default=None,ge=0,allow_inf_nan=False); margin:float|None=Field(default=None,ge=0,allow_inf_nan=False); free_margin:float|None=Field(default=None,ge=0,allow_inf_nan=False); margin_level:float|None=Field(default=None,ge=0,allow_inf_nan=False); leverage:int|None=Field(default=None,gt=0)
class TerminalQuote(BaseModel):
    model_config=ConfigDict(extra='forbid')
    symbol:StrictStr=Field(min_length=1,max_length=64)
    bid:float=Field(gt=0,allow_inf_nan=False)
    ask:float=Field(gt=0,allow_inf_nan=False)
    digits:StrictInt=Field(default=5,ge=0,le=12)
    timestamp_ms:StrictInt=Field(gt=0)

    @model_validator(mode='after')
    def validate_two_sided_quote(self):
        if self.ask < self.bid:
            raise ValueError('Terminal quote ask must be greater than or equal to bid')
        if any(ord(ch)<32 or ord(ch)==127 for ch in self.symbol):
            raise ValueError('Terminal quote symbol contains control characters')
        return self

class TerminalCandle(BaseModel):
    model_config=ConfigDict(extra='forbid')
    symbol:StrictStr=Field(min_length=1,max_length=64)
    timeframe:StrictStr=Field(min_length=2,max_length=3)
    timestamp_ms:StrictInt=Field(gt=0)
    open:float=Field(gt=0,allow_inf_nan=False)
    high:float=Field(gt=0,allow_inf_nan=False)
    low:float=Field(gt=0,allow_inf_nan=False)
    close:float=Field(gt=0,allow_inf_nan=False)
    volume:float=Field(ge=0,allow_inf_nan=False)
    complete:StrictBool=True

    @model_validator(mode='after')
    def validate_terminal_ohlc(self):
        if self.timeframe not in {'M1','M5','M15','M30','H1','H4','D1','W1'}:
            raise ValueError('Unsupported terminal candle timeframe')
        if self.high < max(self.open,self.close,self.low) or self.low > min(self.open,self.close,self.high):
            raise ValueError('Terminal OHLC values are inconsistent')
        if any(ord(ch)<32 or ord(ch)==127 for ch in self.symbol):
            raise ValueError('Terminal candle symbol contains control characters')
        if not self.complete:
            raise ValueError('Only completed terminal candles are accepted')
        return self

class AnalyzerMarketSelection(BaseModel):
    model_config=ConfigDict(extra='forbid')
    symbol:StrictStr=Field(min_length=1,max_length=32)
    timeframe:StrictStr=Field(min_length=2,max_length=3)

    @model_validator(mode='after')
    def validate_selection(self):
        if self.symbol.strip()!=self.symbol or not re.fullmatch(r'[A-Za-z0-9._# -]{1,32}',self.symbol):
            raise ValueError('Invalid MT5 Analyzer symbol')
        if self.timeframe not in {'M1','M5','M15','M30','H1','H4','D1','W1'}:
            raise ValueError('Unsupported MT5 Analyzer timeframe')
        return self

class AnalyzerMarketHeartbeat(BaseModel):
    model_config=ConfigDict(extra='forbid')
    terminal_connected:StrictBool
    account_login:StrictStr=Field(default='',max_length=20)
    account_server:StrictStr=Field(default='',max_length=128)
    account_mode:StrictStr=Field(default='',max_length=8)
    symbols:list[StrictStr]=Field(default_factory=list,max_length=128)
    broker_quotes:list[TerminalQuote]=Field(default_factory=list,max_length=128)
    broker_candles:list[TerminalCandle]=Field(default_factory=list,max_length=1000)

    @model_validator(mode='after')
    def validate_market_identity_and_symbols(self):
        if self.terminal_connected and (
            not re.fullmatch(r'[0-9]{1,20}',self.account_login) or
            not self.account_server.strip() or self.account_server.strip()!=self.account_server or
            self.account_mode not in {'DEMO','LIVE'}
        ):
            raise ValueError('Connected MT5 Analyzer feed must report its exact login, server and DEMO/LIVE mode')
        clean=[]
        seen=set()
        for value in self.symbols:
            symbol=value.strip()
            if symbol!=value or not re.fullmatch(r'[A-Za-z0-9._# -]{1,32}',symbol):
                raise ValueError('Invalid MT5 Market Watch symbol')
            key=symbol.upper()
            if key not in seen:
                seen.add(key)
                clean.append(symbol)
        self.symbols=clean
        return self

class Heartbeat(BaseModel):
    model_config=ConfigDict(extra='forbid')
    state:str; timestamp:str; terminal_connected:bool; trade_allowed:bool; account:Account; configuration:dict[str,Any]=Field(default_factory=dict); last_signal:Any=None; last_error:str=Field(default='',max_length=1000); open_positions:list[Position]; active_orders:list[ActiveOrder]=Field(default_factory=list); closed_deals:list[ClosedDeal]; news_verdict:dict[str,Any]=Field(default_factory=dict); cancelled_order_tickets:list[str]=Field(default_factory=list); broker_quotes:list[TerminalQuote]=Field(default_factory=list,max_length=32); broker_candles:list[TerminalCandle]=Field(default_factory=list,max_length=1000); active_chart_symbol:str=Field(default='',max_length=32); active_chart_timeframe:str=Field(default='M15',min_length=2,max_length=3)

def relevant_news_currencies(symbol):
    s=''.join(ch for ch in (symbol or '').upper() if ch.isalnum())
    explicit={'XAUUSD':{'XAU','USD'},'XAGUSD':{'XAG','USD'},'XPTUSD':{'XPT','USD'},'BTCUSD':{'BTC','USD'},'ETHUSD':{'ETH','USD'},'US30':{'USD'},'NAS100':{'USD'},'US100':{'USD'},'SPX500':{'USD'},'GER40':{'EUR'},'UK100':{'GBP'},'JPN225':{'JPY'}}
    if s in explicit: return explicit[s]
    # Explicitly known Deriv synthetic indices have no currency-macro exposure.
    # Unknown index-like names remain unsupported and fail closed.
    if re.fullmatch(
        r"(?:VOLATILITY[0-9]+(?:1S)?INDEX|V[0-9]+(?:1S)?|1HZ[0-9]+V|"
        r"BOOM[0-9]+INDEX|CRASH[0-9]+INDEX|STEPINDEX|JUMP[0-9]+INDEX|"
        r"RANGEBREAK[0-9]+INDEX|DRIFTSWITCH[0-9]+INDEX|DEX[0-9]+(?:UP|DOWN)?)",
        s,
    ):
        return set()
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
    if relevant == set():
        # Known synthetic indices do not have currency-macro event exposure.
        # This is a narrowly scoped calendar verdict, not permission to trade.
        now_ms=int(time.time()*1000)
        return {
            'status':'CLEAR',
            'source':'DestinedFX known synthetic-index macro policy',
            'affected_currencies':[],
            'retrieved_at_ms':now_ms,
            'coverage_from_ms':now_ms-60_000,
            'coverage_to_ms':now_ms+(max(NEWS_BEFORE,NEWS_AFTER,60)+60)*1000,
            'max_age_seconds':NEWS_MAX_AGE,
            'blackout_before_seconds':NEWS_BEFORE,
            'blackout_after_seconds':NEWS_AFTER,
            'detail':'NEWS CLEAR: recognized synthetic index has no currency-macro exposure',
        }
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
    opener=urllib.request.urlopen
    code=getattr(opener,'__code__',None)
    cache_identity=(getattr(code,'co_filename',None),getattr(code,'co_firstlineno',None),getattr(opener,'__qualname__',None))
    key=(NEWS_PROVIDER_URL, symbol_key, cache_identity)
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

def auth(header, kind, allow_revoked=False):
    if not header or not header.startswith('Bearer '): raise HTTPException(401,'Bearer token required')
    raw=header[7:]
    if len(raw)>200 or kind not in ('app','terminal'): raise HTTPException(401,'Unauthorized')
    field='app_hash' if kind=='app' else 'terminal_hash'
    wanted=digest(raw)
    revoked=False
    with tx() as c:
        row=c.execute(f'SELECT * FROM pairings WHERE {field}=? LIMIT 1',(wanted,)).fetchone()
        if row is not None and kind=='app' and not allow_revoked:
            revoked=bool(row['revocation_pending'])
    if row is not None and hmac.compare_digest(row[field] or '',wanted):
        if revoked: raise HTTPException(401,'Unauthorized')
        if kind=='app' or (row['consumed'] and row['terminal_hash']):
            return dict(row)
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
    placeholder=(host in {'example.com','example.net','testserver','bridge.example','localhost'} or host.endswith(('.example','.localhost','.test','.invalid')) or host.startswith('your-') or host.startswith('placeholder') or 'example' in host)
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
    key=secret_value('CREDENTIALS_FERNET_KEY')
    bootstrap=secret_value('WORKER_BOOTSTRAP_TOKEN') or secret_value('WORKER_TOKEN')
    bridge_url=os.getenv('BRIDGE_BASE_URL','').strip() or os.getenv('RENDER_EXTERNAL_URL','').strip()
    if not key or len(bootstrap)<32 or len(bootstrap)>512 or not validate_origin(bridge_url, allow_loopback=ALLOW_LOOPBACK):
        raise HTTPException(503, detail={'code':'CONFIGURATION_REQUIRED','message':'Managed execution is not configured'})
    try: return Fernet(key.encode()), ''
    except Exception: raise HTTPException(503, detail={'code':'CONFIGURATION_REQUIRED','message':'Managed execution is not configured'})
def durable_execution_enabled():
    """Require explicit operator confirmation that execution storage survives restarts."""
    return os.getenv('EXECUTION_STORAGE_DURABLE','false').strip().lower() == 'true'

def fetch_mt5_terminal_snapshot(symbol, timeframe, authorization):
    """Build analysis input only from the current, identity-verified MT5 terminal snapshot."""
    terminal = state(authorization)
    if (not terminal.get('terminal_online') or not terminal.get('terminal_connected') or
        not terminal.get('account_identity_verified') or not terminal.get('broker_identity_verified')):
        raise HTTPException(503,detail={'code':'MT5_TERMINAL_NOT_VERIFIED','message':'The selected MT5 account and terminal heartbeat are not verified'})
    observed_ms=int(time.time()*1000)
    def canonical_symbol(value):
        return ''.join(ch for ch in str(value or '').upper() if ch.isalnum())
    requested_key=canonical_symbol(symbol)
    matching_quotes=[q for q in terminal.get('broker_quotes',[])
                     if str(q.get('symbol','')).strip().upper()==symbol or
                        (requested_key and canonical_symbol(q.get('symbol'))==requested_key)]
    if len(matching_quotes)!=1:
        raise HTTPException(503,detail={'code':'MT5_TERMINAL_QUOTE_UNAVAILABLE','message':'No unique fresh bid/ask for this symbol was reported by the selected MT5 terminal; configure the exact broker symbol if it has a suffix'})
    quote_row=matching_quotes[0]
    terminal_symbol=str(quote_row.get('symbol','')).strip()
    candle_rows=[c for c in terminal.get('broker_candles',[])
                 if str(c.get('symbol','')).strip().upper()==terminal_symbol.upper() and
                    str(c.get('timeframe','')).strip().upper()==timeframe and c.get('complete') is True]
    candle_rows=sorted(candle_rows,key=lambda c:int(c.get('timestamp_ms',0)))[-250:]
    if len(candle_rows)<60:
        raise HTTPException(503,detail={'code':'MT5_TERMINAL_OHLC_UNAVAILABLE','message':'The selected MT5 terminal has not supplied at least 60 closed bars for this symbol and timeframe'})
    try:
        quote=ProviderQuote.model_validate({k:quote_row[k] for k in ('symbol','bid','ask','timestamp_ms')})
        candles=[ProviderCandle.model_validate({k:c[k] for k in ('timestamp_ms','open','high','low','close','volume','complete')}) for c in candle_rows]
    except Exception as error:
        raise HTTPException(503,detail={'code':'MT5_TERMINAL_MARKET_DATA_INVALID','message':'MT5 terminal quote or OHLC data failed validation'}) from error
    asset_class,decimals,max_spread,max_deviation=ANALYSIS_SYMBOL_POLICY[symbol]
    precision=10**(-decimals)
    if any(abs(value-round(value,decimals))>precision/10 for value in (quote.bid,quote.ask)):
        raise HTTPException(503,detail={'code':'MT5_TERMINAL_INVALID_PRECISION','message':'MT5 terminal quote exceeds the instrument precision policy'})
    if quote.ask < quote.bid or quote.ask-quote.bid > max_spread:
        raise HTTPException(503,detail={'code':'MT5_TERMINAL_INVALID_SPREAD','message':'MT5 terminal quote spread is invalid or exceeds policy'})
    if quote.timestamp_ms>observed_ms+ANALYSIS_CLOCK_SKEW_MS or observed_ms-quote.timestamp_ms>ANALYSIS_QUOTE_MAX_AGE_MS:
        raise HTTPException(503,detail={'code':'MT5_TERMINAL_QUOTE_STALE','message':'MT5 terminal quote is stale or future-dated'})
    if any(a.timestamp_ms>=b.timestamp_ms for a,b in zip(candles,candles[1:])):
        raise HTTPException(503,detail={'code':'MT5_TERMINAL_CANDLES_UNORDERED','message':'MT5 terminal OHLC bars are not strictly ordered'})
    interval_ms,max_age_ms=ANALYSIS_TIMEFRAME_POLICY[timeframe]
    latest=candles[-1]
    if latest.timestamp_ms>observed_ms+ANALYSIS_CLOCK_SKEW_MS or observed_ms-latest.timestamp_ms>max_age_ms:
        raise HTTPException(503,detail={'code':'MT5_TERMINAL_CANDLES_STALE','message':'Latest closed MT5 terminal bar is stale'})
    for previous,current in zip(candles,candles[1:]):
        delta=current.timestamp_ms-previous.timestamp_ms
        if delta==interval_ms:
            continue
        weekend_gap=asset_class in ('FOREX','METAL') and 2*86_400_000<=delta<=72*60*60*1000
        if not weekend_gap:
            raise HTTPException(503,detail={'code':'MT5_TERMINAL_CANDLE_GAP','message':'MT5 terminal OHLC has an unexplained session gap'})
    if max(abs(quote.bid-latest.close),abs(quote.ask-latest.close))/latest.close>max_deviation:
        raise HTTPException(503,detail={'code':'MT5_TERMINAL_QUOTE_DEVIATION','message':'Live MT5 bid/ask differs materially from its latest closed broker candle'})
    snapshot=ProviderSnapshot.model_validate({
        'provider_id':'MT5_TERMINAL','symbol':terminal_symbol,'timeframe':timeframe,
        'quote':quote.model_dump(mode='python'),
        'candles':[c.model_dump(mode='python') for c in candles]
    })
    analysis_candles=[AnalysisCandle(timestamp_ms=c.timestamp_ms,open=c.open,high=c.high,low=c.low,
                     close=c.close,volume=c.volume,complete=c.complete,source='MT5_TERMINAL',
                     retrieved_at_ms=observed_ms) for c in snapshot.candles]
    return {'provider_id':'MT5_TERMINAL','observed_ms':observed_ms,'quote':snapshot.quote,
            'candles':analysis_candles,'asset_class':asset_class}

def validate_analysis_snapshot(x, provider):
    server_now_ms=provider['observed_ms']
    timeframe=x.timeframe.strip().upper()
    policy=ANALYSIS_TIMEFRAME_POLICY.get(timeframe)
    if policy is None:
        raise HTTPException(422,'Unsupported timeframe; expected one of M1, M5, M15, M30, H1, H4, D1, W1')
    interval_ms,max_age_ms=policy
    if x.strategy_id not in STRATEGY_RULESETS:
        raise HTTPException(422,'Unsupported strategy ID')
    if provider['asset_class'] not in ANALYSIS_SESSION_POLICY:
        raise HTTPException(422,'Unsupported instrument session metadata')
    return server_now_ms,timeframe,interval_ms,max_age_ms,ANALYSIS_SESSION_POLICY[provider['asset_class']]

def require_durable_execution():
    if not durable_execution_enabled():
        raise HTTPException(503, detail={'code':'NON_DURABLE_STORAGE','message':'Execution-sensitive operations are disabled on non-durable storage'})
def worker_auth(header):
    managed_config()
    if not header or not header.startswith('Bearer '):
        raise HTTPException(401,'Unauthorized')
    presented=header[7:].strip()
    if not presented or len(presented)>512:
        raise HTTPException(401,'Unauthorized')
    with tx(True) as c:
        row=c.execute('SELECT worker_id,status,expires_at FROM worker_identities WHERE token_hash=?',
                      (digest(presented),)).fetchone()
        if row and row['status']=='ACTIVE' and row['expires_at'] > time.time():
            c.execute('UPDATE worker_identities SET last_seen=? WHERE worker_id=?',(time.time(),row['worker_id']))
            return {'worker_id':row['worker_id'],'token':presented}
    # Test fixtures may opt into the legacy shared token. Production deployments
    # must provision named worker identities and leave this disabled.
    legacy=os.getenv('WORKER_TOKEN','')
    if os.getenv('ALLOW_LEGACY_WORKER_TOKEN','false').lower()=='true' and legacy and hmac.compare_digest(presented,legacy):
        return {'worker_id':None,'token':presented}
    raise HTTPException(401,'Unauthorized')

def require_worker_identity(context, worker_id):
    if context.get('worker_id') is not None and not hmac.compare_digest(context['worker_id'],worker_id):
        raise HTTPException(403, detail={'code':'WORKER_IDENTITY_MISMATCH','message':'Worker credential is not authorized for this worker'})

def operator_auth(header):
    bootstrap=secret_value('WORKER_BOOTSTRAP_TOKEN')
    if not bootstrap or not header or not header.startswith('Bearer ') or not hmac.compare_digest(header[7:].strip(),bootstrap):
        raise HTTPException(401,'Unauthorized')
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

def live_risk_controls(beat):
    """Derive LIVE risk evidence only from the current authenticated MT5 EA heartbeat."""
    config = beat.get('configuration') if isinstance(beat, dict) else None
    if not isinstance(config, dict):
        config = {}

    risk = config.get('risk_percent')
    daily_limit = config.get('max_daily_loss_percent')
    concurrent = config.get('max_concurrent_positions')
    daily_status = str(config.get('daily_risk_status', 'UNKNOWN')).upper()
    daily_used = config.get('daily_loss_used_percent')

    def finite_number(value):
        return (isinstance(value, (int, float)) and not isinstance(value, bool)
                and math.isfinite(float(value)))

    risk_valid = finite_number(risk) and 0.0 < float(risk) <= 1.0
    daily_limit_valid = finite_number(daily_limit) and 0.0 < float(daily_limit) <= 2.0
    concurrent_valid = (isinstance(concurrent, int) and not isinstance(concurrent, bool)
                        and 1 <= concurrent <= 2)
    risk_caps_clear = risk_valid and daily_limit_valid and concurrent_valid

    daily_used_valid = finite_number(daily_used) and 0.0 <= float(daily_used) < 100.0
    daily_status_clear = daily_status in ('NORMAL', 'WARNING_80', 'WARNING_90')
    daily_loss_clear = daily_status_clear and daily_used_valid

    reasons = []
    if not risk_valid:
        reasons.append('TRADE_RISK_PERCENT_MISSING_OR_ABOVE_1_PERCENT')
    if not daily_limit_valid:
        reasons.append('DAILY_LOSS_CAP_MISSING_OR_ABOVE_2_PERCENT')
    if not concurrent_valid:
        reasons.append('MAX_CONCURRENT_POSITIONS_MISSING_OR_ABOVE_2')
    if not daily_status_clear:
        reasons.append('DAILY_RISK_STATUS_NOT_CLEAR')
    if not daily_used_valid:
        reasons.append('DAILY_LOSS_USAGE_MISSING_INVALID_OR_BREACHED')

    return {
        'source': 'authenticated_mt5_ea_heartbeat',
        'observed_at': beat.get('timestamp') if isinstance(beat, dict) else None,
        'risk_percent': float(risk) if risk_valid else None,
        'max_daily_loss_percent': float(daily_limit) if daily_limit_valid else None,
        'max_concurrent_positions': concurrent if concurrent_valid else None,
        'daily_risk_status': daily_status,
        'daily_loss_used_percent': float(daily_used) if finite_number(daily_used) else None,
        'daily_loss_clear': daily_loss_clear,
        'risk_caps_clear': risk_caps_clear,
        'authorized': daily_loss_clear and risk_caps_clear,
        'reasons': reasons,
    }


def require_live_risk_evidence(beat):
    evidence = live_risk_controls(beat)
    if not evidence['authorized']:
        raise HTTPException(409, detail={
            'code': 'LIVE_RISK_EVIDENCE_INVALID',
            'message': 'Fresh EA daily-risk telemetry and conservative risk caps are required for LIVE authorization',
            'daily_loss_clear': evidence['daily_loss_clear'],
            'risk_caps_clear': evidence['risk_caps_clear'],
            'reasons': evidence['reasons'],
        })
    return evidence

def live_authorization_valid(p, managed, beat, now_epoch=None):
    """Require the current LIVE lease and its exact, currently valid DEMO profile association."""
    now_epoch = time.time() if now_epoch is None else now_epoch
    if not p or not managed or managed['revoked'] or managed['access_type'] != 'MASTER':
        return False
    try:
        require_live_host_isolation(minimum_active_slots=2)
    except HTTPException:
        return False
    if managed['account_mode'] != 'LIVE' or not beat:
        return False
    if not execution_state_eligible(beat) or not beat.get('terminal_connected') or not beat.get('trade_allowed'):
        return False
    if not terminal_identity_matches(p, 'LIVE', beat):
        return False
    if not live_risk_controls(beat)['authorized']:
        return False
    account = beat.get('account') or {}
    if not (
        p['live_authorized_until'] is not None
        and p['live_authorized_until'] > now_epoch
        and p['live_authorized_login'] == account.get('login')
        and p['live_authorized_server'] == account.get('server')
        and account.get('account_mode') == 'LIVE'
    ):
        return False
    try:
        with tx() as c:
            link=c.execute('SELECT * FROM live_profile_links WHERE pairing_id=? AND revoked=0',(p['id'],)).fetchone()
            live_managed=c.execute('SELECT * FROM managed_accounts WHERE pairing_id=?',(p['id'],)).fetchone()
        _verified_live_demo_association(p,link,live_managed,beat,now_epoch)
        return True
    except Exception:
        return False


def _management_step_up_claims(assertion):
    if not MANAGEMENT_STEP_UP_PUBLIC_KEY:
        raise HTTPException(503,detail={'code':'STEP_UP_NOT_CONFIGURED','message':'Management step-up verification is not configured'})
    try:
        claims=jwt.decode(
            assertion, MANAGEMENT_STEP_UP_PUBLIC_KEY, algorithms=['EdDSA'],
            audience=MANAGEMENT_STEP_UP_AUDIENCE, issuer=MANAGEMENT_STEP_UP_ISSUER,
            options={'require':['iss','aud','sub','tenant_id','sid','jti','iat','exp','pairing_id','challenge_id',
                                'typed_confirmation','broker_brand','login','server','account_mode',
                                'credential_generation']},
            leeway=10,
        )
    except Exception as exc:
        raise HTTPException(401,detail={'code':'STEP_UP_INVALID','message':'Signed management step-up assertion is invalid'}) from exc
    if not isinstance(claims,dict) or claims['account_mode']!='LIVE' or claims['tenant_id'] != claims['sub']:
        raise HTTPException(401,detail={'code':'STEP_UP_INVALID','message':'Signed management step-up assertion is invalid'})
    return claims

def _management_identity_claims(assertion):
    if not MANAGEMENT_STEP_UP_PUBLIC_KEY:
        raise HTTPException(503,detail={'code':'MANAGEMENT_IDENTITY_NOT_CONFIGURED','message':'Management identity verification is not configured'})
    try:
        claims=jwt.decode(
            assertion, MANAGEMENT_STEP_UP_PUBLIC_KEY, algorithms=['EdDSA'],
            audience=MANAGEMENT_STEP_UP_AUDIENCE, issuer=MANAGEMENT_STEP_UP_ISSUER,
            options={'require':['iss','aud','sub','tenant_id','sid','jti','iat','exp','purpose']},
            leeway=10,
        )
    except Exception as exc:
        raise HTTPException(401,detail={'code':'MANAGEMENT_IDENTITY_INVALID','message':'Signed management identity assertion is invalid'}) from exc
    if claims.get('purpose') != 'bridge_pairing' or claims.get('tenant_id') != claims.get('sub'):
        raise HTTPException(401,detail={'code':'MANAGEMENT_IDENTITY_INVALID','message':'Signed management identity assertion is invalid'})
    return claims

def _current_ready_context(p, expected_mode):
    with tx() as c:
        managed=c.execute('SELECT * FROM managed_accounts WHERE pairing_id=?',(p['id'],)).fetchone()
        job=c.execute("SELECT * FROM worker_jobs WHERE pairing_id=? ORDER BY created DESC LIMIT 1",(p['id'],)).fetchone()
        worker=c.execute('SELECT * FROM worker_identities WHERE worker_id=?',
                         (job['worker_id'],)).fetchone() if job and job['worker_id'] else None
    beat=fresh_heartbeat(p)
    if (not managed or managed['revoked'] or managed['account_mode']!=expected_mode or
        not job or job['status']!='READY' or not worker or worker['status']!='ACTIVE' or
        worker['expires_at']<=time.time() or not beat or not execution_state_eligible(beat) or
        not beat.get('terminal_connected') or not terminal_identity_matches(p,expected_mode,beat)):
        raise HTTPException(409,detail={'code':'ACCOUNT_EVIDENCE_NOT_READY','message':'Fresh authenticated account, worker and terminal evidence is required'})
    if expected_mode=='LIVE' and (not beat.get('trade_allowed') or not heartbeat_news_clear(beat)):
        raise HTTPException(409,detail={'code':'LIVE_EVIDENCE_NOT_READY','message':'LIVE terminal permission and clear news evidence are required'})
    return managed,job,worker,beat

def _verified_live_demo_association(p, link, live_managed, live_beat, now_epoch=None):
    """Validate separately paired DEMO and LIVE accounts under the same signed management tenant."""
    now_epoch = time.time() if now_epoch is None else now_epoch
    if not p or not p['owner_subject'] or not p['owner_tenant']:
        raise HTTPException(409,detail={'code':'MANAGEMENT_IDENTITY_REQUIRED','message':'The LIVE pairing must have a verified management identity'})
    if not link or not link['demo_pairing_id']:
        raise HTTPException(409,detail={'code':'DEMO_PROFILE_ASSOCIATION_REQUIRED','message':'A separately paired, accepted DEMO profile must be associated'})
    live_account=live_beat.get('account') or {}
    if any((
        link['broker_brand'] != live_managed['broker_brand'],
        link['login'] != live_account.get('login'),
        link['server'] != live_account.get('server'),
        link['credential_generation'] != live_managed['credential_generation'],
        not live_managed['credential_generation'],
    )):
        raise HTTPException(409,detail={'code':'LIVE_PROFILE_CHANGED','message':'The LIVE account or credential generation changed; associate it again'})
    with tx() as c:
        demo_pair=c.execute('SELECT * FROM pairings WHERE id=?',(link['demo_pairing_id'],)).fetchone()
        acceptance=c.execute(
            'SELECT * FROM demo_acceptances WHERE id=? AND pairing_id=? AND revoked=0 AND expires_at>?',
            (link['demo_acceptance_id'],link['demo_pairing_id'],now_epoch)
        ).fetchone()
    if not demo_pair or not demo_pair['consumed'] or not acceptance:
        raise HTTPException(409,detail={'code':'DEMO_ACCEPTANCE_INVALID','message':'The selected DEMO pairing or server acceptance is unavailable'})
    if (not demo_pair['owner_subject'] or not demo_pair['owner_tenant'] or
        demo_pair['owner_subject'] != p['owner_subject'] or demo_pair['owner_tenant'] != p['owner_tenant']):
        raise HTTPException(403,detail={'code':'DEMO_PROFILE_TENANT_MISMATCH','message':'The DEMO and LIVE profiles must belong to the same authenticated management tenant'})
    demo_managed,demo_job,demo_worker,demo_beat=_current_ready_context(dict(demo_pair),'DEMO')
    demo_account=demo_beat.get('account') or {}
    if (demo_managed['access_type'] != 'MASTER' or
        demo_managed['broker_brand'] != live_managed['broker_brand'] or
        acceptance['broker_brand'] != live_managed['broker_brand'] or
        acceptance['account_mode'] != 'DEMO' or
        not re.fullmatch(r'[0-9a-fA-F]{64}',acceptance['pass_evidence_digest'] or '') or
        not demo_managed['credential_generation'] or
        acceptance['credential_generation'] != demo_managed['credential_generation'] or
        acceptance['worker_id'] != demo_job['worker_id'] or
        acceptance['worker_generation'] != demo_worker['generation'] or
        acceptance['host_generation'] != HOST_GENERATION or
        any(acceptance[k] != demo_account.get(k) for k in ('login','server','company','currency')) or
        not heartbeat_news_clear(demo_beat)):
        raise HTTPException(409,detail={'code':'DEMO_PROFILE_CHANGED','message':'The accepted DEMO profile no longer matches its current account, PASS evidence, credentials, worker, host or news evidence'})
    return acceptance,demo_managed,demo_pair

def verified_terminal_pairing_exists():
    """Require a real fresh EA heartbeat and a worker-owned READY job."""
    with tx() as c:
        rows=c.execute('''SELECT p.*,m.account_mode,m.broker_brand,m.server_enc,m.access_type,j.status AS job_status
                          FROM pairings p
                          JOIN managed_accounts m ON m.pairing_id=p.id
                          JOIN worker_jobs j ON j.pairing_id=p.id
                          WHERE p.consumed=1 AND m.revoked=0 AND m.account_mode IN ('DEMO','LIVE')
                            AND j.status='READY'
                          ORDER BY j.created DESC''').fetchall()
    for row in rows:
        beat=fresh_heartbeat(row)
        if not beat or not beat.get('terminal_connected') or not execution_state_eligible(beat):
            continue
        if row['access_type']=='MASTER' and not beat.get('trade_allowed'):
            continue
        if not terminal_identity_matches(row,row['account_mode'],beat):
            continue
        try:
            fernet,_=managed_config()
            expected_server=fernet.decrypt(row['server_enc']).decode()
        except Exception:
            continue
        account=beat.get('account') or {}
        if not broker_server_matches(row['broker_brand'],expected_server,account.get('server')):
            continue
        if not broker_company_matches(row['broker_brand'],account.get('company')):
            continue
        return True
    return False

UINT64_MAX=18446744073709551615
DESTRUCTIVE_ACTIONS={'CANCEL_PENDING_SELECTED','CANCEL_EA_PENDING','CLOSE_POSITION_SELECTED','CLOSE_ALL'}
OPENING_ACTIONS={'PLACE_MARKET_ORDER'}
POSITION_MANAGEMENT_ACTIONS={'TIGHTEN_POSITION_SL'}

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
    linux_host_report()
    with tx() as c:
        managed=c.execute('SELECT access_type,revoked,account_mode,broker_brand,server_enc,credential_generation FROM managed_accounts WHERE pairing_id=?',(p['id'],)).fetchone()
        job=c.execute("SELECT status,worker_id FROM worker_jobs WHERE pairing_id=? ORDER BY created DESC LIMIT 1",(p['id'],)).fetchone()
    beat=fresh_heartbeat(p)
    if (not managed or managed['revoked'] or managed['access_type']!='MASTER' or managed['account_mode'] not in ('DEMO','LIVE')
        or not p['consumed'] or not beat or not beat.get('terminal_connected') or not beat.get('trade_allowed') or not terminal_identity_matches(p,managed['account_mode'],beat)
        or not job or job['status']!='READY'):
        raise HTTPException(409,'Destructive action requires an active MASTER profile and a fresh authenticated matching terminal and ready worker')
    return managed,beat

UNRESOLVED_TERMINAL_TRADE_STATES = frozenset({
    'SUBMISSION_INTENT',
    'SUBMISSION_UNKNOWN',
    'UNKNOWN_REQUIRES_RECONCILIATION',
    'ORDER_SUBMITTED',
    'PARTIALLY_FILLED',
    'FILLED',
    'FILLED_POSITION_NOT_FOUND',
    'POSITION_RECONCILED_SLTP_UNVERIFIED',
    'POSITION_SL_MODIFY_INTENT',
})


def require_terminal_trade_outcome_resolved(beat):
    signal=beat.get('last_signal')
    if not isinstance(signal,dict):
        return
    execution_state=str(signal.get('execution_state') or '').strip().upper()
    if execution_state in UNRESOLVED_TERMINAL_TRADE_STATES:
        raise HTTPException(409,detail={
            'code':'TERMINAL_TRADE_OUTCOME_UNRESOLVED',
            'message':f'MT5 reports {execution_state}; new orders and position edits are blocked until the existing broker result is reconciled',
        })


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


def resolve_start_news_symbols(config, beat):
    symbols=config.get('symbols',[]) if isinstance(config,dict) else []
    if isinstance(symbols,list) and any(str(item).strip().upper()=='AUTO' for item in symbols):
        runtime=beat.get('configuration') if isinstance(beat,dict) else {}
        actual=runtime.get('symbols') if isinstance(runtime,dict) else None
        resolved=[str(item) for item in actual if isinstance(item,str) and item.strip()] if isinstance(actual,list) else []
        return resolved if resolved else None
    return symbols if isinstance(symbols,list) else []


def demo_soak_account_digest(account):
    identity={key:str((account or {}).get(key) or '') for key in ('login','server','company','currency','account_mode')}
    raw=json.dumps(identity,sort_keys=True,separators=(',',':'))
    return hashlib.sha256(raw.encode('utf-8')).hexdigest()


def record_demo_soak_heartbeat(c,p,managed,job,worker,payload,received,day_utc):
    """Accumulate server-observed, identity-bound daily evidence from a fresh EA heartbeat."""
    configuration=payload.get('configuration') if isinstance(payload.get('configuration'),dict) else {}
    instance_id=configuration.get('ea_instance_id')
    started_at_ms=configuration.get('ea_started_at_ms')
    if (not isinstance(instance_id,str) or not 8<=len(instance_id)<=128 or
        any(ord(ch)<32 or ord(ch)==127 for ch in instance_id) or
        isinstance(started_at_ms,bool) or not isinstance(started_at_ms,(int,float)) or
        not math.isfinite(float(started_at_ms)) or started_at_ms<=0 or started_at_ms>received*1000+60000):
        return
    account_digest=demo_soak_account_digest(payload.get('account') or {})
    worker_id=str(job['worker_id'] or '')
    worker_generation=int(worker['generation'])
    credential_generation=str(managed['credential_generation'] or '')
    host_generation=HOST_GENERATION
    if not (worker_id and credential_generation and host_generation):
        return
    risk_clear=bool(live_risk_controls(payload).get('authorized'))
    news_clear=bool(heartbeat_news_clear(payload))
    snapshots_reconciled=all(isinstance(payload.get(key),list) for key in ('open_positions','active_orders','closed_deals'))
    existing=c.execute('SELECT * FROM demo_soak_daily WHERE pairing_id=? AND day_utc=?',(p['id'],day_utc)).fetchone()
    if existing:
        identity_matches=(existing['account_digest']==account_digest and existing['credential_generation']==credential_generation and
            existing['worker_id']==worker_id and existing['worker_generation']==worker_generation and existing['host_generation']==host_generation)
        if not identity_matches or existing['invalid']:
            c.execute('UPDATE demo_soak_daily SET invalid=1,last_seen=? WHERE pairing_id=? AND day_utc=?',(received,p['id'],day_utc))
            return
        try:
            instance_ids=json.loads(existing['ea_instance_ids'])
            if not isinstance(instance_ids,list) or not all(isinstance(value,str) for value in instance_ids):
                raise ValueError('invalid EA instance list')
        except (TypeError,ValueError,json.JSONDecodeError):
            c.execute('UPDATE demo_soak_daily SET invalid=1,last_seen=? WHERE pairing_id=? AND day_utc=?',(received,p['id'],day_utc))
            return
        if instance_id not in instance_ids:
            instance_ids.append(instance_id)
        c.execute('''UPDATE demo_soak_daily SET last_seen=?,heartbeat_count=heartbeat_count+1,
                     risk_clear_count=risk_clear_count+?,news_clear_count=news_clear_count+?,
                     reconciliation_count=reconciliation_count+?,ea_instance_ids=?
                     WHERE pairing_id=? AND day_utc=?''',
                  (received,int(risk_clear),int(news_clear),int(snapshots_reconciled),
                   json.dumps(instance_ids,separators=(',',':')),p['id'],day_utc))
    else:
        c.execute('''INSERT INTO demo_soak_daily(
                     pairing_id,day_utc,first_seen,last_seen,heartbeat_count,risk_clear_count,
                     news_clear_count,reconciliation_count,ea_instance_ids,account_digest,
                     credential_generation,worker_id,worker_generation,host_generation,invalid
                     ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)''',
                  (p['id'],day_utc,received,received,1,int(risk_clear),int(news_clear),
                   int(snapshots_reconciled),json.dumps([instance_id],separators=(',',':')),
                   account_digest,credential_generation,worker_id,worker_generation,host_generation))


@app.post('/v1/app/login',status_code=202)
@app.post('/v1/app/managed-account',status_code=202)
def managed_account(x:ManagedAccount, authorization:str|None=Header(default=None),
                    x_management_identity:str|None=Header(default=None)):
    require_durable_execution()
    # In Linux host mode, accept enrolment only after the root host agent has
    # verified that isolated per-pairing Unix/Wine/X11 slots can be provisioned.
    # The password remains encrypted at rest and is withheld from the worker until
    # that particular pairing/job submits valid root-verified slot evidence.
    if LINUX_HOST_MODE:
        host=linux_host_report()
        isolation=linux_slot_isolation(host)
        if isolation.get('enabled') is not True or isolation.get('ready') is not True:
            raise HTTPException(503,detail={'code':'PER_USER_TERMINAL_ISOLATION_REQUIRED','message':'Root host agent has not verified isolated per-user terminal slot capacity'})
        # LIVE credentials may be paired on EPHEMERAL for account verification only.
        # Root-attested isolation is still mandatory; worker disables MT5 algo trading on EPHEMERAL.
        # LIVE authorization, START/RESUME and order routes remain persistent-host-only.
        if x.account_mode == 'LIVE':
            maximum=isolation.get('max_slots')
            active=isolation.get('active_slots',0)
            required_slots = 2 if HOST_PROFILE == 'PERSISTENT' else 1
            if (not isinstance(maximum,int) or isinstance(maximum,bool) or maximum < required_slots or
                    not isinstance(active,int) or isinstance(active,bool) or active >= maximum):
                code = 'LIVE_REQUIRES_TWO_ISOLATED_TERMINALS' if HOST_PROFILE == 'PERSISTENT' else 'LIVE_LOGIN_SLOT_CAPACITY_NOT_READY'
                message = ('A persistent host needs capacity for separate DEMO and LIVE MT5 terminals'
                           if HOST_PROFILE == 'PERSISTENT' else 'An ephemeral account-verification login needs one isolated MT5 terminal slot')
                raise HTTPException(409,detail={'code':code,'message':message,'active_slots':active if isinstance(active,int) and not isinstance(active,bool) else 0,'max_slots':maximum if isinstance(maximum,int) and not isinstance(maximum,bool) else 0})
    p=auth(authorization,'app')
    if not x_management_identity:
        raise HTTPException(401,detail={'code':'MANAGEMENT_IDENTITY_REQUIRED','message':'Re-authenticate to the DestinedFX management account before registering broker credentials'})
    identity=_management_identity_claims(x_management_identity)
    if (not p['owner_subject'] or not p['owner_tenant'] or
        identity['sub']!=p['owner_subject'] or identity['tenant_id']!=p['owner_tenant']):
        raise HTTPException(403,detail={'code':'MANAGEMENT_IDENTITY_MISMATCH','message':'The current management user does not own this account pairing'})
    fernet,worker_secret=managed_config()
    if not x.consent or x.access_type not in ('MASTER','INVESTOR') or x.account_mode not in (None,'DEMO','LIVE') or not x.login.isdigit() or not 1 <= len(x.login) <= 20 or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}',x.server) or any(ord(ch)<32 or ord(ch)==127 for ch in x.password): raise HTTPException(422,'Consent, account number, exact server name, account mode, and valid access_type are required')
    brand=x.broker_brand.strip().upper()
    if brand not in ('FBS','EXNESS','HFM','DERIV'): raise HTTPException(422,'A verified supported broker brand is required; UNKNOWN profiles must be revoked and re-enrolled')
    evidence=x.cost_evidence.strip()
    if any(ord(ch)<32 or ord(ch)==127 for ch in evidence): raise HTTPException(422,'Cost evidence reference must not contain control characters')
    if x.cost_model not in ('OBSERVED_ROUND_TURN','CONFIGURED_ROUND_TURN','CONFIGURED_PER_SIDE','ZERO_COMMISSION_SPREAD_ONLY'): raise HTTPException(422,'Invalid transaction cost model')
    if x.cost_model=='OBSERVED_ROUND_TURN':
        if x.commission_per_lot!=0 or evidence: raise HTTPException(422,'Observed round-turn mode must not include a configured commission or evidence reference')
    elif x.cost_model=='ZERO_COMMISSION_SPREAD_ONLY':
        if x.commission_per_lot!=0 or len(evidence)<8: raise HTTPException(422,'Zero-commission mode requires a zero commission value and an official broker pricing evidence reference')
    elif x.commission_per_lot<=0 or len(evidence)<8:
        raise HTTPException(422,'Configured commission models require a finite positive account-currency amount per standard lot and a broker evidence reference')
    attestation=None
    isolation_pending=False
    if x.isolation_attestation_id:
        with tx() as c:
            attestation=c.execute('SELECT * FROM worker_attestations WHERE id=? AND pairing_id=? AND used=0',
                                  (x.isolation_attestation_id,p['id'])).fetchone()
        if not attestation:
            raise HTTPException(503,detail={'code':'PER_USER_TERMINAL_ISOLATION_REQUIRED','message':'Isolation attestation is missing, consumed or not bound to this pairing'})
        if attestation['expires_at'] <= time.time() or attestation['issued_at'] > time.time()+30:
            raise HTTPException(503,detail={'code':'PER_USER_TERMINAL_ISOLATION_REQUIRED','message':'Isolation attestation is stale or has an invalid issue time'})
        if any((
            attestation['broker_brand'] != brand,
            attestation['login'] != x.login,
            attestation['server'] != x.server,
            attestation['account_mode'] != (x.account_mode or ''),
        )):
            raise HTTPException(503,detail={'code':'PER_USER_TERMINAL_ISOLATION_REQUIRED','message':'Isolation attestation does not match the selected account'})
    elif not _isolation_evidence_verified():
        isolation_pending=True
    with tx(True) as c:
        active_job=c.execute(
            """SELECT id,status FROM worker_jobs
               WHERE pairing_id=? AND kind='PROVISION'
                 AND status IN ('QUEUED','AWAITING_ATTESTATION','LEASED','PROVISIONING','AUTHENTICATING','READY')
               ORDER BY created DESC LIMIT 1""",
            (p['id'],)
        ).fetchone()
        if active_job:
            raise HTTPException(409,detail={
                'code':'PAIRING_TERMINAL_ALREADY_ACTIVE',
                'message':'This broker pairing already has a queued, provisioning or active terminal. Revoke and confirm cleanup before registering it again.',
            })
        # A pairing may transition from DEMO to LIVE only after the DEMO acceptance
        # is recorded; the active managed profile remains the single current execution
        # profile while the acceptance/link preserve the prior profile identity.
        # Re-enrolling a profile invalidates its prior acceptance, cross-profile links and LIVE challenges.
        c.execute("""UPDATE pairings SET live_authorized_until=NULL,live_authorized_login=NULL,live_authorized_server=NULL
                     WHERE id=? OR id IN (SELECT pairing_id FROM live_profile_links WHERE demo_pairing_id=?)""",(p['id'],p['id']))
        c.execute('UPDATE live_profile_links SET revoked=1 WHERE pairing_id=? OR demo_pairing_id=?',(p['id'],p['id']))
        c.execute('UPDATE demo_acceptances SET revoked=1 WHERE pairing_id=?',(p['id'],))
        c.execute('UPDATE live_challenges SET used=1 WHERE pairing_id=? OR demo_pairing_id=?',(p['id'],p['id']))
        pairing_code=secrets.token_urlsafe(18); job_id=str(uuid.uuid4()); t=time.time()
        values=(fernet.encrypt(x.login.encode()).decode(),fernet.encrypt(x.server.encode()).decode(),fernet.encrypt(x.password.encode()).decode(),x.access_type,x.account_mode or 'UNVERIFIED',brand,x.cost_model,float(x.commission_per_lot),evidence,x.credential_generation,p['id'])
        if c.execute('SELECT 1 FROM managed_accounts WHERE pairing_id=?',(p['id'],)).fetchone():
            c.execute('''UPDATE managed_accounts SET login_enc=?,server_enc=?,password_enc=?,access_type=?,
                         revoked=0,account_mode=?,broker_brand=?,cost_model=?,commission_per_lot=?,
                         cost_evidence=?,credential_generation=? WHERE pairing_id=?''',values)
        else:
            c.execute('INSERT INTO managed_accounts(pairing_id,login_enc,server_enc,password_enc,access_type,revoked,account_mode,broker_brand,cost_model,commission_per_lot,cost_evidence,credential_generation) VALUES(?,?,?,?,?,0,?,?,?,?,?,?)',(p['id'],*values[:-1]))
        c.execute('UPDATE pairings SET code_hash=?,expires=?,consumed=0,terminal_hash=NULL,terminal_id=NULL,instance_id=NULL,account=NULL,heartbeat=NULL,live_authorized_until=NULL,live_authorized_login=NULL,live_authorized_server=NULL WHERE id=?',(digest(pairing_code),t+CONNECT_JOB_TTL_SECONDS,p['id']))
        c.execute("INSERT INTO worker_jobs(id,pairing_id,kind,status,created,requested_account_mode) VALUES(?,?,'PROVISION',?,?,?)",
                  (job_id, p['id'], 'QUEUED' if attestation or _isolation_evidence_verified() else 'AWAITING_ATTESTATION', t, x.account_mode))
        if attestation:
            updated=c.execute("UPDATE worker_attestations SET used=1,job_id=? WHERE id=? AND used=0",
                              (job_id,attestation['id']))
            if updated.rowcount != 1:
                raise HTTPException(409,'Isolation attestation was consumed concurrently')
    return {'job_id':job_id,'status':'AWAITING_WORKER_ATTESTATION' if isolation_pending else 'QUEUED'}


@app.post('/v1/operator/workers',status_code=201)
def provision_worker(x:WorkerProvision, authorization:str|None=Header(default=None)):
    require_durable_execution()
    operator_auth(authorization)
    worker_token=f'{x.worker_id}.{secrets.token_urlsafe(48)}'
    now_epoch=time.time()
    with tx(True) as c:
        if c.execute('SELECT 1 FROM worker_identities WHERE worker_id=?',(x.worker_id,)).fetchone():
            raise HTTPException(409,detail={'code':'WORKER_EXISTS','message':'Worker identity already exists'})
        c.execute('INSERT INTO worker_identities(worker_id,token_hash,created,expires_at) VALUES(?,?,?,?)',
                  (x.worker_id,digest(worker_token),now_epoch,now_epoch+x.expires_in_seconds))
    return {'worker_id':x.worker_id,'credential':worker_token,'expires_at':now_epoch+x.expires_in_seconds}

@app.post('/v1/operator/workers/{worker_id}/rotate')
def rotate_worker(worker_id:str, x:WorkerProvision|None=None, authorization:str|None=Header(default=None)):
    require_durable_execution()
    operator_auth(authorization)
    expires_in=x.expires_in_seconds if x else 86400
    worker_token=f'{worker_id}.{secrets.token_urlsafe(48)}'
    now_epoch=time.time()
    with tx(True) as c:
        row=c.execute('SELECT generation FROM worker_identities WHERE worker_id=?',(worker_id,)).fetchone()
        if not row: raise HTTPException(404,'Worker identity not found')
        c.execute('UPDATE worker_identities SET token_hash=?,status="ACTIVE",expires_at=?,last_seen=NULL,generation=? WHERE worker_id=?',
                  (digest(worker_token),now_epoch+expires_in,row['generation']+1,worker_id))
    return {'worker_id':worker_id,'credential':worker_token,'expires_at':now_epoch+expires_in}

@app.post('/v1/operator/workers/{worker_id}/revoke')
def revoke_worker(worker_id:str, authorization:str|None=Header(default=None)):
    require_durable_execution()
    operator_auth(authorization)
    with tx(True) as c:
        updated=c.execute('UPDATE worker_identities SET status="REVOKED" WHERE worker_id=?',(worker_id,))
        if updated.rowcount != 1: raise HTTPException(404,'Worker identity not found')
        c.execute("UPDATE worker_jobs SET status='REVOKED',lease_until=NULL WHERE worker_id=? AND status IN ('LEASED','PROVISIONING','AUTHENTICATING','READY')",(worker_id,))
    return {'worker_id':worker_id,'status':'REVOKED'}

@app.get('/v1/operator/workers/{worker_id}')
def worker_identity_status(worker_id:str, authorization:str|None=Header(default=None)):
    require_durable_execution()
    operator_auth(authorization)
    with tx(True) as c:
        row=c.execute('SELECT worker_id,status,created,expires_at,last_seen,generation FROM worker_identities WHERE worker_id=?',
                      (worker_id,)).fetchone()
    if not row:
        raise HTTPException(404,'Worker identity not found')
    return dict(row)

@app.get('/v1/worker/identity')
def worker_identity(authorization:str|None=Header(default=None)):
    require_durable_execution()
    worker=worker_auth(authorization)
    if worker['worker_id'] is None:
        raise HTTPException(403, detail={'code':'NAMED_WORKER_REQUIRED','message':'A named worker credential is required'})
    with tx(True) as c:
        row=c.execute('SELECT worker_id,status,expires_at,generation FROM worker_identities WHERE worker_id=?',
                      (worker['worker_id'],)).fetchone()
    if not row or row['status'] != 'ACTIVE' or row['expires_at'] <= time.time():
        raise HTTPException(401,'Unauthorized')
    return dict(row)

@app.post('/v1/worker/attestations',status_code=201)
def worker_attestation(x:WorkerAttestation, authorization:str|None=Header(default=None)):
    require_durable_execution()
    worker=worker_auth(authorization)
    require_worker_identity(worker,x.worker_id)
    worker_secret=worker['token']
    evidence=x.model_dump()
    if not hmac.compare_digest(x.signature,_attestation_signature(worker_secret,evidence)):
        raise HTTPException(401,'Invalid worker attestation signature')
    now_epoch=time.time()
    if x.issued_at > now_epoch + 30 or x.expires_at <= now_epoch or x.expires_at > now_epoch + 900:
        raise HTTPException(422,'Worker attestation expiry is invalid')
    if x.host_generation != HOST_GENERATION:
        raise HTTPException(409,detail={'code':'HOST_GENERATION_MISMATCH','message':'Worker attestation belongs to a different host generation'})
    with tx(True) as c:
        if not c.execute('SELECT 1 FROM pairings WHERE id=? AND expires>?',(x.pairing_id,now_epoch)).fetchone():
            raise HTTPException(404,'Pairing not found or expired')
        if c.execute('SELECT 1 FROM worker_attestations WHERE nonce=?',(x.nonce,)).fetchone():
            raise HTTPException(409,'Worker attestation nonce was already used')
        c.execute('''INSERT INTO worker_attestations
            (id,pairing_id,worker_id,slot_id,linux_uid,wine_prefix,terminal_path,process_identity,ea_identity,
             broker_brand,login,server,account_mode,host_generation,issued_at,expires_at,nonce,signature)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',
            (str(uuid.uuid4()),x.pairing_id,x.worker_id,x.slot_id,x.linux_uid,x.wine_prefix,x.terminal_path,
             x.process_identity,x.ea_identity,x.broker_brand.upper(),x.login,x.server,x.account_mode,
             x.host_generation,x.issued_at,x.expires_at,x.nonce,x.signature))
        row=c.execute('SELECT id FROM worker_attestations WHERE nonce=?',(x.nonce,)).fetchone()
    return {'attestation_id':row['id'],'status':'AVAILABLE','expires_at':x.expires_at}

@app.post('/v1/worker/attestations/finalize')
def worker_attestation_finalize(x:WorkerAttestationFinalize, authorization:str|None=Header(default=None)):
    require_durable_execution()
    worker=worker_auth(authorization)
    require_worker_identity(worker,x.worker_id)
    evidence=x.model_dump(exclude_unset=True)
    if not hmac.compare_digest(x.signature,_attestation_signature(worker['token'],evidence)):
        raise HTTPException(401,'Invalid worker attestation finalization signature')
    with tx() as c:
        row=c.execute('SELECT * FROM worker_attestations WHERE id=? AND worker_id=? AND used=1',
                      (x.attestation_id,x.worker_id)).fetchone()
    if not row:
        raise HTTPException(404,'Worker attestation not found')
    if row['expires_at'] <= time.time():
        raise HTTPException(409,'Worker attestation expired')
    if LINUX_HOST_MODE:
        if x.pid is None or x.process_start_ticks is None or x.terminal_path != row['terminal_path']:
            raise HTTPException(409,'Finalized terminal does not match its reserved Linux slot')
        verify_linux_job_process(row,x.pid,x.process_start_ticks)
    with tx(True) as c:
        updated=c.execute('''UPDATE worker_attestations
                             SET terminal_path=?,process_identity=?,ea_identity=?,
                                 process_pid=?,process_start_ticks=?,finalized=1
                             WHERE id=? AND worker_id=? AND used=1 AND finalized=0''',
                          (x.terminal_path,x.process_identity,x.ea_identity,x.pid,x.process_start_ticks,
                           x.attestation_id,x.worker_id))
        if updated.rowcount != 1:
            raise HTTPException(409,'Worker attestation was already finalized')
    return {'attestation_id':x.attestation_id,'status':'FINALIZED'}


@app.post('/v1/worker/jobs/claim')
def worker_claim(x:WorkerClaim, authorization:str|None=Header(default=None)):
    require_durable_execution()
    worker=worker_auth(authorization)
    require_worker_identity(worker,x.worker_id)
    fernet,_=managed_config(); worker_secret=worker['token']; t=time.time()
    draining=host_lifecycle_state(t)['host_shutdown_pending']
    if draining:
        queue_host_expiry_safety_stops(t)
    with tx(True) as c:
        leased_provisions=c.execute(
            "SELECT COUNT(*) FROM worker_jobs WHERE worker_id=? AND kind='PROVISION' AND status IN ('LEASED','PROVISIONING','AUTHENTICATING') AND lease_until>=?",
            (x.worker_id,t)
        ).fetchone()[0]
        shutdown_prepared=_host_stop_prepared(c)
        # Keep the worker draining queued revocations while a host shutdown is
        # prepared, but never lease a new DEMO provisioning job.
        at_capacity = shutdown_prepared or x.pause_provisioning or x.active_terminals >= x.max_terminals or leased_provisions >= x.max_terminals
        if at_capacity or draining:
            r=c.execute("SELECT * FROM worker_jobs WHERE kind='REVOKE' AND (status='QUEUED' OR (status IN ('LEASED','PROVISIONING','AUTHENTICATING') AND lease_until<?)) ORDER BY created LIMIT 1",(t,)).fetchone()
        else:
            r=c.execute("SELECT * FROM worker_jobs WHERE status IN ('QUEUED','AWAITING_ATTESTATION') OR (status IN ('LEASED','PROVISIONING','AUTHENTICATING') AND lease_until<?) ORDER BY created LIMIT 1",(t,)).fetchone()
        if not r: return {'job':None}
        if r['kind']=='REVOKE':
            c.execute("UPDATE worker_jobs SET status='LEASED',worker_id=?,lease_until=?,lease_started=? WHERE id=?",(x.worker_id,t+120,t,r['id']))
            return {'job_id':r['id'],'kind':'REVOKE','pairing_id':r['pairing_id'],'worker_id':x.worker_id}
        p=c.execute('SELECT * FROM pairings WHERE id=?',(r['pairing_id'],)).fetchone()
        m=c.execute('SELECT * FROM managed_accounts WHERE pairing_id=? AND revoked=0',(r['pairing_id'],)).fetchone()
        if not p or not m:
            c.execute("UPDATE worker_jobs SET status='FAILED',error_code='ACCOUNT_REVOKED',error_message='Account was revoked before provisioning' WHERE id=?",(r['id'],))
            return {'job':None}
        if r['status']=='AWAITING_ATTESTATION' and not x.attestation and not _isolation_evidence_verified():
            account_identity={'account_mode':r['requested_account_mode'] or 'UNVERIFIED','broker_brand':m['broker_brand']}
            account_identity['login']=fernet.decrypt(m['login_enc'].encode()).decode()
            account_identity['server']=fernet.decrypt(m['server_enc'].encode()).decode()
            return {'status':'ATTESTATION_REQUIRED','job_id':r['id'],'pairing_id':r['pairing_id'],
                    'worker_id':x.worker_id,'host_generation':HOST_GENERATION,'account_identity':account_identity}
        if x.attestation and not _isolation_evidence_verified():
            try:
                submitted=WorkerAttestation.model_validate(x.attestation)
            except ValidationError:
                raise HTTPException(422,'Invalid worker attestation')
            evidence=submitted.model_dump(exclude_unset=True)
            if not hmac.compare_digest(submitted.signature,_attestation_signature(worker_secret,evidence)):
                raise HTTPException(401,'Invalid worker attestation signature')
            if submitted.pairing_id != r['pairing_id'] or submitted.worker_id != x.worker_id:
                raise HTTPException(409,'Worker attestation is not bound to this job and worker')
            if submitted.host_generation != HOST_GENERATION or submitted.expires_at <= t or submitted.issued_at > t+30:
                raise HTTPException(409,'Worker attestation is stale or belongs to another host generation')
            expected_login=fernet.decrypt(m['login_enc'].encode()).decode()
            expected_server=fernet.decrypt(m['server_enc'].encode()).decode()
            if any((submitted.login != expected_login,submitted.server != expected_server,
                    submitted.broker_brand != m['broker_brand'],submitted.account_mode != (r['requested_account_mode'] or ''))):
                raise HTTPException(409,'Worker attestation does not match the registered account')
            if LINUX_HOST_MODE:
                required=(submitted.job_id,submitted.linux_user,submitted.slot_root,submitted.terminal_home,
                          submitted.xauthority,submitted.display,submitted.xvfb_pid,submitted.xvfb_start_ticks)
                if submitted.job_id != r['id'] or any(value is None for value in required):
                    raise HTTPException(409,'Linux slot reservation is incomplete or not bound to this job')
                if not re.fullmatch(r'dfxslot_[0-9a-f]{16}',submitted.linux_user or ''):
                    raise HTTPException(422,'Linux slot user identity is invalid')
                occupied=c.execute(
                    """SELECT 1 FROM worker_attestations a
                       JOIN worker_jobs j ON j.id=a.job_id
                       WHERE a.host_generation=? AND a.pairing_id<>?
                         AND (a.linux_uid=? OR a.linux_user=? OR a.wine_prefix=? OR a.terminal_path=? OR a.display=?)
                         AND j.status IN ('LEASED','PROVISIONING','AUTHENTICATING','READY')
                       LIMIT 1""",
                    (HOST_GENERATION,submitted.pairing_id,submitted.linux_uid,submitted.linux_user,
                     submitted.wine_prefix,submitted.terminal_path,submitted.display),
                ).fetchone()
                if occupied:
                    raise HTTPException(409,'Linux UID, display or terminal path is already assigned to another active pairing')
                host_agent_slot_request('/internal/slot/reservation',submitted.model_dump(exclude_unset=True))
            attestation_id=str(uuid.uuid4())
            c.execute('''INSERT INTO worker_attestations
                (id,pairing_id,worker_id,slot_id,linux_uid,wine_prefix,terminal_path,process_identity,ea_identity,
                 broker_brand,login,server,account_mode,host_generation,issued_at,expires_at,nonce,signature,used,job_id,
                 linux_user,slot_root,terminal_home,xauthority,display,xvfb_pid,xvfb_start_ticks)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?,?,?,?,?,?,?)''',
                (attestation_id,submitted.pairing_id,submitted.worker_id,submitted.slot_id,submitted.linux_uid,
                 submitted.wine_prefix,submitted.terminal_path,submitted.process_identity,submitted.ea_identity,
                 submitted.broker_brand,submitted.login,submitted.server,submitted.account_mode,
                 submitted.host_generation,submitted.issued_at,submitted.expires_at,submitted.nonce,
                 submitted.signature,r['id'],submitted.linux_user,submitted.slot_root,submitted.terminal_home,
                 submitted.xauthority,submitted.display,submitted.xvfb_pid,submitted.xvfb_start_ticks))
        c.execute("UPDATE worker_jobs SET status='LEASED',worker_id=?,lease_until=?,lease_started=? WHERE id=?",(x.worker_id,t+120,t,r['id']))
        attestation=c.execute('SELECT * FROM worker_attestations WHERE job_id=? AND worker_id=? AND used=1',(r['id'],x.worker_id)).fetchone()
        if (not attestation or attestation['expires_at'] < t) and not _isolation_evidence_verified():
            c.execute("UPDATE worker_jobs SET status='FAILED',error_code='PER_USER_TERMINAL_ISOLATION_REQUIRED',error_message='Fresh slot isolation evidence is required' WHERE id=?",(r['id'],))
            return {'job':None}
        account={k:fernet.decrypt(m[k+'_enc'].encode()).decode() for k in ('login','server','password')}
        account['access_type']=m['access_type']; account['account_mode']=m['account_mode']; account['broker_brand']=m['broker_brand']
        account['cost_model']=m['cost_model']; account['commission_per_lot']=float(m['commission_per_lot']); account['cost_evidence']=m['cost_evidence']
        try:
            claimed_account=json.loads(p['account']) if p['account'] else {}
            for key in ('company','currency'):
                if isinstance(claimed_account.get(key),str) and claimed_account[key]: account[key]=claimed_account[key]
        except (TypeError,ValueError):
            pass
        code=secrets.token_urlsafe(18)
        c.execute('UPDATE pairings SET code_hash=?,expires=? WHERE id=?',(digest(code),t+CONNECT_JOB_TTL_SECONDS,r['pairing_id']))
        bridge_url=os.getenv('BRIDGE_BASE_URL','').strip() or os.getenv('RENDER_EXTERNAL_URL','').strip()
        return {'job_id':r['id'],'kind':'PROVISION','worker_id':x.worker_id,'pairing_id':r['pairing_id'],
                'pairing_code':code,'bridge_url':bridge_url,'account':account,
                'isolation_evidence':dict(attestation) if attestation else None}


@app.patch('/v1/worker/jobs/{job_id}')
def worker_update(job_id:str,x:WorkerUpdate,authorization:str|None=Header(default=None)):
    require_durable_execution()
    worker=worker_auth(authorization)
    require_worker_identity(worker,x.worker_id)
    if x.status not in ('PROVISIONING','AUTHENTICATING','READY','FAILED','REVOKED'): raise HTTPException(422,'Invalid provisioning status')
    code=(x.error_code or '')[:64]
    safe_messages={'terminal_auth_failed':'Terminal authentication failed','bridge_unavailable':'Bridge unavailable','terminal_launch_failed':'Terminal launch failed','WRONG_CREDENTIALS':'Terminal rejected the supplied credentials','INVALID_ACCOUNT':'The broker rejected the account number','ACCOUNT_OR_SERVER_MISMATCH':'Terminal account or server did not match the requested identity','NO_CONNECTION':'Terminal could not connect to the broker','SERVER_NOT_FOUND':'Terminal could not find the requested server','ACCOUNT_DISABLED':'Broker reported the account as disabled','TERMINAL_VERSION_UNSUPPORTED':'The terminal or broker version is unsupported','LOGIN_FAILED_UNCLASSIFIED':'Terminal login failed for an unclassified reason; review current terminal logs','TERMINAL_LAUNCH_FAILED':'Terminal launch or service control failed','TERMINAL_OWNERSHIP_UNVERIFIED':'Terminal could not be stopped; operator review is required','REVOKE_PROCESS_NOT_TERMINATED':'Terminal could not be stopped during account revocation','TERMINAL_BUSY':'The terminal already has an active account job','TERMINAL_OFFLINE':'Terminal is offline','EA_OFFLINE':'DestinedFX EA is offline','ALGO_TRADING_DISABLED':'Algorithmic trading is disabled in the terminal','WEBREQUEST_NOT_ALLOWED':'Terminal WebRequest allowlist is not configured','REVOKE_PROCESS_ID_MISMATCH':'Terminal process identity could not be verified','REVOKE_PROCESS_ID_MISSING':'Terminal process identity is missing','REVOKE_MARKER_NOT_FOUND':'Terminal ownership marker was not found; operator review is required','REVOKE_CLEANUP_FAILED':'Terminal cleanup could not be verified','ACCOUNT_REVOKED':'Account was revoked before provisioning','ACCOUNT_MODE_UNVERIFIED':'The terminal could not verify whether this account is DEMO or LIVE'}
    journal_codes={'WRONG_CREDENTIALS','INVALID_ACCOUNT','NO_CONNECTION','SERVER_NOT_FOUND','ACCOUNT_DISABLED','TERMINAL_VERSION_UNSUPPORTED','LOGIN_FAILED_UNCLASSIFIED'}
    supplied_reason=re.sub(r'\\s+',' ',''.join(ch if ch.isprintable() else ' ' for ch in (x.error_message or ''))).strip()
    message=supplied_reason[:160] if code in journal_codes and supplied_reason else safe_messages.get(code,'')
    with tx(True) as c:
        r=c.execute('SELECT * FROM worker_jobs WHERE id=?',(job_id,)).fetchone()
        if not r: raise HTTPException(404,'Job not found')
        if r['worker_id']!=x.worker_id: raise HTTPException(403,'Worker does not own this job lease')
        if r['lease_until'] is None or r['lease_until']<time.time(): raise HTTPException(409,'Worker job lease expired; reclaim the job before continuing')
        if r['kind']=='REVOKE' and x.status not in ('REVOKED','FAILED'): raise HTTPException(422,'Revocation job must be acknowledged as REVOKED or FAILED')
        if r['kind']!='REVOKE' and x.status=='REVOKED': raise HTTPException(422,'Only a revocation job can be marked REVOKED')
        # Linux isolated-slot workers may receive FAILED from a terminal observer
        # and then attach the exact per-slot journal reason. Permit only an
        # owner-worker, same-state FAILED update while its lease is still valid.
        if r['status']=='FAILED' and x.status=='FAILED':
            if code and code in journal_codes and message:
                c.execute('UPDATE worker_jobs SET error_code=?,error_message=? WHERE id=?',
                          (code, message[:160], job_id))
            return {'updated':True}
        if x.status=='READY' and not _isolation_evidence_verified():
            evidence=c.execute('SELECT * FROM worker_attestations WHERE job_id=? AND worker_id=? AND used=1',(job_id,x.worker_id)).fetchone()
            if not evidence or not evidence['finalized']:
                raise HTTPException(409,'Finalized worker isolation evidence is required before READY')
            if LINUX_HOST_MODE:
                verify_linux_job_process(evidence)
        if r['status']=='READY' and x.status in ('PROVISIONING','AUTHENTICATING'): return {'updated':True}
        if r['status'] not in ('LEASED','PROVISIONING','AUTHENTICATING'): raise HTTPException(409,'Job is not active')
        lease=time.time()+120 if x.status in ('PROVISIONING','AUTHENTICATING','LEASED') else r['lease_until']
        c.execute('UPDATE worker_jobs SET status=?,lease_until=?,error_code=?,error_message=? WHERE id=?',(x.status,lease,code or None,message or None,job_id))
        if r['kind']=='REVOKE' and x.status=='REVOKED':
            # Release the old provision job only after the worker confirms that
            # this pairing's isolated terminal and Unix identity were removed.
            c.execute(
                """UPDATE worker_jobs SET status='REVOKED',lease_until=NULL
                   WHERE pairing_id=? AND kind='PROVISION'
                     AND status IN ('QUEUED','AWAITING_ATTESTATION','LEASED','PROVISIONING','AUTHENTICATING','READY')""",
                (r['pairing_id'],)
            )
    return {'updated':True}


@app.get('/v1/worker/jobs/{job_id}/status')
def worker_job_status(job_id:str,worker_id:str=Query(min_length=1,max_length=128),authorization:str|None=Header(default=None)):
    require_durable_execution()
    worker=worker_auth(authorization)
    require_worker_identity(worker,worker_id)
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
            if valid and not _isolation_evidence_verified():
                evidence=c.execute('SELECT * FROM worker_attestations WHERE job_id=? AND worker_id=? AND used=1 AND finalized=1',(job_id,worker_id)).fetchone()
                try:
                    if not evidence: raise HTTPException(409,'Finalized isolation evidence is unavailable')
                    verify_linux_job_process(evidence)
                except HTTPException:
                    valid=False
            if valid:
                c.execute('UPDATE worker_jobs SET lease_until=?,stale_since=NULL,recovery_deadline=NULL,error_code=NULL,error_message=NULL WHERE id=?',(t+120,job_id))
            else:
                c.execute("UPDATE worker_jobs SET status='AUTHENTICATING',lease_until=?,stale_since=COALESCE(stale_since,?),recovery_deadline=COALESCE(recovery_deadline,?),error_code='TERMINAL_HEARTBEAT_STALE',error_message='Fresh authenticated isolated terminal heartbeat is required' WHERE id=?",(t+120,t,t+RECOVERY_GRACE,job_id))
            job=c.execute('SELECT * FROM worker_jobs WHERE id=?',(job_id,)).fetchone()
        if job['kind']=='PROVISION' and job['status'] in ('PROVISIONING','AUTHENTICATING') and managed and not managed['revoked']:
            beat=fresh_heartbeat(pairing)
            valid=bool(execution_state_eligible(beat) and beat.get('terminal_connected') and managed['account_mode'] in ('DEMO','LIVE') and terminal_identity_matches(pairing,managed['account_mode'],beat) and (managed['access_type']=='INVESTOR' or beat.get('trade_allowed')))
            if valid and not _isolation_evidence_verified():
                evidence=c.execute('SELECT * FROM worker_attestations WHERE job_id=? AND worker_id=? AND used=1 AND finalized=1',(job_id,worker_id)).fetchone()
                try:
                    if not evidence: raise HTTPException(409,'Finalized isolation evidence is unavailable')
                    verify_linux_job_process(evidence)
                except HTTPException:
                    valid=False
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


@app.get('/health/live')
def live_health(): return {'status':'ok'}
@app.get('/health')
def health():
    return {
        'status':'ok','host_profile':HOST_PROFILE,'public_exposure':PUBLIC_EXPOSURE,
        # The mobile app compares this non-secret value with the URL host before saving it.
        'expected_forwarded_host':EXPECTED_PUBLIC_HOST if PUBLIC_EXPOSURE else '',
        'idle_timeout_seconds':HOST_IDLE_TIMEOUT_SECONDS,
        'auto_sleep_idle_seconds':max(120,min(86400,int(os.getenv('DESTINEDFX_AUTO_SLEEP_IDLE_SECONDS','120')))),
        'host_started_at_epoch_seconds':HOST_STARTED_AT_EPOCH_SECONDS,
        **host_lifecycle_state(),
    }
@app.get('/health/config')
def configuration_health():
    require_durable_execution()
    managed_config()
    try:
        with tx(True) as c:
            c.execute('SELECT 1').fetchone()
    except Exception as error:
        raise HTTPException(503, detail={'code':'DATABASE_UNAVAILABLE','message':'Bridge storage is unavailable'}) from error
    # A Linux deployment may be used only after the root agent has independently
    # attested per-pairing OS identity, Wine-prefix and terminal-process isolation.
    # Do not report a blanket BLOCKED value when that evidence is healthy, and do
    # not report VERIFIED merely because Linux-host mode is enabled.
    host = None
    if LINUX_HOST_MODE:
        # Configuration health remains distinct from execution readiness. Report
        # BLOCKED when the root agent/isolated slots are missing; /health/ready and
        # account registration independently refuse to proceed until attested.
        try:
            host = linux_host_report()
        except HTTPException:
            host = None
    isolation = linux_slot_isolation(host)
    backup = (host or {}).get('backup') if isinstance(host, dict) else None
    if not isinstance(backup, dict):
        backup = {'status': 'NOT_VERIFIED'}
    isolation_verified = bool(
        LINUX_HOST_MODE and isolation.get('enabled') and isolation.get('ready')
    )
    isolation_status = (
        'VERIFIED' if isolation_verified
        else 'BLOCKED' if LINUX_HOST_MODE
        else 'WORKER_ATTESTATION_REQUIRED'
    )
    return {
        'status': 'configured',
        'database': 'ok',
        'managed_execution': 'configured',
        'host_profile': HOST_PROFILE,
        'management_step_up_key_fingerprint_sha256': management_step_up_key_fingerprint(),
        'management_step_up_issuer': MANAGEMENT_STEP_UP_ISSUER if management_step_up_key_fingerprint() else '',
        'management_step_up_audience': MANAGEMENT_STEP_UP_AUDIENCE if management_step_up_key_fingerprint() else '',
        'news_provider_configured': news_provider_configuration()['configured'],
        'news_provider_kind': news_provider_configuration()['kind'],
        'per_user_terminal_isolation': isolation_status,
        'multi_user_terminal_isolation': 'VERIFIED' if isolation_verified else 'NOT_VERIFIED',
        'linux_host_mode': LINUX_HOST_MODE,
        'isolated_slots': int(isolation.get('active_slots', 0)) if isolation_verified else 0,
        'max_isolated_slots': int(isolation.get('max_slots', 0)) if isolation_verified else 0,
        'backup': backup,
        **host_lifecycle_state(),
    }
@app.get('/health/ready')
def readiness():
    """Report infrastructure and isolated-slot capacity without claiming an account is logged in."""
    if not durable_execution_enabled():
        raise HTTPException(503, detail={'code':'NON_DURABLE_STORAGE','message':'Execution readiness is disabled because this deployment uses ephemeral storage'})
    try:
        managed_config()
        with tx() as c: c.execute('SELECT 1').fetchone()
        host=linux_host_report()
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(503, detail={'code':'DATABASE_UNAVAILABLE','message':'Bridge storage is unavailable'})
    if LINUX_HOST_MODE:
        isolation=linux_slot_isolation(host)
        if not isolation.get('enabled') or not isolation.get('ready'):
            raise HTTPException(503,detail={'code':'PER_USER_TERMINAL_ISOLATION_REQUIRED','message':'Root host agent has not verified isolated per-user MT5 slot capacity'})
        return {'status':'ready','database':'ok','managed_execution':'configured',
                'per_user_terminal_isolation':'VERIFIED','isolated_slots':int(isolation.get('active_slots',0)),
                'max_isolated_slots':int(isolation.get('max_slots',1)),
                'host_profile':HOST_PROFILE,'idle_timeout_seconds':HOST_IDLE_TIMEOUT_SECONDS,
                'host_started_at_epoch_seconds':HOST_STARTED_AT_EPOCH_SECONDS,**host_lifecycle_state()}
    if not verified_terminal_pairing_exists():
        raise HTTPException(503, detail={'code':'NO_VERIFIED_TERMINAL_PAIRING','message':'Durable storage and configuration are present, but no real terminal pairing has a fresh authenticated EA heartbeat and READY worker state'})
    return {'status':'ready','database':'ok','managed_execution':'configured',
            'per_user_terminal_isolation':'WORKER_ATTESTATION_REQUIRED','verified_terminal_pairing':'healthy',
            'host_profile':HOST_PROFILE,'idle_timeout_seconds':HOST_IDLE_TIMEOUT_SECONDS,
            'host_started_at_epoch_seconds':HOST_STARTED_AT_EPOCH_SECONDS,**host_lifecycle_state()}


@app.post('/v1/owner/bootstrap-code')
def create_owner_bootstrap_code(request:Request, x_management_identity:str|None=Header(default=None)):
    """Issue a one-use ten-minute bootstrap code to a signed DestinedFX management session."""
    require_durable_execution()
    if not x_management_identity:
        raise HTTPException(401,detail={'code':'MANAGEMENT_IDENTITY_REQUIRED','message':'A signed management identity is required to issue a pairing bootstrap code'})
    _management_identity_claims(x_management_identity)
    ip=client_ip(request); window=int(time.time()//60)
    rate_key=digest('owner-bootstrap:'+ip)
    with tx(True) as c:
        row=c.execute('SELECT window,count FROM request_rate WHERE token_hash=?',(rate_key,)).fetchone()
        if row and row['window']==window and row['count']>=OWNER_BOOTSTRAP_RATE:
            raise HTTPException(429,detail={'code':'RATE_LIMITED','message':'Bootstrap code issuance rate limit exceeded'})
        count=(row['count']+1) if row and row['window']==window else 1
        c.execute('INSERT OR REPLACE INTO request_rate(token_hash,window,count) VALUES(?,?,?)',(rate_key,window,count))
    code,ttl=issue_owner_bootstrap_code()
    return {'owner_bootstrap_code':code,'expires_in_seconds':ttl}

@app.post('/v1/pairings',status_code=201)
def create_pair(request:Request, x_owner_bootstrap:str|None=Header(default=None), x_management_identity:str|None=Header(default=None)):
    require_durable_execution()
    # Validate the short-lived signed tenant identity before consuming the owner's one-time code.
    if not x_management_identity:
        raise HTTPException(401,detail={'code':'MANAGEMENT_IDENTITY_REQUIRED','message':'A signed management identity is required for MT5 pairing'})
    identity=_management_identity_claims(x_management_identity)
    # Public pairing additionally requires the owner's one-time 10-minute bootstrap code.
    if PUBLIC_EXPOSURE and not consume_owner_bootstrap_code(x_owner_bootstrap or ''):
        raise HTTPException(401,'Owner bootstrap code required')
    ip=client_ip(request); t=time.time()
    with tx(True) as c:
        row=c.execute('SELECT * FROM rate WHERE ip=?',(ip,)).fetchone()
        if row and t-row['window']<60 and row['count']>=PAIR_RATE: raise HTTPException(429,'Pairing rate limit exceeded')
        if row and t-row['window']<60: c.execute('UPDATE rate SET count=count+1 WHERE ip=?',(ip,))
        else: c.execute('INSERT OR REPLACE INTO rate VALUES(?,?,1)',(ip,t))
        pairing_id=str(uuid.uuid4()); code=secrets.token_urlsafe(12); app_token=token(); exp=t+600
        c.execute('INSERT INTO pairings(id,code_hash,app_hash,expires,created,owner_subject,owner_tenant) VALUES(?,?,?,?,?,?,?)',(pairing_id,digest(code),digest(app_token),exp,t,identity['sub'] if identity else None,identity.get('tenant_id') if identity else None))
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
        managed=c.execute('SELECT login_enc,server_enc,account_mode,broker_brand FROM managed_accounts WHERE pairing_id=? AND revoked=0',(found['id'],)).fetchone()
        if managed:
            fernet,_=managed_config()
            expected_login=fernet.decrypt(managed['login_enc'].encode()).decode()
            expected_server=fernet.decrypt(managed['server_enc'].encode()).decode()
            reported_mode=x.account.get('account_mode')
            if (reported_mode not in ('DEMO','LIVE') or x.account['login']!=expected_login or
                not broker_server_matches(managed['broker_brand'],expected_server,x.account['server']) or
                not broker_company_matches(managed['broker_brand'],x.account.get('company'))):
                raise HTTPException(401,'Terminal-reported broker, login, server or mode does not match the managed account')
            if managed['account_mode']=='UNVERIFIED':
                if reported_mode != 'DEMO':
                    raise HTTPException(401,'An unverified profile may only be enrolled as DEMO')
                c.execute("UPDATE managed_accounts SET account_mode='DEMO' WHERE pairing_id=? AND account_mode='UNVERIFIED'",(found['id'],))
            elif reported_mode != managed['account_mode']:
                raise HTTPException(401,'Terminal-reported broker, login, server or mode does not match the managed account')
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

BROKER_QUOTE_MAX_AGE_MS = 15_000
BROKER_QUOTE_FUTURE_SKEW_MS = 3_000

def fresh_terminal_broker_quotes(beat, now_ms=None):
    """Return only fresh two-sided quotes directly observed by the paired MT5 terminal."""
    if not isinstance(beat,dict):
        return []
    now_ms = int(time.time()*1000) if now_ms is None else int(now_ms)
    rows = beat.get('broker_quotes')
    if not isinstance(rows,list):
        return []
    accepted = []
    seen = set()
    for row in rows:
        if not isinstance(row,dict):
            continue
        symbol = row.get('symbol')
        try:
            bid = float(row.get('bid'))
            ask = float(row.get('ask'))
            digits = int(row.get('digits',5))
            timestamp_ms = int(row.get('timestamp_ms'))
        except (TypeError,ValueError,OverflowError):
            continue
        age_ms = now_ms - timestamp_ms
        if (not isinstance(symbol,str) or not symbol.strip() or any(ord(ch)<32 or ord(ch)==127 for ch in symbol) or
            not math.isfinite(bid) or not math.isfinite(ask) or bid<=0 or ask<bid or
            digits<0 or digits>12 or timestamp_ms<=0 or
            age_ms < -BROKER_QUOTE_FUTURE_SKEW_MS or age_ms > BROKER_QUOTE_MAX_AGE_MS):
            continue
        key=symbol.strip().upper()
        if key in seen:
            continue
        seen.add(key)
        accepted.append({'symbol':symbol.strip(),'bid':bid,'ask':ask,'digits':digits,'timestamp_ms':timestamp_ms,
                         'age_ms':max(0,age_ms),'source':'MT5_TERMINAL'})
    return accepted

def fresh_terminal_broker_candles(beat, now_ms=None):
    """Return bounded, structurally valid OHLC bars reported by the paired MT5 terminal."""
    if not isinstance(beat,dict):
        return []
    now_ms = int(time.time()*1000) if now_ms is None else int(now_ms)
    rows = beat.get('broker_candles')
    if not isinstance(rows,list):
        return []
    accepted = []
    seen = set()
    supported = {'M1','M5','M15','M30','H1','H4','D1','W1'}
    for row in rows[:1000]:
        if not isinstance(row,dict):
            continue
        symbol = row.get('symbol')
        timeframe = row.get('timeframe')
        try:
            stamp = int(row.get('timestamp_ms'))
            o,h,l,c = (float(row.get(k)) for k in ('open','high','low','close'))
            volume = float(row.get('volume',0.0))
        except (TypeError,ValueError,OverflowError):
            continue
        if (not isinstance(symbol,str) or not symbol.strip() or
            any(ord(ch)<32 or ord(ch)==127 for ch in symbol) or
            timeframe not in supported or stamp<=0 or stamp>now_ms+BROKER_QUOTE_FUTURE_SKEW_MS or
            not all(math.isfinite(v) and v>0 for v in (o,h,l,c)) or
            not math.isfinite(volume) or volume<0 or
            h<max(o,c,l) or l>min(o,c,h) or row.get('complete') is not True):
            continue
        key=(symbol.strip().upper(),timeframe,stamp)
        if key in seen:
            continue
        seen.add(key)
        accepted.append({'symbol':symbol.strip(),'timeframe':timeframe,'timestamp_ms':stamp,
                         'open':o,'high':h,'low':l,'close':c,'volume':volume,
                         'complete':True,'source':'MT5_TERMINAL'})
    accepted.sort(key=lambda item:(item['symbol'].upper(),item['timeframe'],item['timestamp_ms']))
    return accepted

def safe_terminal_chart_symbol(value):
    if not isinstance(value,str):
        return ''
    symbol=value.strip()
    if not symbol or len(symbol)>32 or not re.fullmatch(r'[A-Za-z0-9._#-]{1,32}',symbol):
        return ''
    return symbol



@app.get('/v1/mt5/server-catalog')
def mt5_server_catalog(broker: str = Query(..., min_length=2, max_length=64),
                       mode: str = Query(..., min_length=4, max_length=4),
                       q: str = Query(default='', max_length=96)):
    """Search any typed broker name against exact labels in the installed MT5 catalogue."""
    broker_query = broker.strip()
    account_mode = mode.strip().upper()
    normalized_broker = re.sub(r'[^A-Za-z0-9]', '', broker_query)
    if (len(normalized_broker) < 2 or len(normalized_broker) > 64 or
        not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9 .&_-]{1,63}', broker_query) or
        account_mode not in {'DEMO', 'LIVE'}):
        raise HTTPException(400, detail={'code': 'INVALID_BROKER_SEARCH_OR_MODE'})
    search_query = q.strip() or broker_query
    if len(search_query) < 2:
        return {'broker': broker_query, 'mode': account_mode, 'servers': [], 'catalog_available': False}
    rows = search_mt5_servers(broker_query, account_mode, search_query)
    return {
        'broker': broker_query,
        'mode': account_mode,
        'servers': rows,
        'count': len(rows),
        'catalog_available': bool(rows),
        'source': 'MT5 Config/servers.dat',
    }

def safe_terminal_chart_timeframe(value):
    allowed={'M1','M5','M15','M30','H1','H4','D1','W1'}
    return value.strip().upper() if isinstance(value,str) and value.strip().upper() in allowed else ''


def safe_analyzer_market_symbol(value):
    if not isinstance(value,str):
        return ''
    symbol=value.strip()
    if not symbol or len(symbol)>32 or not re.fullmatch(r'[A-Za-z0-9._# -]{1,32}',symbol):
        return ''
    return symbol


def analyzer_feed_local_peer(request):
    peer=request.client.host if request.client else ''
    return peer in ('127.0.0.1','::1','localhost','172.30.250.1')


@app.get('/v1/analyzer/market-selection')
def analyzer_market_selection():
    with _ANALYZER_FEED_LOCK:
        return dict(_ANALYZER_MARKET_SELECTION)


@app.post('/v1/analyzer/market-selection')
def set_analyzer_market_selection(x:AnalyzerMarketSelection):
    # Selection is only a read-only symbol/timeframe subscription, never an execution command.
    with _ANALYZER_FEED_LOCK:
        _ANALYZER_MARKET_SELECTION['symbol']=x.symbol
        _ANALYZER_MARKET_SELECTION['timeframe']=x.timeframe
    return {'accepted':True,'symbol':x.symbol,'timeframe':x.timeframe}


@app.post('/v1/analyzer/market-heartbeat')
def analyzer_market_heartbeat(x:AnalyzerMarketHeartbeat, request:Request):
    if not analyzer_feed_local_peer(request):
        raise HTTPException(403,detail={'code':'ANALYZER_FEED_LOCAL_PUBLISHER_ONLY',
                                        'message':'Market data must be published by the local MT5 terminal'})
    now_ms=int(time.time()*1000)
    if x.terminal_connected:
        account_identity_sha256=hashlib.sha256(
            f'{x.account_login}|{x.account_server}|{x.account_mode}'.encode('utf-8')
        ).hexdigest()
        accepted_quotes=fresh_terminal_broker_quotes(
            {'broker_quotes':[row.model_dump() for row in x.broker_quotes]},now_ms
        )[:128]
        accepted_candles=fresh_terminal_broker_candles(
            {'broker_candles':[row.model_dump() for row in x.broker_candles]},now_ms
        )[:1000]
        symbols=[]
        seen=set()
        for value in x.symbols:
            symbol=safe_analyzer_market_symbol(value)
            if symbol and symbol.upper() not in seen:
                seen.add(symbol.upper())
                symbols.append(symbol)
    else:
        account_identity_sha256=''
        accepted_quotes=[]
        accepted_candles=[]
        symbols=[]
    with _ANALYZER_FEED_LOCK:
        _ANALYZER_FEED_STATE.update({
            'heartbeat_at_ms':now_ms,
            'terminal_connected':bool(x.terminal_connected),
            'account_identity_sha256':account_identity_sha256,
            'symbols':symbols,
            'broker_quotes':accepted_quotes,
            'broker_candles':accepted_candles,
        })
    return {'accepted':True,'received_at_ms':now_ms,
            'quote_count':len(accepted_quotes),'candle_count':len(accepted_candles)}


@app.get('/v1/analyzer/market-state')
def analyzer_market_state():
    now_ms=int(time.time()*1000)
    with _ANALYZER_FEED_LOCK:
        state={key:(list(value) if isinstance(value,list) else value)
               for key,value in _ANALYZER_FEED_STATE.items()}
        selection=dict(_ANALYZER_MARKET_SELECTION)
    heartbeat_at=state.get('heartbeat_at_ms',0)
    age_ms=now_ms-heartbeat_at if isinstance(heartbeat_at,int) and heartbeat_at>0 else None
    heartbeat_fresh=bool(age_ms is not None and 0<=age_ms<=BROKER_QUOTE_MAX_AGE_MS)
    connected=bool(heartbeat_fresh and state.get('terminal_connected') is True)
    quotes=fresh_terminal_broker_quotes({'broker_quotes':state.get('broker_quotes',[])},now_ms) if connected else []
    candles=fresh_terminal_broker_candles({'broker_candles':state.get('broker_candles',[])},now_ms) if connected else []
    symbols=state.get('symbols',[]) if connected else []
    return {
        'connected':connected,
        'terminal_connected':bool(state.get('terminal_connected')),
        'heartbeat_fresh':heartbeat_fresh,
        'heartbeat_age_seconds':None if age_ms is None else max(0,int(age_ms/1000)),
        'account_identity_sha256':state.get('account_identity_sha256','') if connected else '',
        'symbols':symbols,
        'broker_quotes':quotes,
        'broker_candles':candles,
        'selected_symbol':selection.get('symbol',''),
        'selected_timeframe':selection.get('timeframe','M15'),
        'error_message':'' if connected else 'MT5 not connected',
    }


@app.get('/v1/app/state')
def state(authorization:str|None=Header(default=None)):
    # Passive state polling must not keep an idle engine awake indefinitely.
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
        m=c.execute('SELECT access_type,revoked,account_mode,broker_brand,login_enc,server_enc,credential_generation FROM managed_accounts WHERE pairing_id=?',(p['id'],)).fetchone()
        job=c.execute('SELECT status,error_code,error_message FROM worker_jobs WHERE pairing_id=? ORDER BY created DESC LIMIT 1',(p['id'],)).fetchone()
    managed=None
    identity_verified=False
    actual=None
    if m:
        actual=fresh_heartbeat(p); worker_ready=bool(job and job['status']=='READY')
        claimed=json.loads(p['account']) if p['account'] else {}
        identity_verified=bool(actual and m['account_mode'] in ('DEMO','LIVE') and actual.get('account') and all(actual['account'].get(k)==claimed.get(k) for k in ('login','server','company','currency','account_mode')) and actual['account'].get('account_mode')==m['account_mode'])
        fernet,_=managed_config()
        expected_login=fernet.decrypt(m['login_enc']).decode()
        expected_server=fernet.decrypt(m['server_enc']).decode()
        broker_identity_verified=bool(actual and actual.get('account') and broker_company_matches(m['broker_brand'], actual['account'].get('company','')) and broker_server_matches(m['broker_brand'], expected_server, actual['account'].get('server','')))
        app_status='READY' if identity_verified and worker_ready and execution_state_eligible(actual) and heartbeat_news_clear(actual) else ('STALE' if worker_ready and p['heartbeat'] else ('AUTHENTICATING' if worker_ready else (job['status'] if job else 'QUEUED')))
        live_auth=live_authorization_valid(p,m,actual) if m['account_mode']=='LIVE' else False
        managed={'status':app_status,'worker_status':job['status'] if job else 'NOT_CONFIGURED','access_type':m['access_type'],'account_mode':m['account_mode'],'broker_brand':m['broker_brand'],'requested_login':expected_login,'requested_server':expected_server,'terminal_fresh':bool(actual),'account_identity_verified':identity_verified,'broker_identity_verified':broker_identity_verified,'execution_state_eligible':execution_state_eligible(actual),'news_ready':heartbeat_news_clear(actual),'live_authorized':live_auth,'live_authorized_until':iso(datetime.fromtimestamp(p['live_authorized_until'],timezone.utc)) if p['live_authorized_until'] else None,'trade_allowed':bool(actual and actual.get('trade_allowed')),'error_code':job['error_code'] if job else None,'error_message':job['error_message'] if job else None}
        if actual:
            with tx(True) as c: ingest_trade_ledger(c,p['id'],m['account_mode'],actual,time.time())
    risk_controls=live_risk_controls(actual)
    terminal_data_verified = bool(online and beat and beat.get('terminal_connected') is True and actual and
                                 identity_verified and broker_identity_verified)
    engine_status = read_status(HOST_CONTROL_DIR)
    engine_idle_status = str(engine_status.get('status','UNKNOWN')).upper()
    engine_idle_timeout_seconds = max(120, min(86400, int(
        engine_status.get('idle_timeout_seconds') or os.getenv('DESTINEDFX_AUTO_SLEEP_IDLE_SECONDS','120')
    )))
    engine_idle_elapsed_seconds = max(0, int(engine_status.get('idle_seconds') or 0))
    engine_idle_remaining_seconds = (
        max(0, engine_idle_timeout_seconds-engine_idle_elapsed_seconds)
        if engine_idle_status in ('RUNNING','STARTING') else None
    )
    broker_quotes = fresh_terminal_broker_quotes(beat) if terminal_data_verified else []
    broker_candles = fresh_terminal_broker_candles(beat) if terminal_data_verified else []
    state_account = beat.get('account') if online and isinstance(beat,dict) else None
    def positive_account_metric(value):
        return isinstance(value,(int,float)) and not isinstance(value,bool) and math.isfinite(float(value)) and float(value)>0
    account_balance_available = bool(
        isinstance(state_account,dict) and
        positive_account_metric(state_account.get('balance')) and
        positive_account_metric(state_account.get('equity'))
    )
    connection_error = None
    if online and not account_balance_available:
        connection_error = {
            'code':'ACCOUNT_BALANCE_UNAVAILABLE',
            'message':'The authenticated terminal did not provide a finite positive balance and equity snapshot; quote display may continue, but order execution remains blocked.'
        }
    result={'pairing_id':p['id'],'management_identity_bound':bool(p['owner_subject'] and p['owner_tenant']),'host_profile':HOST_PROFILE,'idle_timeout_seconds':HOST_IDLE_TIMEOUT_SECONDS,'host_started_at_epoch_seconds':HOST_STARTED_AT_EPOCH_SECONDS,**host_lifecycle_state(),'engine_idle_status':engine_idle_status,'engine_idle_timeout_seconds':engine_idle_timeout_seconds,'engine_idle_remaining_seconds':engine_idle_remaining_seconds,'pairing_status':'PAIRED' if p['consumed'] else 'PAIRING','terminal_online':online,'heartbeat_age_seconds':age,'heartbeat_stale_seconds':STALE,'ea_state':beat['state'] if online else 'OFFLINE','terminal_connected':beat['terminal_connected'] if online else False,'trade_allowed':beat['trade_allowed'] if online else False,'account':beat['account'] if online else None,'account_balance_available':account_balance_available,'connection_error':connection_error,'execution_storage_durable':durable_execution_enabled(),'account_identity_verified':identity_verified,'broker_identity_verified':broker_identity_verified if m else False,'configuration':beat['configuration'] if online else {},'risk_controls':risk_controls,'news_verdict':beat.get('news_verdict',{'status':'UNKNOWN','detail':'NEWS UNKNOWN: authenticated verdict unavailable'}) if online else {'status':'UNKNOWN','detail':'NEWS UNKNOWN: terminal heartbeat unavailable'},'last_signal':beat['last_signal'] if online else None,'last_error':beat['last_error'] if online else ('Terminal heartbeat is stale or missing' if p['heartbeat'] else ''),'open_positions':beat['open_positions'] if online else None,'active_orders':beat.get('active_orders',[]) if online else None,'closed_deals':beat['closed_deals'] if online else None,'last_heartbeat_at':beat['timestamp'] if beat else None,'last_command':last_command,'broker_quotes':broker_quotes,'broker_candles':broker_candles,'active_chart_symbol':safe_terminal_chart_symbol(beat.get('active_chart_symbol')) if terminal_data_verified else '', 'active_chart_timeframe':safe_terminal_chart_timeframe(beat.get('active_chart_timeframe')) if terminal_data_verified else '', 'broker_quote_stale_ms':BROKER_QUOTE_MAX_AGE_MS}
    if managed is not None: result['managed_execution']=managed
    return result


def _require_pairing_owner(p, assertion):
    if not assertion:
        raise HTTPException(401,detail={'code':'MANAGEMENT_IDENTITY_REQUIRED','message':'Re-authenticate as the owner before controlling this Codespace'})
    identity=_management_identity_claims(assertion)
    if (not p.get('owner_subject') or not p.get('owner_tenant') or
        identity.get('sub') != p['owner_subject'] or identity.get('tenant_id') != p['owner_tenant']):
        raise HTTPException(403,detail={'code':'MANAGEMENT_IDENTITY_MISMATCH','message':'Only the management user that owns this Bridge pairing may control the Codespace'})

def run_stop_script_preflight():
    """Require an explicit read-only acknowledgement from the real local stop.sh."""
    root=Path(__file__).resolve().parents[1]
    script=root/'deploy'/'codespaces'/'stop.sh'
    if not script.is_file():
        return False
    env=os.environ.copy()
    env['BRIDGE_DB_PATH']=str(DB_PATH)
    env.setdefault('DESTINEDFX_CODESPACES_STATE_DIR',str(Path(HOST_CONTROL_DIR).parent))
    try:
        result=subprocess.run(
            ['bash',str(script),'--preflight'],
            cwd=str(root),
            env=env,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=20,
            check=False,
        )
    except (OSError,subprocess.SubprocessError):
        return False
    expected='PASS: STOP PREFLIGHT acknowledged by stop.sh; no services or trading processes were stopped.'
    return result.returncode==0 and expected in result.stdout


@app.post('/v1/app/github/revoke')
def revoke_github_app_authorization(
    x:GitHubTokenRevoke,
    authorization:str|None=Header(default=None),
    x_management_identity:str|None=Header(default=None)
):
    """Revoke the owner's GitHub App user token without shipping the App secret to Android."""
    p=auth(authorization,'app')
    _require_pairing_owner(p,x_management_identity)
    if not x.access_token.startswith('ghu_') or any(ord(ch)<32 or ord(ch)==127 for ch in x.access_token):
        raise HTTPException(422,detail={'code':'GITHUB_TOKEN_INVALID','message':'Only a GitHub App user access token can be revoked here'})
    client_id=os.getenv('CODESPACES_GITHUB_CLIENT_ID','').strip()
    client_secret=os.getenv('CODESPACES_GITHUB_CLIENT_SECRET','').strip()
    if not client_id or not client_secret:
        raise HTTPException(503,detail={
            'code':'GITHUB_REVOCATION_NOT_CONFIGURED',
            'message':'Remote revocation is not configured on this Bridge. Open GitHub Settings and revoke the app authorization there; local tokens have not been changed.',
        })
    if len(client_id)>128 or len(client_secret)>4096:
        raise HTTPException(503,detail={'code':'GITHUB_REVOCATION_NOT_CONFIGURED','message':'GitHub App revocation configuration is invalid'})
    credentials=base64.b64encode((client_id+':'+client_secret).encode()).decode()
    body=json.dumps({'access_token':x.access_token},separators=(',',':')).encode()
    url='https://api.github.com/applications/'+quote(client_id,safe='')+'/grant'
    req=urllib.request.Request(url,data=body,headers={
        'Authorization':'Basic '+credentials,
        'Accept':'application/vnd.github+json',
        'X-GitHub-Api-Version':'2026-03-10',
        'Content-Type':'application/json',
        'User-Agent':'DestinedFX-Bridge',
    },method='DELETE')
    try:
        with urllib.request.urlopen(req,timeout=8) as response:
            if response.status not in (200,204):
                raise HTTPException(503,detail={'code':'GITHUB_REVOCATION_FAILED','message':'GitHub did not confirm token revocation'})
    except urllib.error.HTTPError as error:
        # Do not include response body, token, secret or Basic header in logs or API output.
        if error.code in (401,403,404,422):
            raise HTTPException(409,detail={'code':'GITHUB_REVOCATION_REJECTED','message':'GitHub rejected the revocation request; check App configuration and token validity'}) from None
        raise HTTPException(503,detail={'code':'GITHUB_REVOCATION_FAILED','message':'GitHub revocation service is temporarily unavailable'}) from None
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(503,detail={'code':'GITHUB_REVOCATION_FAILED','message':'GitHub revocation service is unavailable'}) from None
    return {'revoked':True}


@app.post('/v1/app/host/activity')
def app_host_activity(authorization:str|None=Header(default=None)):
    auth(authorization,'app')
    touch_activity(HOST_CONTROL_DIR)
    return {'status':'ACTIVE','host_profile':HOST_PROFILE}


@app.get('/host-status')
@app.get('/v1/app/host/status')
def app_host_status(authorization:str|None=Header(default=None)):
    auth(authorization,'app')
    with tx() as c:
        shutdown_prepared = _host_stop_prepared(c)
    isolation=linux_slot_isolation(linux_host_report()) if LINUX_HOST_MODE else {'enabled':False,'ready':True,'active_slots':0,'max_slots':0}
    return {'host_profile':HOST_PROFILE,'shutdown_prepared':shutdown_prepared,
            'slot_isolation':isolation,**host_lifecycle_state(),**read_status(HOST_CONTROL_DIR)}


@app.post('/host/shutdown-safe')
@app.post('/v1/app/host/shutdown-safe')
@app.post('/v1/app/host/prepare-stop')
def prepare_host_stop(authorization:str|None=Header(default=None),
                      x_management_identity:str|None=Header(default=None)):
    p=auth(authorization,'app')
    _require_pairing_owner(p,x_management_identity)
    if not LINUX_HOST_MODE or HOST_PROFILE != 'EPHEMERAL':
        raise HTTPException(409,detail={'code':'HOST_LIFECYCLE_UNAVAILABLE','message':'This Bridge is not running the owner-controlled Codespaces host lifecycle'})

    # Serialize shutdown preparation with the command insertion transactions.
    with tx(True) as c:
        c.execute(
            """INSERT INTO host_control(key,value,updated)
               VALUES('shutdown_prepared',?,?)
               ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated=excluded.updated""",
            (HOST_GENERATION,time.time()),
        )

    try:
        safety=inspect_shutdown_safety(DB_PATH,heartbeat_stale_seconds=STALE)
        if not safety['safe']:
            raise HTTPException(409,detail={'code':'HOST_STOP_BLOCKED','reasons':safety['reasons'],
                'message':'Stop the EA and resolve open positions, active orders, stale broker state or unfinished commands before stopping the host'})

        backup=backup_database(DB_PATH,Path(HOST_CONTROL_DIR).parent/'backups')
        backup_safety=inspect_shutdown_safety(backup,heartbeat_stale_seconds=STALE)
        current_safety=inspect_shutdown_safety(DB_PATH,heartbeat_stale_seconds=STALE)
        if not backup_safety['safe'] or not current_safety['safe']:
            reasons=sorted(set(backup_safety['reasons']) | set(current_safety['reasons']))
            raise HTTPException(409,detail={'code':'HOST_STOP_STATE_CHANGED','reasons':reasons,
                'message':'Broker state changed during backup verification; the host remains online and shutdown was cancelled'})
        if not run_stop_script_preflight():
            raise HTTPException(503,detail={'code':'HOST_STOP_PREFLIGHT_FAILED',
                'message':'The local stop.sh preflight did not acknowledge safe isolated-slot cleanup; the Codespace must remain running'})
    except HTTPException:
        try: cancel_stop(HOST_CONTROL_DIR,DB_PATH,HOST_GENERATION)
        except sqlite3.Error: pass
        raise
    except (OSError,sqlite3.Error):
        try: cancel_stop(HOST_CONTROL_DIR,DB_PATH,HOST_GENERATION)
        except sqlite3.Error: pass
        raise HTTPException(503,detail={'code':'HOST_BACKUP_FAILED',
            'message':'A verified database backup and safe-state recheck could not be completed; the host was not prepared for shutdown'})
    return {'status':'PREPARED','host_generation':HOST_GENERATION,'backup_verified':True,
            'stop_script_ack':True,'stop_script_status':'PREFLIGHT_PASSED',
            'backup_name':backup.name,'broker_safety':current_safety,'backup_safety':backup_safety}


@app.post('/v1/app/host/cancel-stop')
def cancel_host_stop(authorization:str|None=Header(default=None),
                     x_management_identity:str|None=Header(default=None)):
    p=auth(authorization,'app')
    _require_pairing_owner(p,x_management_identity)
    if not LINUX_HOST_MODE or HOST_PROFILE != 'EPHEMERAL':
        raise HTTPException(409,detail={'code':'HOST_LIFECYCLE_UNAVAILABLE','message':'This Bridge is not running the owner-controlled Codespaces host lifecycle'})
    try: cancel_stop(HOST_CONTROL_DIR,DB_PATH,HOST_GENERATION)
    except sqlite3.Error:
        raise HTTPException(503,detail={'code':'HOST_STOP_CANCEL_FAILED','message':'The durable host-shutdown gate could not be cleared'})
    return {'status':'CANCELLED','host_generation':HOST_GENERATION}


@app.post('/v1/app/analysis',response_model=AnalysisResponse)
def app_analysis(x:AnalysisRequest,authorization:str|None=Header(default=None)):
    """Run deterministic analysis on an authenticated server-fetched snapshot.

    This endpoint is deliberately analysis-only: the response is always marked
    non-executable by the quantitative module and is never translated into a
    broker command. Reusing a request ID returns the original response; changing
    its payload is rejected to prevent accidental duplicate work.
    """
    p=auth(authorization,'app')
    identity_payload=x.model_dump(mode='json')
    identity_raw=json.dumps(identity_payload,sort_keys=True,separators=(',',':'),allow_nan=False)
    payload_hash=digest(identity_raw)
    with tx() as c:
        prior=c.execute('SELECT payload_hash,response FROM analysis_requests WHERE pairing_id=? AND request_id=?',(p['id'],x.request_id)).fetchone()
    if prior:
        if not hmac.compare_digest(prior['payload_hash'],payload_hash):
            raise HTTPException(409,'request_id was already used for a different analysis payload')
        return json.loads(prior['response'])
    symbol=x.symbol.strip().upper(); timeframe=x.timeframe.strip().upper()
    if symbol not in ANALYSIS_SYMBOL_POLICY:
        raise HTTPException(422,'Unsupported symbol')
    if timeframe not in ANALYSIS_TIMEFRAME_POLICY:
        raise HTTPException(422,'Unsupported timeframe; expected one of M1, M5, M15, M30, H1, H4, D1, W1')
    provider=fetch_mt5_terminal_snapshot(symbol,timeframe,authorization)
    server_now_ms,timeframe,interval_ms,max_age_ms,allow_session_gaps=validate_analysis_snapshot(x,provider)
    quote=provider['quote']
    payload={
        'symbol':symbol,'timeframe':timeframe,'strategy_id':x.strategy_id,
        'source':provider['provider_id'],'now_ms':server_now_ms,
        'provider_retrieved_at_ms':provider['observed_ms'],
        'expected_interval_ms':interval_ms,'max_candle_age_ms':max_age_ms,
        'asset_class':provider['asset_class'],'allow_session_gaps':allow_session_gaps,
        'quote_bid':quote.bid,'quote_ask':quote.ask,
        'candles':[c.model_dump(mode='json') for c in provider['candles']],
    }
    raw=json.dumps(payload,sort_keys=True,separators=(',',':'),allow_nan=False)
    response=json.loads(analyze_json(raw))
    # The quantitative contract is the final safety boundary for this route.
    response.update({
        'provider_retrieved_at_ms':provider['observed_ms'],
        'quote_bid':quote.bid,
        'quote_ask':quote.ask,
        'quote_retrieved_at_ms':quote.timestamp_ms,
        'quote_source':provider['provider_id'],
        'server_now_ms':server_now_ms,
        'executable':False,
    })
    validated=AnalysisResponse.model_validate(response)
    serialized=json.dumps(validated.model_dump(mode='json'),sort_keys=True,separators=(',',':'),allow_nan=False)
    with tx(True) as c:
        try:
            c.execute('INSERT INTO analysis_requests(pairing_id,request_id,payload_hash,response,created) VALUES(?,?,?,?,?)',(p['id'],x.request_id,payload_hash,serialized,time.time()))
        except sqlite3.IntegrityError:
            prior=c.execute('SELECT payload_hash,response FROM analysis_requests WHERE pairing_id=? AND request_id=?',(p['id'],x.request_id)).fetchone()
            if not prior or not hmac.compare_digest(prior['payload_hash'],payload_hash):
                raise HTTPException(409,'request_id was already used for a different analysis payload')
            serialized=prior['response']
    return json.loads(serialized)

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

@app.post('/v1/app/demo-acceptance')
def accept_demo(x:DemoAcceptanceRequest,authorization:str|None=Header(default=None),
                x_management_identity:str|None=Header(default=None)):
    require_durable_execution()
    p=auth(authorization,'app')
    if not x_management_identity:
        raise HTTPException(401,detail={'code':'MANAGEMENT_IDENTITY_REQUIRED','message':'Re-authenticate before accepting this DEMO profile'})
    identity=_management_identity_claims(x_management_identity)
    if (not p['owner_subject'] or not p['owner_tenant'] or
        identity['sub']!=p['owner_subject'] or identity['tenant_id']!=p['owner_tenant']):
        raise HTTPException(403,detail={'code':'MANAGEMENT_IDENTITY_MISMATCH','message':'The current management user does not own this DEMO pairing'})
    if not x.confirm_demo:
        raise HTTPException(422,'Explicit DEMO acceptance is required')
    managed,job,worker,beat=_current_ready_context(p,'DEMO')
    if managed['access_type']!='MASTER' or not heartbeat_news_clear(beat):
        raise HTTPException(409,'DEMO acceptance requires a MASTER account and fresh clear evidence')
    if not managed['credential_generation']:
        raise HTTPException(409,detail={'code':'CREDENTIAL_GENERATION_REQUIRED','message':'The DEMO credential generation must be recorded before acceptance'})
    try:
        evidence=DemoPassEvidence.model_validate_json(x.evidence_document)
    except Exception as exc:
        raise HTTPException(422,detail={'code':'DEMO_PASS_EVIDENCE_INVALID','message':'The submitted DEMO PASS document is malformed or incomplete'}) from exc
    try:
        accepted_days=[datetime.strptime(day,'%Y-%m-%d').date() for day in evidence.accepted_days]
    except ValueError as exc:
        raise HTTPException(422,detail={'code':'DEMO_PASS_EVIDENCE_INVALID','message':'Accepted DEMO soak days must use YYYY-MM-DD dates'}) from exc
    today=now().astimezone(timezone.utc).date()
    expected_account_digest=demo_soak_account_digest(beat['account'])
    with tx() as c:
        soak_rows_desc=c.execute('''SELECT * FROM demo_soak_daily WHERE pairing_id=? AND invalid=0
                                   AND account_digest=? AND credential_generation=? AND worker_id=?
                                   AND worker_generation=? AND host_generation=?
                                   ORDER BY day_utc DESC LIMIT 7''',
                                 (p['id'],expected_account_digest,managed['credential_generation'],
                                  job['worker_id'],worker['generation'],HOST_GENERATION)).fetchall()
        closed_rows=c.execute('''SELECT identity,position_id,deal_ticket,payload FROM trade_ledger
                                 WHERE pairing_id=? AND account_mode='DEMO' AND status='CLOSED'
                                 AND position_id IS NOT NULL AND deal_ticket IS NOT NULL''',(p['id'],)).fetchall()
    selected_rows=list(reversed(soak_rows_desc[:evidence.soak_days]))
    recorded_days=[row['day_utc'] for row in selected_rows]
    try:
        recorded_dates=[datetime.strptime(day,'%Y-%m-%d').date() for day in recorded_days]
    except ValueError:
        recorded_dates=[]
    accepted_days_are_valid=(
        len(selected_rows)==evidence.soak_days and len(accepted_days)>=5 and
        accepted_days==sorted(set(accepted_days)) and accepted_days==recorded_dates and
        all((accepted_days[index]-accepted_days[index-1]).days==1 for index in range(1,len(accepted_days))) and
        accepted_days[-1] <= today and (today-accepted_days[-1]).days <= 1
    )
    daily_soak_quality=True
    observed_instance_ids=set()
    for row in selected_rows:
        try:
            instance_ids=json.loads(row['ea_instance_ids'])
            day=datetime.strptime(row['day_utc'],'%Y-%m-%d').date()
            first=datetime.fromtimestamp(row['first_seen'],timezone.utc).date()
            last=datetime.fromtimestamp(row['last_seen'],timezone.utc).date()
        except Exception:
            daily_soak_quality=False
            continue
        if (not isinstance(instance_ids,list) or not instance_ids or
            row['heartbeat_count']<12 or row['risk_clear_count']<1 or
            row['news_clear_count']<1 or row['reconciliation_count']<1 or
            row['last_seen']-row['first_seen']<60 or
            first!=day or last!=day or row['last_seen']>time.time()+5):
            daily_soak_quality=False
        else:
            observed_instance_ids.update(value for value in instance_ids if isinstance(value,str) and value)
    restart_instances_observed=len(observed_instance_ids)
    closed_trade_count_observed=len(closed_rows)
    closed_trade_rows_valid=True
    for row in closed_rows:
        try:
            trade=json.loads(row['payload'])
        except Exception:
            closed_trade_rows_valid=False
            continue
        if (not isinstance(trade,dict) or trade.get('kind')!='closed_position' or
            trade.get('lifecycle')!='CLOSED' or not isinstance(trade.get('deals'),list) or
            not trade['deals'] or not row['identity'] or not row['position_id'] or not row['deal_ticket']):
            closed_trade_rows_valid=False
    evidence_paths_valid=(
        len(set(evidence.evidence_paths))==len(evidence.evidence_paths) and
        all(item.strip() and len(item)<=512 and not item.startswith('/') and chr(92) not in item and
            not any(part in ('','.','..') for part in item.split('/')) and
            not any(ord(ch)<32 or ord(ch)==127 for ch in item) for item in evidence.evidence_paths)
    )
    if any((
        evidence.evidence_version != 1,
        evidence.pairing_id != p['id'],
        evidence.broker.strip().upper() != managed['broker_brand'],
        evidence.mode.strip().upper() != 'DEMO',
        evidence.status.strip().upper() != 'PASS',
        evidence.account_login != beat['account'].get('login'),
        evidence.server != beat['account'].get('server'),
        evidence.credential_generation != managed['credential_generation'],
        evidence.worker_id != job['worker_id'],
        evidence.worker_generation != worker['generation'],
        evidence.host_generation != HOST_GENERATION,
        evidence.soak_days not in range(5,8),
        not accepted_days_are_valid,
        not daily_soak_quality,
        evidence.restart_instances != restart_instances_observed or restart_instances_observed<2,
        evidence.closed_trade_count != closed_trade_count_observed or closed_trade_count_observed<1,
        not closed_trade_rows_valid,
        evidence.order_lifecycle_pass is not True or closed_trade_count_observed<1,
        evidence.daily_reconciliation_pass is not True or not selected_rows or any(row['reconciliation_count']<1 for row in selected_rows),
        evidence.restart_test_pass is not True or restart_instances_observed<2,
        evidence.risk_controls_pass is not True or not selected_rows or any(row['risk_clear_count']<1 for row in selected_rows),
        not selected_rows or any(row['news_clear_count']<1 for row in selected_rows),
        not evidence_paths_valid,
        evidence.bridge_proof != demo_pass_bridge_proof(evidence),
    )):
        raise HTTPException(409,detail={'code':'DEMO_PASS_EVIDENCE_MISMATCH','message':'The DEMO PASS must match five-to-seven server-recorded soak days, risk/news/reconciliation telemetry, two EA instances, a reconciled closed trade, and the current pairing/worker/host identity'})
    # Hash the exact UTF-8 JSON document received by the Bridge; never trust a client-supplied digest.
    pass_evidence_digest=hashlib.sha256(x.evidence_document.encode('utf-8')).hexdigest()
    account=beat['account']
    raw=json.dumps({'pairing_id':p['id'],'broker_brand':managed['broker_brand'],'account':account,
                    'credential_generation':managed['credential_generation'],
                    'pass_evidence_digest':pass_evidence_digest,
                    'heartbeat_seq':p['heartbeat_seq'],'worker_id':job['worker_id'],
                    'worker_generation':worker['generation'],'host_generation':HOST_GENERATION},
                   sort_keys=True,separators=(',',':'))
    acceptance_id=str(uuid.uuid4()); now_epoch=time.time(); expires=now_epoch+86400
    with tx(True) as c:
        # Update/reacceptance revokes all dependent LIVE leases and challenges first.
        c.execute("""UPDATE pairings SET live_authorized_until=NULL,live_authorized_login=NULL,live_authorized_server=NULL
                     WHERE id IN (SELECT pairing_id FROM live_profile_links WHERE demo_pairing_id=?)""",(p['id'],))
        c.execute('UPDATE live_profile_links SET revoked=1 WHERE demo_pairing_id=?',(p['id'],))
        c.execute('UPDATE live_challenges SET used=1 WHERE demo_pairing_id=? AND used=0',(p['id'],))
        previous=c.execute('SELECT id FROM demo_acceptances WHERE pairing_id=?',(p['id'],)).fetchone()
        if previous:
            acceptance_id=previous['id']
            c.execute('''UPDATE demo_acceptances SET broker_brand=?,login=?,server=?,company=?,currency=?,
                         account_mode='DEMO',heartbeat_seq=?,evidence_digest=?,accepted_at=?,expires_at=?,
                         credential_generation=?,worker_id=?,worker_generation=?,host_generation=?,
                         pass_evidence_digest=?,revoked=0 WHERE id=? AND pairing_id=?''',
                (managed['broker_brand'],account['login'],account['server'],account['company'],
                 account['currency'],p['heartbeat_seq'],digest(raw),now_epoch,expires,
                 managed['credential_generation'],job['worker_id'],worker['generation'],
                 HOST_GENERATION,pass_evidence_digest,acceptance_id,p['id']))
        else:
            c.execute('''INSERT INTO demo_acceptances
                (id,pairing_id,broker_brand,login,server,company,currency,account_mode,heartbeat_seq,
                 evidence_digest,accepted_at,expires_at,credential_generation,worker_id,worker_generation,
                 host_generation,pass_evidence_digest,revoked)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)''',
                (acceptance_id,p['id'],managed['broker_brand'],account['login'],account['server'],
                 account['company'],account['currency'],'DEMO',p['heartbeat_seq'],digest(raw),
                 now_epoch,expires,managed['credential_generation'],job['worker_id'],worker['generation'],
                 HOST_GENERATION,pass_evidence_digest))
    return {'accepted':True,'acceptance_id':acceptance_id,'pairing_id':p['id'],
            'broker_brand':managed['broker_brand'],'login':account['login'],'server':account['server'],
            'credential_generation':managed['credential_generation'],'evidence_digest':pass_evidence_digest,
            'expires_at':iso(datetime.fromtimestamp(expires,timezone.utc))}

@app.post('/v1/app/live-profile-association')
def associate_live_profile(x:LiveProfileAssociationRequest, authorization:str|None=Header(default=None),
                            x_management_identity:str|None=Header(default=None)):
    require_durable_execution()
    p=auth(authorization,'app')
    if not x_management_identity:
        raise HTTPException(401,detail={'code':'MANAGEMENT_IDENTITY_REQUIRED','message':'Re-authenticate before linking DEMO and LIVE profiles'})
    identity=_management_identity_claims(x_management_identity)
    if (not p['owner_subject'] or not p['owner_tenant'] or
        identity['sub']!=p['owner_subject'] or identity['tenant_id']!=p['owner_tenant']):
        raise HTTPException(403,detail={'code':'MANAGEMENT_IDENTITY_MISMATCH','message':'The current management user does not own this LIVE pairing'})
    if not x.confirm_association:
        raise HTTPException(422,'Explicit DEMO-to-LIVE association is required')
    managed,job,worker,beat=_current_ready_context(p,'LIVE')
    live_account=beat.get('account') or {}
    if managed['access_type']!='MASTER' or not managed['credential_generation']:
        raise HTTPException(409,detail={'code':'LIVE_PROFILE_NOT_ELIGIBLE','message':'A verified MASTER LIVE profile with a credential generation is required'})
    link={
        'pairing_id':p['id'],'demo_pairing_id':x.demo_pairing_id,
        'demo_acceptance_id':x.demo_acceptance_id,'broker_brand':managed['broker_brand'],
        'login':live_account.get('login'),'server':live_account.get('server'),
        'credential_generation':managed['credential_generation'],'revoked':0,
    }
    acceptance,demo_managed,demo_pair=_verified_live_demo_association(p,link,managed,beat)
    associated_at=time.time()
    with tx(True) as c:
        # Re-linking a LIVE profile revokes its earlier lease/challenges before replacing the association.
        c.execute('UPDATE pairings SET live_authorized_until=NULL,live_authorized_login=NULL,live_authorized_server=NULL WHERE id=?',(p['id'],))
        c.execute('UPDATE live_challenges SET used=1 WHERE pairing_id=? AND used=0',(p['id'],))
        c.execute('''INSERT INTO live_profile_links
            (pairing_id,demo_acceptance_id,demo_pairing_id,broker_brand,login,server,credential_generation,created_at,revoked)
            VALUES(?,?,?,?,?,?,?,?,0)
            ON CONFLICT(pairing_id) DO UPDATE SET demo_acceptance_id=excluded.demo_acceptance_id,
              demo_pairing_id=excluded.demo_pairing_id,broker_brand=excluded.broker_brand,
              login=excluded.login,server=excluded.server,credential_generation=excluded.credential_generation,
              created_at=excluded.created_at,revoked=0''',
            (p['id'],x.demo_acceptance_id,x.demo_pairing_id,managed['broker_brand'],
             live_account['login'],live_account['server'],managed['credential_generation'],associated_at))
        pair_check=c.execute('SELECT owner_subject,owner_tenant FROM pairings WHERE id=?',(x.demo_pairing_id,)).fetchone()
        acceptance_check=c.execute('''SELECT credential_generation,worker_id,worker_generation,host_generation,
                                      pass_evidence_digest,revoked,expires_at
                                      FROM demo_acceptances WHERE id=? AND pairing_id=?''',
                                   (x.demo_acceptance_id,x.demo_pairing_id)).fetchone()
        live_check=c.execute('SELECT credential_generation FROM managed_accounts WHERE pairing_id=?',(p['id'],)).fetchone()
        demo_check=c.execute('SELECT credential_generation FROM managed_accounts WHERE pairing_id=?',(x.demo_pairing_id,)).fetchone()
        job_check=c.execute("SELECT worker_id,status FROM worker_jobs WHERE pairing_id=? ORDER BY created DESC LIMIT 1",
                             (x.demo_pairing_id,)).fetchone()
        worker_check=c.execute("SELECT generation,status,expires_at FROM worker_identities WHERE worker_id=?",
                               (job_check['worker_id'],)).fetchone() if job_check and job_check['worker_id'] else None
        if (not pair_check or pair_check['owner_subject']!=p['owner_subject'] or pair_check['owner_tenant']!=p['owner_tenant'] or
            not acceptance_check or acceptance_check['revoked'] or acceptance_check['expires_at']<=associated_at or
            not re.fullmatch(r'[0-9a-fA-F]{64}',acceptance_check['pass_evidence_digest'] or '') or
            not live_check or live_check['credential_generation']!=managed['credential_generation'] or
            not demo_check or demo_check['credential_generation']!=acceptance_check['credential_generation'] or
            not job_check or job_check['status']!='READY' or job_check['worker_id']!=acceptance_check['worker_id'] or
            not worker_check or worker_check['status']!='ACTIVE' or worker_check['expires_at']<=associated_at or
            worker_check['generation']!=acceptance_check['worker_generation'] or
            acceptance_check['host_generation']!=HOST_GENERATION):
            raise HTTPException(409,detail={'code':'PROFILE_ASSOCIATION_CHANGED','message':'Profile ownership, PASS evidence, worker or credentials changed during association'})
    return {'associated':True,'demo_pairing_id':x.demo_pairing_id,'demo_acceptance_id':x.demo_acceptance_id,
            'live_pairing_id':p['id'],'broker_brand':managed['broker_brand'],
            'demo_login':acceptance['login'],'demo_server':acceptance['server'],
            'demo_credential_generation':acceptance['credential_generation'],
            'live_login':live_account['login'],'live_server':live_account['server'],
            'live_credential_generation':managed['credential_generation'],
            'evidence_digest':acceptance['pass_evidence_digest'],
            'expires_at':iso(datetime.fromtimestamp(acceptance['expires_at'],timezone.utc)),
            'associated_at':iso(datetime.fromtimestamp(associated_at,timezone.utc))}

@app.get('/v1/app/demo-acceptance')
def demo_acceptance(authorization:str|None=Header(default=None)):
    p=auth(authorization,'app')
    with tx() as c:
        row=c.execute('''SELECT id,broker_brand,login,server,company,currency,account_mode,credential_generation,
                         pass_evidence_digest,accepted_at,expires_at,revoked
                         FROM demo_acceptances WHERE pairing_id=? ORDER BY accepted_at DESC LIMIT 1''',(p['id'],)).fetchone()
    if not row or row['revoked'] or row['expires_at']<=time.time():
        return {'accepted':False}
    return {'accepted':True,'pairing_id':p['id'],**dict(row)}

@app.delete('/v1/app/demo-acceptance')
def revoke_demo_acceptance(authorization:str|None=Header(default=None)):
    p=auth(authorization,'app')
    with tx(True) as c:
        c.execute("""UPDATE pairings SET live_authorized_until=NULL,live_authorized_login=NULL,live_authorized_server=NULL
                     WHERE id IN (SELECT pairing_id FROM live_profile_links WHERE demo_pairing_id=?)""",(p['id'],))
        c.execute('UPDATE live_profile_links SET revoked=1 WHERE demo_pairing_id=?',(p['id'],))
        c.execute('UPDATE live_challenges SET used=1 WHERE demo_pairing_id=? AND used=0',(p['id'],))
        c.execute('UPDATE demo_acceptances SET revoked=1 WHERE pairing_id=?',(p['id'],))
    return {'revoked':True}

@app.post('/v1/app/live-challenge')
def create_live_challenge(authorization:str|None=Header(default=None)):
    require_durable_execution()
    require_live_host_isolation(minimum_active_slots=2)
    p=auth(authorization,'app')
    managed,job,worker,beat=_current_ready_context(p,'LIVE')
    if managed['access_type']!='MASTER':
        raise HTTPException(409,'LIVE authorization requires a MASTER profile')
    require_live_risk_evidence(beat)
    with tx() as c:
        link=c.execute('SELECT * FROM live_profile_links WHERE pairing_id=? AND revoked=0',(p['id'],)).fetchone()
    if not link:
        raise HTTPException(409,detail={'code':'DEMO_PROFILE_ASSOCIATION_REQUIRED','message':'Associate a separately paired DEMO profile before LIVE authorization'})
    acceptance,demo_managed,demo_pair=_verified_live_demo_association(p,link,managed,beat)
    challenge_id=secrets.token_urlsafe(24); now_epoch=time.time(); expires=now_epoch+120
    with tx(True) as c:
        c.execute('UPDATE live_challenges SET used=1 WHERE pairing_id=? AND used=0',(p['id'],))
        c.execute('''INSERT INTO live_challenges
            (id,pairing_id,challenge_hash,demo_acceptance_id,demo_pairing_id,broker_brand,login,server,company,
             worker_id,worker_generation,host_generation,credential_generation,expires_at,used,created_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?)''',
            (challenge_id,p['id'],digest(challenge_id),acceptance['id'],link['demo_pairing_id'],managed['broker_brand'],
             beat['account']['login'],beat['account']['server'],beat['account']['company'],
             job['worker_id'],worker['generation'],HOST_GENERATION,managed['credential_generation'] or '',expires,now_epoch))
    return {
        'pairing_id':p['id'],
        'challenge_id':challenge_id,
        'expires_at':iso(datetime.fromtimestamp(expires,timezone.utc)),
        'typed_confirmation':f"ARM LIVE {managed['broker_brand']} {beat['account']['login']}",
        'broker_brand':managed['broker_brand'],
        'login':beat['account']['login'],
        'server':beat['account']['server'],
        'account_mode':managed['account_mode'],
        'credential_generation':managed['credential_generation'] or '',
    }

@app.post('/v1/app/live-authorization')
def authorize_live(x:LiveAuthorization,authorization:str|None=Header(default=None)):
    require_durable_execution()
    require_live_host_isolation(minimum_active_slots=2)
    p=auth(authorization,'app')
    with tx(True) as c:
        challenge=c.execute('SELECT * FROM live_challenges WHERE id=? AND challenge_hash=? AND pairing_id=?',
                            (x.challenge_id,digest(x.challenge_id),p['id'])).fetchone()
    if not challenge or challenge['used'] or challenge['expires_at']<=time.time():
        raise HTTPException(409,detail={'code':'LIVE_CHALLENGE_INVALID','message':'LIVE authorization challenge is invalid, expired or already used'})
    claims=_management_step_up_claims(x.step_up_assertion)
    if not p['owner_subject'] or claims['sub'] != p['owner_subject'] or claims['tenant_id'] != p['owner_tenant']:
        raise HTTPException(403,detail={'code':'MANAGEMENT_IDENTITY_MISMATCH','message':'The management identity is not bound to this pairing'})
    if any((
        claims['pairing_id'] != p['id'],
        claims['challenge_id'] != challenge['id'],
        claims['typed_confirmation'] != x.typed_confirmation,
        claims['broker_brand'] != challenge['broker_brand'],
        claims['login'] != challenge['login'],
        claims['server'] != challenge['server'],
        claims['account_mode'] != 'LIVE',
        claims['credential_generation'] != challenge['credential_generation'],
    )):
        with tx(True) as c:
            c.execute('UPDATE live_challenges SET used=1 WHERE id=? AND used=0',(challenge['id'],))
        raise HTTPException(409,detail={'code':'LIVE_CONTEXT_CHANGED','message':'The authorization context changed; obtain a new challenge'})
    managed,job,worker,beat=_current_ready_context(p,'LIVE')
    require_live_risk_evidence(beat)
    with tx() as c:
        link=c.execute('SELECT * FROM live_profile_links WHERE pairing_id=? AND revoked=0',(p['id'],)).fetchone()
    if (not link or link['demo_pairing_id'] != challenge['demo_pairing_id'] or
        link['demo_acceptance_id'] != challenge['demo_acceptance_id']):
        with tx(True) as c:
            c.execute('UPDATE live_challenges SET used=1 WHERE id=? AND used=0',(challenge['id'],))
        raise HTTPException(409,detail={'code':'DEMO_PROFILE_ASSOCIATION_CHANGED','message':'The DEMO association changed after the LIVE challenge was issued'})
    acceptance,demo_managed,demo_pair=_verified_live_demo_association(p,link,managed,beat)
    expected=f'ARM LIVE {managed["broker_brand"]} {beat["account"]["login"]}'
    if x.typed_confirmation != expected:
        raise HTTPException(422,'Typed LIVE confirmation does not match the current account')
    if any((
        managed['credential_generation'] != challenge['credential_generation'],
        job['worker_id'] != challenge['worker_id'],
        worker['generation'] != challenge['worker_generation'],
        HOST_GENERATION != challenge['host_generation'],
        beat['account'].get('login') != challenge['login'],
        beat['account'].get('server') != challenge['server'],
        beat['account'].get('company') != challenge['company'],
    )):
        with tx(True) as c:
            c.execute('UPDATE live_challenges SET used=1 WHERE id=? AND used=0',(challenge['id'],))
        raise HTTPException(409,detail={'code':'LIVE_CONTEXT_CHANGED','message':'The authorization context changed; obtain a new challenge'})
    with tx(True) as c:
        acceptance=c.execute('''SELECT * FROM demo_acceptances WHERE id=? AND pairing_id=?
                                 AND credential_generation=? AND revoked=0 AND expires_at>?''',
                              (challenge['demo_acceptance_id'],challenge['demo_pairing_id'],
                               demo_managed['credential_generation'],time.time())).fetchone()
        if not acceptance:
            raise HTTPException(409,detail={'code':'DEMO_ACCEPTANCE_REQUIRED','message':'DEMO acceptance is missing or expired'})
        changed=c.execute('UPDATE live_challenges SET used=1 WHERE id=? AND used=0 AND expires_at>?',(x.challenge_id,time.time()))
        if changed.rowcount!=1:
            raise HTTPException(409,detail={'code':'LIVE_CHALLENGE_REPLAY','message':'LIVE authorization challenge was already consumed'})
        until=time.time()+900
        account=beat['account']
        c.execute('UPDATE pairings SET live_authorized_until=?,live_authorized_login=?,live_authorized_server=? WHERE id=?',
                  (until,account['login'],account['server'],p['id']))
    return {'authorized':True,'expires_at':iso(datetime.fromtimestamp(until,timezone.utc)),'duration_seconds':900}
@app.delete('/v1/app/live-authorization')
def revoke_live_authorization(authorization:str|None=Header(default=None)):
    p=auth(authorization,'app')
    with tx(True) as c:
        c.execute('UPDATE pairings SET live_authorized_until=NULL,live_authorized_login=NULL,live_authorized_server=NULL WHERE id=?',(p['id'],))
        c.execute('UPDATE live_challenges SET used=1 WHERE pairing_id=? AND used=0',(p['id'],))
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
        _require_host_accepting_commands(c)
        pending_orders=c.execute("SELECT ack FROM commands WHERE pairing_id=? AND action='PLACE_MARKET_ORDER' AND expires_at>?",(p['id'],time.time())).fetchall()
        for pending in pending_orders:
            try: pending_outcome=json.loads(pending['ack']).get('outcome') if pending['ack'] else None
            except Exception: pending_outcome=None
            if pending_outcome!='REJECTED':
                raise HTTPException(409,detail={'code':'MARKET_ORDER_ALREADY_PENDING','message':'A recent market-order request is still awaiting broker/heartbeat reconciliation; do not submit another'})
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
            if kind=='POSITION':
                stop_rows=c.execute("SELECT config,ack,expires_at FROM commands WHERE pairing_id=? AND action='TIGHTEN_POSITION_SL'",(p['id'],)).fetchall()
                for stop_row in stop_rows:
                    try:
                        stop_cfg=json.loads(stop_row['config'])
                        stop_ack=json.loads(stop_row['ack']) if stop_row['ack'] else None
                        stop_outcome=stop_ack.get('outcome') if isinstance(stop_ack,dict) else None
                    except Exception:
                        stop_cfg={}; stop_outcome=None
                    same_position=stop_cfg.get('position_id')==target_id
                    queued_unexpired=(stop_row['ack'] is None and stop_row['expires_at'] is not None and stop_row['expires_at']>time.time())
                    unresolved_unknown=(stop_row['ack'] is not None and stop_outcome not in ('APPLIED','REJECTED'))
                    if same_position and (queued_unexpired or unresolved_unknown):
                        raise HTTPException(409,'A position stop-tightening request is unresolved for this exact position')
        c.execute('INSERT INTO commands(pairing_id,request_id,action,config,expires_at,request_config) VALUES(?,?,?,?,?,?)',
                  (p['id'],x.request_id,x.action,serialized,expiry,raw_config))
        command_id=c.execute('SELECT last_insert_rowid()').fetchone()[0]
        c.executemany('INSERT INTO command_items(command_id,ticket,target_id,target_kind,target_volume) VALUES(?,?,?,?,?)',
                      [(command_id,ticket,target,kind,volume) for ticket,target,kind,volume in targets])
        if confirmation:
            updated=c.execute('UPDATE close_all_confirmations SET used=1 WHERE token_hash=? AND used=0 AND expires_at>?',(confirmation[0],time.time()))
            if updated.rowcount!=1: raise HTTPException(409,'Close-all confirmation was already used or expired')
    return {'id':command_id,'request_id':x.request_id,'action':x.action,'config':config,'status':'queued'}


def issue_market_order_command(x,p):
    """Queue one account-bound phone market order after fresh broker-side checks.

    The phone supplies a requested size bounded by the fresh EA effective lot
    ceiling. The EA recalculates the exact risk/cost-safe size at execution and
    rejects a request that exceeds that live calculation.
    """
    try:
        raw_config=json.dumps(x.config,sort_keys=True,separators=(',',':'),allow_nan=False)
    except (TypeError,ValueError):
        raise HTTPException(422,'Invalid market-order config')
    with tx() as c:
        prior=c.execute('SELECT * FROM commands WHERE pairing_id=? AND request_id=?',(p['id'],x.request_id)).fetchone()
    if prior:
        if prior['action']!=x.action or prior['request_config']!=raw_config:
            raise HTTPException(409,'request_id was already used for a different command payload')
        return {'id':prior['id'],'request_id':prior['request_id'],'action':prior['action'],
                'config':json.loads(prior['config']),'status':'acknowledged' if prior['ack'] else 'queued'}

    require_durable_execution()
    managed,beat=destructive_context(p)
    if managed['account_mode']=='LIVE':
        # LIVE can execute on an EPHEMERAL Codespaces slot after owner risk acceptance.
        require_live_host_isolation(minimum_active_slots=1)
    elif managed['account_mode']!='DEMO':
        raise HTTPException(409,detail={'code':'ACCOUNT_MODE_NOT_SUPPORTED','message':'Phone market orders require a verified DEMO profile or separately authorized LIVE profile'})
    require_terminal_trade_outcome_resolved(beat)
    cfg=x.config
    account=beat.get('account') or {}
    if any((cfg.get('account_login')!=account.get('login'),
            cfg.get('account_server')!=account.get('server'),
            cfg.get('account_mode')!=managed['account_mode'])):
        raise HTTPException(409,detail={'code':'MARKET_ORDER_ACCOUNT_MISMATCH','message':'Order profile does not match the fresh authenticated MT5 account'})
    if managed['account_mode']=='LIVE' and not live_authorization_valid(p,managed,beat):
        raise HTTPException(409,detail={'code':'LIVE_AUTHORIZATION_REQUIRED','message':'A current account-bound LIVE lease is required before a real-money order'})
    risk=live_risk_controls(beat)
    if not risk['authorized']:
        raise HTTPException(409,detail={'code':'MARKET_ORDER_RISK_EVIDENCE_INVALID','message':'Fresh EA daily-risk evidence and configured risk caps are required'})
    symbol=cfg.get('symbol')
    side=cfg.get('side')
    if not isinstance(symbol,str) or not re.fullmatch(r'[A-Za-z0-9._-]{1,32}',symbol):
        raise HTTPException(422,'A valid broker symbol is required')
    if side not in ('BUY','SELL'):
        raise HTTPException(422,'Market-order side must be BUY or SELL')
    def finite_positive(value):
        return (isinstance(value,(int,float)) and not isinstance(value,bool)
                and math.isfinite(float(value)) and float(value)>0)
    risk_percent=cfg.get('risk_percent')
    stop_loss=cfg.get('stop_loss')
    take_profit=cfg.get('take_profit')
    expected_bid=cfg.get('expected_bid')
    expected_ask=cfg.get('expected_ask')
    quote_timestamp=cfg.get('quote_timestamp_ms')
    if not finite_positive(risk_percent) or float(risk_percent)>min(1.0,float(risk['risk_percent'])):
        raise HTTPException(422,'Requested per-trade risk must be positive and no higher than the current EA risk cap (maximum 1%)')
    if not all(finite_positive(v) for v in (stop_loss,take_profit,expected_bid,expected_ask)):
        raise HTTPException(422,'Fresh quote, stop-loss and take-profit prices must be finite and positive')
    if isinstance(quote_timestamp,bool) or not isinstance(quote_timestamp,int) or quote_timestamp<=0:
        raise HTTPException(422,'A canonical MT5 quote timestamp is required')

    config=beat.get('configuration') if isinstance(beat.get('configuration'),dict) else {}
    requested_volume=cfg.get('requested_volume')
    effective_max_volume=config.get('effective_max_volume')
    if not finite_positive(requested_volume) or float(requested_volume)<0.01:
        raise HTTPException(422,detail={'code':'MARKET_ORDER_VOLUME_INVALID',
            'message':'Requested volume must be finite and at least 0.01 lots'})
    if not finite_positive(effective_max_volume) or float(effective_max_volume)<0.01:
        raise HTTPException(409,detail={'code':'MARKET_ORDER_EA_VOLUME_LIMIT_UNAVAILABLE',
            'message':'A fresh EA effective volume ceiling is required before requesting a market order'})
    if float(requested_volume)>float(effective_max_volume)+1e-9:
        raise HTTPException(422,detail={'code':'MARKET_ORDER_VOLUME_CAP_EXCEEDED',
            'message':'Requested volume exceeds the current effective volume ceiling reported by the MT5 EA'})
    configured_symbols=config.get('symbols')
    # The current EA heartbeat publishes one news verdict at a time, for the
    # first configured symbol. Do not trade a different symbol against that
    # unrelated verdict; per-symbol news telemetry is a separate required extension.
    if not isinstance(configured_symbols,list) or not configured_symbols or symbol!=configured_symbols[0]:
        raise HTTPException(409,detail={'code':'MARKET_ORDER_NEWS_SYMBOL_UNVERIFIED',
            'message':'Market orders are limited to the first MT5 EA-configured symbol until per-symbol news telemetry is available'})
    if not heartbeat_news_clear(beat,[symbol]):
        raise HTTPException(409,detail={'code':'MARKET_ORDER_NEWS_NOT_CLEAR','message':'Fresh CLEAR economic-calendar evidence is required for this exact symbol'})

    quotes=fresh_terminal_broker_quotes(beat)
    matches=[q for q in quotes if q['symbol']==symbol and q['timestamp_ms']==quote_timestamp]
    if len(matches)!=1:
        raise HTTPException(409,detail={'code':'MARKET_ORDER_QUOTE_CHANGED','message':'The submitted quote is no longer the exact fresh quote reported by this MT5 terminal'})
    quote=matches[0]
    tolerance=0.5*(10.0**(-quote['digits']))
    if (abs(float(expected_bid)-quote['bid'])>tolerance or
        abs(float(expected_ask)-quote['ask'])>tolerance or quote['age_ms']>BROKER_QUOTE_MAX_AGE_MS):
        raise HTTPException(409,detail={'code':'MARKET_ORDER_QUOTE_CHANGED','message':'MT5 bid/ask has changed or is stale; refresh the quote before submitting'})
    if side=='BUY' and not (float(stop_loss)<quote['ask']<float(take_profit)):
        raise HTTPException(422,'BUY protection must satisfy stop-loss < current ask < take-profit')
    if side=='SELL' and not (float(take_profit)<quote['bid']<float(stop_loss)):
        raise HTTPException(422,'SELL protection must satisfy take-profit < current bid < stop-loss')

    positions=beat.get('open_positions')
    active_orders=beat.get('active_orders')
    if not isinstance(positions,list) or not isinstance(active_orders,list):
        raise HTTPException(409,'Complete broker position and active-order snapshots are required before a new order')
    max_concurrent=int(risk['max_concurrent_positions'])
    if len(positions)+len(active_orders)>=max_concurrent:
        raise HTTPException(409,detail={'code':'MARKET_ORDER_POSITION_LIMIT','message':'The configured maximum concurrent-position and pending-order limit is already reached'})
    now_epoch=time.time()
    expiry=now_epoch+20
    queued_config={
        'account_login':account['login'],'account_server':account['server'],'account_mode':managed['account_mode'],
        'symbol':symbol,'side':side,'risk_percent':float(risk_percent),
        'requested_volume':float(requested_volume),
        'stop_loss':float(stop_loss),'take_profit':float(take_profit),
        'expected_bid':quote['bid'],'expected_ask':quote['ask'],
        'quote_timestamp_ms':quote['timestamp_ms'],'expires_at_epoch':int(expiry),
    }
    serialized=json.dumps(queued_config,separators=(',',':'),allow_nan=False)
    with tx(True) as c:
        _require_host_accepting_commands(c)
        pending_orders=c.execute("SELECT config,ack,expires_at FROM commands WHERE pairing_id=? AND action='PLACE_MARKET_ORDER'",(p['id'],)).fetchall()
        for pending in pending_orders:
            try:
                pending_config=json.loads(pending['config'])
                pending_ack=json.loads(pending['ack']) if pending['ack'] else None
                pending_outcome=pending_ack.get('outcome') if isinstance(pending_ack,dict) else None
            except Exception:
                pending_config={}; pending_outcome=None
            same_symbol=pending_config.get('symbol')==symbol
            expiry_active=(pending['expires_at'] is not None and pending['expires_at']>time.time())
            queued_unexpired=(pending['ack'] is None and expiry_active)
            unresolved_unknown=(pending['ack'] is not None and pending_outcome not in ('APPLIED','REJECTED'))
            just_applied=(pending['ack'] is not None and pending_outcome=='APPLIED' and expiry_active)
            if same_symbol and (queued_unexpired or unresolved_unknown or just_applied):
                raise HTTPException(409,detail={'code':'MARKET_ORDER_ALREADY_PENDING','message':'A market-order command for this symbol is queued or has an unresolved broker result; do not submit another'})
        concurrent=c.execute('SELECT * FROM commands WHERE pairing_id=? AND request_id=?',(p['id'],x.request_id)).fetchone()
        if concurrent:
            if concurrent['action']!=x.action or concurrent['request_config']!=raw_config:
                raise HTTPException(409,'request_id was already used for a different command payload')
            return {'id':concurrent['id'],'request_id':concurrent['request_id'],'action':concurrent['action'],
                    'config':json.loads(concurrent['config']),'status':'acknowledged' if concurrent['ack'] else 'queued'}
        c.execute('''INSERT INTO commands(pairing_id,request_id,action,config,expires_at,request_config,host_generation)
                     VALUES(?,?,?,?,?,?,?)''',
                  (p['id'],x.request_id,x.action,serialized,expiry,raw_config,HOST_GENERATION))
        command_id=c.execute('SELECT last_insert_rowid()').fetchone()[0]
    return {'id':command_id,'request_id':x.request_id,'action':x.action,'config':queued_config,'status':'queued'}



def issue_position_stop_command(x,p):
    """Queue a stop-loss-only change that strictly reduces or preserves account risk."""
    try:
        raw_config=json.dumps(x.config,sort_keys=True,separators=(',',':'),allow_nan=False)
    except (TypeError,ValueError):
        raise HTTPException(422,'Invalid position stop config')
    with tx() as c:
        prior=c.execute('SELECT * FROM commands WHERE pairing_id=? AND request_id=?',(p['id'],x.request_id)).fetchone()
    if prior:
        if prior['action']!=x.action or prior['request_config']!=raw_config:
            raise HTTPException(409,'request_id was already used for a different command payload')
        return {'id':prior['id'],'request_id':prior['request_id'],'action':prior['action'],
                'config':json.loads(prior['config']),'status':'acknowledged' if prior['ack'] else 'queued'}

    require_durable_execution()
    managed,beat=destructive_context(p)
    if managed['account_mode']=='LIVE':
        require_live_host_isolation(minimum_active_slots=1)
    require_terminal_trade_outcome_resolved(beat)
    if managed['account_mode']=='LIVE' and not live_authorization_valid(p,managed,beat):
        raise HTTPException(409,detail={'code':'LIVE_AUTHORIZATION_REQUIRED','message':'A current account-bound LIVE lease is required before changing a real-money position stop'})
    cfg=x.config
    account=beat.get('account') or {}
    if any((cfg.get('account_login')!=account.get('login'),
            cfg.get('account_server')!=account.get('server'),
            cfg.get('account_mode')!=managed['account_mode'])):
        raise HTTPException(409,detail={'code':'POSITION_STOP_ACCOUNT_MISMATCH','message':'Protection request does not match the current MT5 account'})
    ticket=cfg.get('ticket')
    if not isinstance(ticket,str) or snapshot_ticket(ticket)!=ticket:
        raise HTTPException(422,'A canonical positive MT5 position ticket is required')
    def finite_number(value, allow_zero=False):
        return (isinstance(value,(int,float)) and not isinstance(value,bool)
                and math.isfinite(float(value)) and (float(value)>=0 if allow_zero else float(value)>0))
    expected_stop=cfg.get('expected_stop_loss')
    new_stop=cfg.get('new_stop_loss')
    quote_timestamp=cfg.get('quote_timestamp_ms')
    expected_bid=cfg.get('expected_bid')
    expected_ask=cfg.get('expected_ask')
    if (not finite_number(expected_stop,True) or not finite_number(new_stop) or
        not finite_number(expected_bid) or not finite_number(expected_ask) or
        isinstance(quote_timestamp,bool) or not isinstance(quote_timestamp,int) or quote_timestamp<=0):
        raise HTTPException(422,'Stop price and current broker quote must be finite and positive')
    positions=beat.get('open_positions')
    if not isinstance(positions,list):
        raise HTTPException(409,'A complete broker position snapshot is required before editing protection')
    matches=[item for item in positions if isinstance(item,dict) and snapshot_ticket(item.get('ticket'))==ticket]
    if len(matches)!=1:
        raise HTTPException(409,detail={'code':'POSITION_STOP_TICKET_CHANGED','message':'The exact open position ticket is no longer present'})
    position=matches[0]
    symbol=position.get('symbol')
    direction=str(position.get('direction') or position.get('side') or position.get('type','')).upper()
    current_stop=position.get('stop_loss')
    if (not isinstance(symbol,str) or not symbol or direction not in ('BUY','SELL') or
        not finite_number(current_stop,True)):
        raise HTTPException(409,'The selected MT5 position snapshot is incomplete')
    if cfg.get('symbol')!=symbol or str(cfg.get('direction','')).upper()!=direction:
        raise HTTPException(409,detail={'code':'POSITION_STOP_IDENTITY_CHANGED','message':'The selected position symbol or direction changed; refresh before editing'})
    quotes=fresh_terminal_broker_quotes(beat)
    quote_matches=[q for q in quotes if q['symbol']==symbol and q['timestamp_ms']==quote_timestamp]
    if len(quote_matches)!=1:
        raise HTTPException(409,detail={'code':'POSITION_STOP_QUOTE_CHANGED','message':'The selected position quote has changed or is stale; refresh before editing SL'})
    quote=quote_matches[0]
    tolerance=0.5*(10.0**(-quote['digits']))
    if (abs(float(expected_stop)-float(current_stop))>tolerance or
        abs(float(expected_bid)-quote['bid'])>tolerance or
        abs(float(expected_ask)-quote['ask'])>tolerance or quote['age_ms']>BROKER_QUOTE_MAX_AGE_MS):
        raise HTTPException(409,detail={'code':'POSITION_STOP_SNAPSHOT_CHANGED','message':'Position SL or broker quote changed; refresh before retrying'})
    if direction=='BUY':
        tighter=float(expected_stop)<=0 or float(new_stop)>float(expected_stop)+tolerance
        price_valid=float(new_stop)<quote['bid']
    else:
        tighter=float(expected_stop)<=0 or float(new_stop)<float(expected_stop)-tolerance
        price_valid=float(new_stop)>quote['ask']
    if not tighter or not price_valid:
        raise HTTPException(422,detail={'code':'POSITION_STOP_MUST_TIGHTEN','message':'The new stop must strictly reduce or establish protection and stay on the broker-valid side of price; widening/removing a stop is blocked'})
    position_id=snapshot_ticket(position.get('position_id') or ticket)
    if not position_id:
        raise HTTPException(409,'The selected position identity is unavailable')
    if cfg.get('position_id')!=position_id:
        raise HTTPException(409,detail={'code':'POSITION_STOP_IDENTITY_CHANGED','message':'The selected broker position identifier changed; refresh before editing'})
    now_epoch=time.time()
    expiry=now_epoch+20
    queued_config={
        'account_login':account['login'],'account_server':account['server'],'account_mode':managed['account_mode'],
        'ticket':ticket,'position_id':position_id,'symbol':symbol,'direction':direction,
        'expected_stop_loss':float(current_stop),'new_stop_loss':float(new_stop),
        'expected_bid':quote['bid'],'expected_ask':quote['ask'],
        'quote_timestamp_ms':quote['timestamp_ms'],'expires_at_epoch':int(expiry),
    }
    serialized=json.dumps(queued_config,separators=(',',':'),allow_nan=False)
    with tx(True) as c:
        _require_host_accepting_commands(c)
        unresolved=c.execute('''SELECT 1 FROM command_items i JOIN commands cmd ON cmd.id=i.command_id
            WHERE cmd.pairing_id=? AND i.target_id=? AND i.operational_status IN ('PENDING','PARTIAL')
            AND (cmd.ack IS NOT NULL OR cmd.expires_at>?) LIMIT 1''',(p['id'],position_id,time.time())).fetchone()
        if unresolved:
            raise HTTPException(409,'A close or other unresolved operation is already queued for this position')
        pending_stops=c.execute("SELECT config,ack,expires_at FROM commands WHERE pairing_id=? AND action='TIGHTEN_POSITION_SL'",(p['id'],)).fetchall()
        for pending in pending_stops:
            try:
                pending_config=json.loads(pending['config'])
                pending_ack=json.loads(pending['ack']) if pending['ack'] else None
                ack_outcome=pending_ack.get('outcome') if isinstance(pending_ack,dict) else None
                pending_ticket=pending_config.get('ticket')
            except Exception:
                ack_outcome=None; pending_ticket=''
            queued_unexpired=(pending['ack'] is None and pending['expires_at'] is not None and pending['expires_at']>time.time())
            unresolved_unknown=(pending['ack'] is not None and ack_outcome not in ('APPLIED','REJECTED'))
            if pending_ticket==ticket and (queued_unexpired or unresolved_unknown):
                raise HTTPException(409,detail={'code':'POSITION_STOP_ALREADY_PENDING','message':'A stop-tightening request for this exact position is still unresolved; do not submit another'})
        concurrent=c.execute('SELECT * FROM commands WHERE pairing_id=? AND request_id=?',(p['id'],x.request_id)).fetchone()
        if concurrent:
            if concurrent['action']!=x.action or concurrent['request_config']!=raw_config:
                raise HTTPException(409,'request_id was already used for a different command payload')
            return {'id':concurrent['id'],'request_id':concurrent['request_id'],'action':concurrent['action'],
                    'config':json.loads(concurrent['config']),'status':'acknowledged' if concurrent['ack'] else 'queued'}
        c.execute('''INSERT INTO commands(pairing_id,request_id,action,config,expires_at,request_config,host_generation)
                     VALUES(?,?,?,?,?,?,?)''',
                  (p['id'],x.request_id,x.action,serialized,expiry,raw_config,HOST_GENERATION))
        command_id=c.execute('SELECT last_insert_rowid()').fetchone()[0]
    return {'id':command_id,'request_id':x.request_id,'action':x.action,'config':queued_config,'status':'queued'}


@app.post('/v1/app/commands',status_code=202)
def command(x:Command,authorization:str|None=Header(default=None)):
    p=auth(authorization,'app')
    with tx() as c: _require_host_accepting_commands(c)
    check_json(x.config)
    linux_host_report()
    if host_lifecycle_state()['host_shutdown_pending'] and x.action in ('START','RESUME'):
        raise HTTPException(409,detail={'code':'HOST_SHUTDOWN_PENDING','message':'The Codespaces hard lifetime is approaching; new entries and resumes are blocked while the safety stop is active'})
    # A non-durable diagnostic deployment may still deliver a narrowly scoped
    # safety stop to a terminal already paired in the current process. It must
    # never create a new START/RESUME or a destructive trading command.
    safety_actions = {'STOP','PAUSE','EMERGENCY_STOP','SET_ANALYZER_CHART'}
    if not durable_execution_enabled() and x.action not in safety_actions:
        raise HTTPException(503, detail={'code':'NON_DURABLE_STORAGE','message':'Only STOP, PAUSE and EMERGENCY_STOP are available on non-durable storage'})
    try: uuid.UUID(x.request_id)
    except ValueError: raise HTTPException(422,'request_id must be a UUID')
    if x.action in DESTRUCTIVE_ACTIONS: return issue_destructive_command(x,p)
    if x.action in OPENING_ACTIONS: return issue_market_order_command(x,p)
    if x.action in POSITION_MANAGEMENT_ACTIONS: return issue_position_stop_command(x,p)
    if x.action not in ('START','STOP','PAUSE','RESUME','EMERGENCY_STOP','SET_ANALYZER_CHART'): raise HTTPException(422,'Invalid action')
    with tx() as c:
        m=c.execute('SELECT access_type,revoked,account_mode FROM managed_accounts WHERE pairing_id=?',(p['id'],)).fetchone()
    if x.action in ('START','RESUME'):
        if not m: raise HTTPException(409,'Managed account provisioning is required before trading can be enabled')
        beat=fresh_heartbeat(p)
        with tx() as c: job=c.execute("SELECT status FROM worker_jobs WHERE pairing_id=? ORDER BY created DESC LIMIT 1",(p['id'],)).fetchone()
        account=beat.get('account',{}) if beat else {}
        live_ok=(m['account_mode']!='LIVE' or live_authorization_valid(p,m,beat))
        symbols=resolve_start_news_symbols(x.config,beat)
        if m['revoked'] or m['access_type']!='MASTER' or m['account_mode'] not in ('DEMO','LIVE') or not execution_state_eligible(beat) or symbols is None or not heartbeat_news_clear(beat,symbols) or not beat.get('terminal_connected') or not beat.get('trade_allowed') or not terminal_identity_matches(p,m['account_mode'],beat) or not job or job['status']!='READY' or not live_ok: raise HTTPException(409,'Managed execution is not authorized to start or resume: profile, MASTER access, eligible EA state, verified identity/mode, fresh CLEAR news verdict, verified identity/mode, fresh heartbeat, worker readiness, trade permission and current LIVE authorization are required')
    serialized=json.dumps(x.config,separators=(',',':'),allow_nan=False)
    with tx(True) as c:
        _require_host_accepting_commands(c)
        c.execute('INSERT OR IGNORE INTO commands(pairing_id,request_id,action,config,host_generation) VALUES(?,?,?,?,?)',(p['id'],x.request_id,x.action,serialized,HOST_GENERATION))
        r=c.execute('SELECT * FROM commands WHERE pairing_id=? AND request_id=?',(p['id'],x.request_id)).fetchone()
        if r['action']!=x.action or r['config']!=serialized: raise HTTPException(409,'request_id was already used for a different command payload')
    return {'id':r['id'],'request_id':r['request_id'],'action':r['action'],'config':json.loads(r['config']),'status':'acknowledged' if r['ack'] else 'queued'}
@app.get('/v1/terminal/commands')
def poll(cursor:int=Query(default=0,ge=0),authorization:str|None=Header(default=None)):
    p=auth(authorization,'terminal')
    with tx() as c:
        if HOST_PROFILE=='EPHEMERAL':
            account=c.execute('SELECT account_mode FROM managed_accounts WHERE pairing_id=?',(p['id'],)).fetchone()
            mode=account['account_mode'] if account else 'UNVERIFIED'
            blocked=c.execute("SELECT id FROM commands WHERE pairing_id=? AND action IN ('START','RESUME') AND ack IS NULL AND (host_generation IS NULL OR host_generation!=?)",(p['id'],HOST_GENERATION)).fetchall()
            for row in blocked:
                c.execute('UPDATE commands SET ack=? WHERE id=? AND ack IS NULL',
                          (json.dumps({'outcome':'REJECTED','state':'STOPPED','message':'EPHEMERAL_HOST_RESTART_RECONCILE_REQUIRED','code':'EPHEMERAL_HOST_RESTART_RECONCILE_REQUIRED'},separators=(',',':')),row['id']))
        if durable_execution_enabled():
            rows=c.execute('SELECT * FROM commands WHERE pairing_id=? AND (id>? OR ack IS NULL) AND (expires_at IS NULL OR expires_at>?) ORDER BY id',(p['id'],cursor,time.time())).fetchall()
        else:
            # Never deliver a previously queued START/RESUME from an ephemeral
            # database. Keep emergency stop controls available as a safe fallback.
            rows=c.execute("SELECT * FROM commands WHERE pairing_id=? AND action IN ('STOP','PAUSE','EMERGENCY_STOP','SET_ANALYZER_CHART') AND (id>? OR ack IS NULL) AND (expires_at IS NULL OR expires_at>?) ORDER BY id",(p['id'],cursor,time.time())).fetchall()
        if HOST_PROFILE=='EPHEMERAL':
            rows=[r for r in rows if r['action'] not in ('START','RESUME') or
                  (r['host_generation']==HOST_GENERATION and mode in ('DEMO','LIVE'))]
    commands=[{'id':r['id'],'request_id':r['request_id'],'action':r['action'],'config':json.loads(r['config']),**({'expires_at':r['config'] and json.loads(r['config']).get('expires_at')} if r['expires_at'] is not None else {})} for r in rows]
    return {'cursor':max([cursor]+[r['id'] for r in rows]),'commands':commands}
@app.post('/v1/terminal/commands/{command_id}/ack')
def acknowledge(command_id:int,x:Ack,authorization:str|None=Header(default=None)):
    p=auth(authorization,'terminal')
    if x.outcome not in ('APPLIED','REJECTED','UNKNOWN') or x.state not in ('READY','RUNNING','PAUSED','STOPPED','ERROR'): raise HTTPException(422,'Invalid acknowledgement')
    with tx(True) as c:
        r=c.execute('SELECT * FROM commands WHERE id=? AND pairing_id=?',(command_id,p['id'])).fetchone()
        if not r: raise HTTPException(404,'Command not found')
        if not durable_execution_enabled() and r['action'] not in ('STOP','PAUSE','EMERGENCY_STOP','SET_ANALYZER_CHART'):
            raise HTTPException(503, detail={'code':'NON_DURABLE_STORAGE','message':'Only safety-stop acknowledgements are accepted on non-durable storage'})
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
        if x.outcome=='APPLIED' and expected_state is not None and x.state!=expected_state:
            raise HTTPException(422,'Applied acknowledgement state does not match command action')
        prior_ack=json.loads(r['ack']) if r['ack'] else None
        if prior_ack:
            prior_outcome=prior_ack.get('outcome')
            if prior_outcome in ('APPLIED','REJECTED'):
                if x.outcome!=prior_outcome:
                    raise HTTPException(409,'Final command acknowledgement cannot be changed')
                return {'id':command_id,'acknowledged':True}
            if prior_outcome=='UNKNOWN':
                if x.outcome=='UNKNOWN':
                    return {'id':command_id,'acknowledged':True}
                if x.outcome not in ('APPLIED','REJECTED'):
                    raise HTTPException(409,'UNKNOWN acknowledgement can only be upgraded to a final result')
            else:
                raise HTTPException(409,'Stored command acknowledgement is invalid; manual reconciliation is required')
        ack=json.dumps({'outcome':x.outcome,'state':x.state,'message':x.message},separators=(',',':'))
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
        managed=c.execute('SELECT * FROM managed_accounts WHERE pairing_id=?',(p['id'],)).fetchone()
        job=c.execute("SELECT * FROM worker_jobs WHERE pairing_id=? ORDER BY created DESC LIMIT 1",(p['id'],)).fetchone()
        if (managed and not managed['revoked'] and managed['account_mode']=='LIVE'
            and not live_risk_controls(payload)['authorized']):
            # Risk evidence loss or a breached EA daily-loss guard immediately removes the lease.
            c.execute('UPDATE pairings SET live_authorized_until=NULL,live_authorized_login=NULL,live_authorized_server=NULL WHERE id=?',(p['id'],))
            c.execute('UPDATE live_challenges SET used=1 WHERE pairing_id=? AND used=0',(p['id'],))
        if (managed and not managed['revoked'] and managed['access_type']=='MASTER' and managed['account_mode']==x.account.account_mode
            and broker_company_matches(managed['broker_brand'],x.account.company)
            and job and job['status']=='READY' and x.terminal_connected and terminal_identity_matches(p,managed['account_mode'],payload)):
            received=time.time()
            ingest_trade_ledger(c,p['id'],x.account.account_mode,payload,received)
            reconcile_command_items(c,p['id'],payload,seq)
            worker=c.execute('SELECT generation,status,expires_at FROM worker_identities WHERE worker_id=?',(job['worker_id'],)).fetchone() if job['worker_id'] else None
            if (x.account.account_mode=='DEMO' and worker and worker['status']=='ACTIVE' and
                worker['expires_at']>received and x.state in ('READY','RUNNING') and x.trade_allowed and
                managed['credential_generation']):
                record_demo_soak_heartbeat(c,p,managed,job,worker,payload,received,dt.astimezone(timezone.utc).date().isoformat())
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
    # Disable the old terminal immediately, but retain the app token for one
    # read-only revocation-status route until the worker proves process death.
    p=auth(authorization,'app',allow_revoked=True)
    with tx(True) as c:
        c.execute('UPDATE managed_accounts SET revoked=1,login_enc=?,server_enc=?,password_enc=? WHERE pairing_id=?',('', '', '', p['id']))
        existing=c.execute("SELECT id,status FROM worker_jobs WHERE pairing_id=? AND kind='REVOKE' ORDER BY created DESC LIMIT 1",(p['id'],)).fetchone()
        if existing and existing['status'] in ('QUEUED','LEASED','PROVISIONING','AUTHENTICATING','REVOKED'):
            job_id=existing['id']
        else:
            job_id=str(uuid.uuid4())
            c.execute("INSERT INTO worker_jobs(id,pairing_id,kind,status,created) VALUES(?,?,'REVOKE','QUEUED',?)",(job_id,p['id'],time.time()))
        c.execute('UPDATE pairings SET revocation_pending=1,terminal_hash=NULL,consumed=1,heartbeat=NULL,live_authorized_until=NULL,live_authorized_login=NULL,live_authorized_server=NULL WHERE id=?',(p['id'],))
        c.execute('DELETE FROM commands WHERE pairing_id=?',(p['id'],))
    return {'revoked':True,'deprovision_queued':True,'job_id':job_id}


@app.get('/v1/app/pairing/revocation-status')
def pairing_revocation_status(authorization:str|None=Header(default=None)):
    p=auth(authorization,'app',allow_revoked=True)
    with tx() as c:
        job=c.execute("SELECT id,status,error_code FROM worker_jobs WHERE pairing_id=? AND kind='REVOKE' ORDER BY created DESC LIMIT 1",(p['id'],)).fetchone()
    if not job:
        return {'status':'NOT_REQUESTED','terminal_stopped':False}
    status=job['status']
    return {
        'job_id':job['id'],
        'status':status,
        'terminal_stopped':status=='REVOKED',
        'error_code':job['error_code'] or '',
    }

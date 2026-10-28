import os, time, uuid
os.environ['ALLOW_LOOPBACK_HTTP']='true'
os.environ['BRIDGE_DB_PATH']='/tmp/destinedfx-bridge-test.sqlite3'
try: os.unlink(os.environ['BRIDGE_DB_PATH'])
except FileNotFoundError: pass
from fastapi.testclient import TestClient
import app
client=TestClient(app.app, base_url='https://testserver')
ACCOUNT={'login':'123456','server':'Demo-Server','company':'Demo Broker','currency':'USD','account_mode':'DEMO'}
def pair():
    r=client.post('/v1/pairings'); assert r.status_code==201; return r.json()
def claimed(p):
    r=client.post('/v1/terminal/claim',json={'pairing_code':p['pairing_code'],'terminal_instance_id':'test-terminal-1','account':ACCOUNT}); assert r.status_code==200; return r.json()
def heartbeat_data(**kwargs):
    x={'state':'READY','timestamp':app.iso(),'terminal_connected':True,'trade_allowed':True,'account':{**ACCOUNT,'balance':10000,'equity':9990,'margin':100,'free_margin':9890,'margin_level':9990,'leverage':100},'configuration':{'strategy':'demo'},'last_signal':None,'last_error':'','open_positions':[],'closed_deals':[]}; x.update(kwargs); return x
def test_pairing_single_use_and_token_auth_boundaries():
    p=pair(); assert client.get('/v1/app/state',headers={'Authorization':'Bearer '+p['app_token']}).json()['pairing_status']=='PAIRING'; t=claimed(p)
    assert client.post('/v1/terminal/claim',json={'pairing_code':p['pairing_code'],'terminal_instance_id':'another','account':ACCOUNT}).status_code==401
    assert client.get('/v1/app/state').status_code==401
    assert client.get('/v1/app/state',headers={'Authorization':'Bearer wrong'}).status_code==401
    assert client.get('/v1/app/state',headers={'Authorization':'Bearer '+t['terminal_token']}).status_code==401
    assert client.get('/v1/terminal/commands',headers={'Authorization':'Bearer '+p['app_token']}).status_code==401
    assert client.get('/v1/terminal/commands',headers={'Authorization':'Bearer '+t['terminal_token']}).status_code==200

def test_pairing_expiry():
    p=pair()
    with app.tx(True) as c: c.execute('UPDATE pairings SET expires=? WHERE id=?',(time.time()-1,p['pairing_id']))
    assert client.post('/v1/terminal/claim',json={'pairing_code':p['pairing_code'],'terminal_instance_id':'x','account':ACCOUNT}).status_code==401

def test_duplicate_request_ids_delivery_ack_and_replay():
    p=pair(); t=claimed(p); ah={'Authorization':'Bearer '+p['app_token']}; th={'Authorization':'Bearer '+t['terminal_token']}
    req={'request_id':str(uuid.uuid4()),'action':'STOP','config':{}}
    a=client.post('/v1/app/commands',json=req,headers=ah); b=client.post('/v1/app/commands',json=req,headers=ah)
    assert a.status_code==202 and a.json()['id']==b.json()['id']
    changed={**req,'action':'START'}
    assert client.post('/v1/app/commands',json=changed,headers=ah).status_code==409
    delivered=client.get('/v1/terminal/commands?cursor=0',headers=th).json(); assert delivered['commands'][0]['id']==a.json()['id']
    assert client.get('/v1/terminal/commands?cursor='+str(delivered['cursor']),headers=th).json()['commands']
    assert client.post(f"/v1/terminal/commands/{a.json()['id']}/ack",json={'outcome':'APPLIED','state':'RUNNING','message':'contradictory'},headers=th).status_code==422
    ack={'outcome':'APPLIED','state':'STOPPED','message':'applied'}
    assert client.post(f"/v1/terminal/commands/{a.json()['id']}/ack",json=ack,headers=th).status_code==200
    state=client.get('/v1/app/state',headers=ah).json()
    assert state['last_command']['request_id']==req['request_id'] and state['last_command']['status']=='ACKNOWLEDGED'
    assert state['last_command']['outcome']=='APPLIED'
    assert client.get('/v1/terminal/commands?cursor='+str(delivered['cursor']),headers=th).json()['commands']==[]

def test_heartbeat_status_identity_and_offline():
    p=pair(); t=claimed(p); ah={'Authorization':'Bearer '+p['app_token']}; th={'Authorization':'Bearer '+t['terminal_token']}
    assert client.get('/v1/app/state',headers=ah).json()['ea_state']=='OFFLINE'
    wrong=heartbeat_data(account={**ACCOUNT,'login':'different'})
    assert client.post('/v1/terminal/heartbeat',json=wrong,headers=th).status_code==409
    stale=heartbeat_data(timestamp='2000-01-01T00:00:00Z')
    assert client.post('/v1/terminal/heartbeat',json=stale,headers=th).status_code==422
    assert client.post('/v1/terminal/heartbeat',json=heartbeat_data(),headers=th).status_code==200
    state=client.get('/v1/app/state',headers=ah).json()
    assert state['pairing_status']=='PAIRED' and state['terminal_online'] is True and state['account']['login']==ACCOUNT['login']
    assert set(state)=={'pairing_status','terminal_online','heartbeat_age_seconds','heartbeat_stale_seconds','ea_state','terminal_connected','trade_allowed','account','account_identity_verified','configuration','last_signal','last_error','open_positions','closed_deals','last_heartbeat_at','last_command'}
    with app.tx(True) as c: c.execute('UPDATE pairings SET heartbeat=? WHERE id=?',(app.json.dumps({**heartbeat_data(),'timestamp':'2000-01-01T00:00:00Z'}),p['pairing_id']))
    off=client.get('/v1/app/state',headers=ah).json(); assert off['terminal_online'] is False and off['ea_state']=='OFFLINE'

def test_revoke_invalidates_both_tokens():
    p=pair(); t=claimed(p); ah={'Authorization':'Bearer '+p['app_token']}; th={'Authorization':'Bearer '+t['terminal_token']}
    assert client.delete('/v1/app/pairing',headers=ah).status_code==200
    assert client.get('/v1/app/state',headers=ah).status_code==401
    assert client.get('/v1/terminal/commands',headers=th).status_code==401

def test_open_position_with_unavailable_commission_is_accepted_as_null():
    p=pair(); t=claimed(p); ah={'Authorization':'Bearer '+p['app_token']}; th={'Authorization':'Bearer '+t['terminal_token']}
    position={'ticket':'10','position_id':'9','symbol':'EURUSD','direction':'BUY','volume':0.01,'open_price':1.1,'stop_loss':1.09,'take_profit':1.12,'floating_profit':1.0,'commission':None,'swap':0.0}
    hb=heartbeat_data(open_positions=[position],last_signal={'state':'WAIT','reason':'No valid setup'})
    assert client.post('/v1/terminal/heartbeat',json=hb,headers=th).status_code==200
    state=client.get('/v1/app/state',headers=ah).json()
    assert state['open_positions'][0]['commission'] is None
    assert state['last_signal']['reason']=='No valid setup'


from cryptography.fernet import Fernet

def managed_env(monkeypatch):
    with app.tx(True) as c:
        c.execute('DELETE FROM worker_jobs')
        c.execute('DELETE FROM managed_accounts')
        c.execute('DELETE FROM rate')
    monkeypatch.setenv('CREDENTIALS_FERNET_KEY', Fernet.generate_key().decode())
    monkeypatch.setenv('WORKER_TOKEN', 'operator-secret-test-fixture-2026-very-long')
    monkeypatch.setenv('BRIDGE_BASE_URL','https://bridge.destinedfx.internal')

def command_req(action='START'):
    return {'request_id':str(uuid.uuid4()),'action':action,'config':{}}

def managed_pair():
    p=pair()
    ah={'Authorization':'Bearer '+p['app_token']}
    return p,ah

def test_managed_missing_configuration(monkeypatch):
    monkeypatch.delenv('CREDENTIALS_FERNET_KEY',raising=False)
    monkeypatch.delenv('WORKER_TOKEN',raising=False)
    monkeypatch.delenv('BRIDGE_BASE_URL',raising=False)
    p,ah=managed_pair()
    r=client.post('/v1/app/managed-account',headers=ah,json={'login':'123','server':'S','password':'secret','access_type':'MASTER','account_mode':'DEMO','consent':True})
    assert r.status_code==503 and r.json()['detail']['code']=='CONFIGURATION_REQUIRED'

def test_managed_credentials_encrypted_and_worker_claim(monkeypatch):
    managed_env(monkeypatch); p,ah=managed_pair()
    r=client.post('/v1/app/managed-account',headers=ah,json={'login':'99881','server':'Broker','password':'topsecret','access_type':'MASTER','account_mode':'DEMO','consent':True})
    assert r.status_code==202
    with app.tx() as c:
        row=c.execute('SELECT * FROM managed_accounts WHERE pairing_id=?',(p['pairing_id'],)).fetchone()
        assert 'topsecret' not in str(dict(row)) and '99881' not in str(dict(row))
    assert client.post('/v1/worker/jobs/claim',json={'worker_id':'w1'}).status_code==401
    wh={'Authorization':'Bearer operator-secret-test-fixture-2026-very-long'}
    res=client.post('/v1/worker/jobs/claim',headers=wh,json={'worker_id':'w1'})
    assert res.status_code==200 and res.json()['account']=={'login':'99881','server':'Broker','password':'topsecret','access_type':'MASTER','account_mode':'DEMO'}
    assert client.post('/v1/app/managed-account',headers=ah,json={'login':'98765','server':'S','password':'p','access_type':'MASTER','account_mode':'DEMO','consent':True}).status_code==409

def test_managed_tenant_auth_and_master_readiness_gate(monkeypatch):
    managed_env(monkeypatch)
    p,ah=managed_pair(); other,oh=managed_pair()
    for h in (ah,oh):
        assert client.post('/v1/app/managed-account',headers=h,json={'login':'123456','server':'Demo-Server','password':'secret','access_type':'MASTER','account_mode':'DEMO','consent':True}).status_code==202
    wh={'Authorization':'Bearer operator-secret-test-fixture-2026-very-long'}
    job=client.post('/v1/worker/jobs/claim',headers=wh,json={'worker_id':'worker'}).json()
    assert client.patch('/v1/worker/jobs/'+job['job_id'],headers=wh,json={'worker_id':'worker','status':'AUTHENTICATING'}).status_code==200
    assert client.post('/v1/app/commands',headers=ah,json=command_req()).status_code==409
    wrong_account={**ACCOUNT,'login':'999999','server':'Different-Server'}
    mismatch=client.post('/v1/terminal/claim',json={'pairing_code':job['pairing_code'],'terminal_instance_id':'wrong-account','account':wrong_account})
    assert mismatch.status_code==401
    assert client.post('/v1/terminal/claim',json={'pairing_code':job['pairing_code'],'terminal_instance_id':'managed','account':ACCOUNT}).status_code==200
    terminal=client.post('/v1/terminal/claim',json={'pairing_code':job['pairing_code'],'terminal_instance_id':'again','account':ACCOUNT})
    assert terminal.status_code==401
    # The app state cannot report READY without an actual fresh heartbeat.
    assert client.get('/v1/app/state',headers=ah).json()['managed_execution']['status']!='READY'

def test_investor_start_rejected_stop_allowed_and_revoke_clears_secret(monkeypatch):
    managed_env(monkeypatch); p,ah=managed_pair()
    assert client.post('/v1/app/managed-account',headers=ah,json={'login':'7','server':'S','password':'secret','access_type':'INVESTOR','account_mode':'DEMO','consent':True}).status_code==202
    assert client.post('/v1/app/commands',headers=ah,json=command_req('START')).status_code==409
    assert client.post('/v1/app/commands',headers=ah,json=command_req('RESUME')).status_code==409
    assert client.post('/v1/app/commands',headers=ah,json=command_req('STOP')).status_code==202
    result=client.delete('/v1/app/pairing',headers=ah)
    assert result.status_code==200 and result.json()['deprovision_queued']
    with app.tx() as c:
        row=c.execute('SELECT login_enc,server_enc,password_enc,revoked FROM managed_accounts WHERE pairing_id=?',(p['pairing_id'],)).fetchone()
        assert row['revoked']==1 and not row['login_enc'] and not row['server_enc'] and not row['password_enc']
        job=c.execute("SELECT kind,status FROM worker_jobs WHERE pairing_id=? ORDER BY created DESC LIMIT 1",(p['pairing_id'],)).fetchone()
        assert tuple(job)==('REVOKE','QUEUED')

def test_ready_requires_fresh_actual_heartbeat_and_worker_ready(monkeypatch):
    managed_env(monkeypatch); p,ah=managed_pair()
    client.post('/v1/app/managed-account',headers=ah,json={'login':'123456','server':'Demo-Server','password':'secret','access_type':'MASTER','account_mode':'DEMO','consent':True})
    wh={'Authorization':'Bearer operator-secret-test-fixture-2026-very-long'}
    job=client.post('/v1/worker/jobs/claim',headers=wh,json={'worker_id':'worker'}).json()
    client.patch('/v1/worker/jobs/'+job['job_id'],headers=wh,json={'worker_id':'worker','status':'AUTHENTICATING'})
    terminal=client.post('/v1/terminal/claim',json={'pairing_code':job['pairing_code'],'terminal_instance_id':'managed','account':ACCOUNT}).json()
    th={'Authorization':'Bearer '+terminal['terminal_token']}
    client.post('/v1/terminal/heartbeat',headers=th,json=heartbeat_data())
    state=client.get('/v1/app/state',headers=ah).json()
    assert state['managed_execution']['status']=='READY'
    assert client.post('/v1/app/commands',headers=ah,json=command_req()).status_code==202
    assert client.post('/v1/terminal/heartbeat',headers=th,json=heartbeat_data(state='RUNNING')).status_code==200
    running=client.get('/v1/app/state',headers=ah).json()
    assert running['managed_execution']['status']=='READY' and running['ea_state']=='RUNNING'
    with app.tx(True) as c:
        c.execute('UPDATE pairings SET heartbeat=? WHERE id=?',(app.json.dumps({**heartbeat_data(),'timestamp':'2000-01-01T00:00:00Z'}),p['pairing_id']))
    state=client.get('/v1/app/state',headers=ah).json()
    assert state['managed_execution']['status']!='READY'

def test_investor_can_be_ready_for_read_only_but_never_start(monkeypatch):
    managed_env(monkeypatch); p,ah=managed_pair()
    client.post('/v1/app/managed-account',headers=ah,json={'login':'77','server':'ReadOnly','password':'investor','access_type':'INVESTOR','account_mode':'DEMO','consent':True})
    wh={'Authorization':'Bearer operator-secret-test-fixture-2026-very-long'}
    job=client.post('/v1/worker/jobs/claim',headers=wh,json={'worker_id':'worker-ro'}).json()
    client.patch('/v1/worker/jobs/'+job['job_id'],headers=wh,json={'worker_id':'worker-ro','status':'AUTHENTICATING'})
    investor_account={**ACCOUNT,'login':'77','server':'ReadOnly'}
    terminal=client.post('/v1/terminal/claim',json={'pairing_code':job['pairing_code'],'terminal_instance_id':'managed-ro','account':investor_account}).json()
    th={'Authorization':'Bearer '+terminal['terminal_token']}
    assert client.post('/v1/terminal/heartbeat',headers=th,json=heartbeat_data(trade_allowed=False,account={**investor_account,'balance':10000,'equity':9990,'margin':100,'free_margin':9890,'margin_level':9990,'leverage':100})).status_code==200
    state=client.get('/v1/app/state',headers=ah).json()
    assert state['managed_execution']['status']=='READY' and state['managed_execution']['access_type']=='INVESTOR'
    assert client.post('/v1/app/commands',headers=ah,json=command_req()).status_code==409

def test_worker_lease_owner_and_terminal_auth_timeout(monkeypatch):
    managed_env(monkeypatch); p,ah=managed_pair()
    client.post('/v1/app/managed-account',headers=ah,json={'login':'88','server':'S','password':'secret','access_type':'MASTER','account_mode':'DEMO','consent':True})
    wh={'Authorization':'Bearer operator-secret-test-fixture-2026-very-long'}
    job=client.post('/v1/worker/jobs/claim',headers=wh,json={'worker_id':'owner'}).json()
    wrong=client.patch('/v1/worker/jobs/'+job['job_id'],headers=wh,json={'worker_id':'other','status':'AUTHENTICATING'})
    assert wrong.status_code==403
    with app.tx(True) as c: c.execute('UPDATE worker_jobs SET status="AUTHENTICATING",lease_until=?,lease_started=? WHERE id=?',(time.time()+120,time.time()-301,job['job_id']))
    status=client.get('/v1/worker/jobs/'+job['job_id']+'/status?worker_id=owner',headers=wh).json()
    assert status['status']=='FAILED' and status['error_code']=='TERMINAL_AUTH_TIMEOUT'

def test_heartbeat_requires_present_snapshots_and_preserves_confirmed_empty_arrays():
    p=pair(); t=claimed(p); th={'Authorization':'Bearer '+t['terminal_token']}
    missing=heartbeat_data(); del missing['open_positions']
    assert client.post('/v1/terminal/heartbeat',json=missing,headers=th).status_code==422
    assert client.post('/v1/terminal/heartbeat',json={**heartbeat_data(),'open_positions':None},headers=th).status_code==422
    assert client.post('/v1/terminal/heartbeat',json={**heartbeat_data(),'open_positions':[{'bad':'row'}]},headers=th).status_code==422
    assert client.post('/v1/terminal/heartbeat',json=heartbeat_data(open_positions=[],closed_deals=[]),headers=th).status_code==200

def test_placeholder_managed_origin_is_configuration_required(monkeypatch):
    managed_env(monkeypatch)
    monkeypatch.setenv('BRIDGE_BASE_URL','https://your-bridge.example/')
    p,ah=managed_pair()
    r=client.post('/v1/app/managed-account',headers=ah,json={'login':'123','server':'S','password':'secret','access_type':'MASTER','account_mode':'DEMO','consent':True})
    assert r.status_code==503 and r.json()['detail']['code']=='CONFIGURATION_REQUIRED'

def test_start_requires_managed_account_and_fresh_authorized_terminal():
    p=pair(); ah={'Authorization':'Bearer '+p['app_token']}
    req={'request_id':str(uuid.uuid4()),'action':'START','config':{'strategy':'ict-silver-bullet'}}
    assert client.post('/v1/app/commands',json=req,headers=ah).status_code==409

def test_heartbeat_rejects_impossible_numeric_telemetry():
    p=pair(); t=claimed(p); th={'Authorization':'Bearer '+t['terminal_token']}
    position={'ticket':'10','position_id':'9','symbol':'EURUSD','direction':'BUY','volume':-0.01,'open_price':1.1,'stop_loss':1.09,'take_profit':1.12,'floating_profit':1.0,'commission':None,'swap':0.0}
    assert client.post('/v1/terminal/heartbeat',json=heartbeat_data(open_positions=[position]),headers=th).status_code==422
    bad_account={**ACCOUNT,'balance':-100,'equity':100,'margin':0,'free_margin':100,'margin_level':0,'leverage':100}
    assert client.post('/v1/terminal/heartbeat',json=heartbeat_data(account=bad_account),headers=th).status_code==422

def test_expired_active_worker_lease_is_reclaimable_and_old_owner_is_fenced(monkeypatch):
    managed_env(monkeypatch); p,ah=managed_pair()
    client.post('/v1/app/managed-account',headers=ah,json={'login':'99','server':'S','password':'secret','access_type':'MASTER','account_mode':'DEMO','consent':True})
    wh={'Authorization':'Bearer operator-secret-test-fixture-2026-very-long'}
    job=client.post('/v1/worker/jobs/claim',headers=wh,json={'worker_id':'first'}).json()
    client.patch('/v1/worker/jobs/'+job['job_id'],headers=wh,json={'worker_id':'first','status':'AUTHENTICATING'})
    with app.tx(True) as c: c.execute('UPDATE worker_jobs SET lease_until=? WHERE id=?',(time.time()-1,job['job_id']))
    expired=client.get('/v1/worker/jobs/'+job['job_id']+'/status?worker_id=first',headers=wh)
    assert expired.status_code==409
    reclaimed=client.post('/v1/worker/jobs/claim',headers=wh,json={'worker_id':'second'}).json()
    assert reclaimed['job_id']==job['job_id'] and reclaimed['worker_id']=='second'
    old=client.patch('/v1/worker/jobs/'+job['job_id'],headers=wh,json={'worker_id':'first','status':'AUTHENTICATING'})
    assert old.status_code==403

def test_revocation_failure_is_reported_and_queued_provision_is_not_mislabeled(monkeypatch):
    managed_env(monkeypatch); p,ah=managed_pair()
    client.post('/v1/app/managed-account',headers=ah,json={'login':'111','server':'S','password':'secret','access_type':'MASTER','account_mode':'DEMO','consent':True})
    assert client.delete('/v1/app/pairing',headers=ah).status_code==200
    wh={'Authorization':'Bearer operator-secret-test-fixture-2026-very-long'}
    # The older PROVISION job is marked failed; only the actual REVOKE job is returned.
    assert client.post('/v1/worker/jobs/claim',headers=wh,json={'worker_id':'worker'}).json()['job'] is None
    revoke=client.post('/v1/worker/jobs/claim',headers=wh,json={'worker_id':'worker'}).json()
    assert revoke['kind']=='REVOKE'
    update=client.patch('/v1/worker/jobs/'+revoke['job_id'],headers=wh,json={'worker_id':'worker','status':'FAILED','error_code':'REVOKE_MARKER_NOT_FOUND'})
    assert update.status_code==200
    with app.tx() as c:
        row=c.execute('SELECT status,error_code FROM worker_jobs WHERE id=?',(revoke['job_id'],)).fetchone()
    assert tuple(row)==('FAILED','REVOKE_MARKER_NOT_FOUND')



def live_ready(monkeypatch, access_type='MASTER', login='700001', server='Broker-Live'):
    managed_env(monkeypatch)
    p,ah=managed_pair()
    body={'login':login,'server':server,'password':'live-test-password','access_type':access_type,'account_mode':'LIVE','consent':True}
    assert client.post('/v1/app/managed-account',headers=ah,json=body).status_code==202
    wh={'Authorization':'Bearer operator-secret-test-fixture-2026-very-long'}
    job=client.post('/v1/worker/jobs/claim',headers=wh,json={'worker_id':'worker-live'}).json()
    assert client.patch('/v1/worker/jobs/'+job['job_id'],headers=wh,json={'worker_id':'worker-live','status':'AUTHENTICATING'}).status_code==200
    actual={'login':login,'server':server,'company':'Live Broker','currency':'USD','account_mode':'LIVE'}
    terminal=client.post('/v1/terminal/claim',json={'pairing_code':job['pairing_code'],'terminal_instance_id':'live-terminal','account':actual})
    assert terminal.status_code==200
    th={'Authorization':'Bearer '+terminal.json()['terminal_token']}
    hb={**heartbeat_data(account={**actual,'balance':10000,'equity':10000,'margin':0,'free_margin':10000,'margin_level':0,'leverage':100})}
    if access_type=='INVESTOR': hb['trade_allowed']=False
    assert client.post('/v1/terminal/heartbeat',headers=th,json=hb).status_code==200
    return p,ah,wh,job,th,actual


def test_live_master_requires_explicit_current_authorization(monkeypatch):
    p,ah,wh,job,th,actual=live_ready(monkeypatch)
    no_auth=client.post('/v1/app/commands',headers=ah,json=command_req('START'))
    assert no_auth.status_code==409
    authorized=client.post('/v1/app/live-authorization',headers=ah,json={'confirm_live_trading':True})
    assert authorized.status_code==200 and authorized.json()['duration_seconds']==900
    state=client.get('/v1/app/state',headers=ah).json()
    assert state['account_identity_verified'] is True
    assert state['managed_execution']['account_mode']=='LIVE' and state['managed_execution']['live_authorized'] is True
    assert client.post('/v1/app/commands',headers=ah,json=command_req('START')).status_code==202


def test_live_authorization_requires_confirmation_and_current_verified_identity(monkeypatch):
    p,ah,wh,job,th,actual=live_ready(monkeypatch)
    assert client.post('/v1/app/live-authorization',headers=ah,json={'confirm_live_trading':False}).status_code==422
    with app.tx(True) as c:
        c.execute('UPDATE pairings SET live_authorized_until=?,live_authorized_login=?,live_authorized_server=? WHERE id=?',(time.time()+600,actual['login'],actual['server'],p['pairing_id']))
    switched={**actual,'login':'700002'}
    bad=heartbeat_data(account={**switched,'balance':10000,'equity':10000,'margin':0,'free_margin':10000,'margin_level':0,'leverage':100})
    assert client.post('/v1/terminal/heartbeat',headers=th,json=bad).status_code==409
    state=client.get('/v1/app/state',headers=ah).json()
    assert state['terminal_online'] is False and state['account_identity_verified'] is False
    assert state['managed_execution']['live_authorized'] is False
    assert client.post('/v1/app/commands',headers=ah,json=command_req('START')).status_code==409


def test_live_investor_is_never_authorized_to_start(monkeypatch):
    p,ah,wh,job,th,actual=live_ready(monkeypatch,access_type='INVESTOR')
    assert client.post('/v1/app/live-authorization',headers=ah,json={'confirm_live_trading':True}).status_code==409
    assert client.post('/v1/app/commands',headers=ah,json=command_req('START')).status_code==409


def test_live_profile_rejects_demo_terminal_and_expired_authorization(monkeypatch):
    p,ah,wh,job,th,actual=live_ready(monkeypatch)
    assert client.post('/v1/app/live-authorization',headers=ah,json={'confirm_live_trading':True}).status_code==200
    with app.tx(True) as c:
        c.execute('UPDATE pairings SET live_authorized_until=? WHERE id=?',(time.time()-1,p['pairing_id']))
    assert client.post('/v1/app/commands',headers=ah,json=command_req('START')).status_code==409
    p2,ah2=managed_pair()
    assert client.post('/v1/app/managed-account',headers=ah2,json={'login':'700003','server':'Broker-Live','password':'x','access_type':'MASTER','account_mode':'LIVE','consent':True}).status_code==202
    wh2={'Authorization':'Bearer operator-secret-test-fixture-2026-very-long'}
    job2=client.post('/v1/worker/jobs/claim',headers=wh2,json={'worker_id':'worker-live2'}).json()
    demo={**actual,'login':'700003','account_mode':'DEMO'}
    mismatch=client.post('/v1/terminal/claim',json={'pairing_code':job2['pairing_code'],'terminal_instance_id':'wrong-mode','account':demo})
    assert mismatch.status_code==401


def test_missing_account_mode_fails_closed_at_registration_and_terminal_claim():
    p,ah=managed_pair()
    missing=client.post('/v1/app/managed-account',headers=ah,json={'login':'333','server':'S','password':'x','access_type':'MASTER','consent':True})
    assert missing.status_code==422
    unpaired=pair()
    no_mode={'login':'123456','server':'Demo-Server','company':'Demo Broker','currency':'USD'}
    assert client.post('/v1/terminal/claim',json={'pairing_code':unpaired['pairing_code'],'terminal_instance_id':'no-mode','account':no_mode}).status_code==422


def test_live_authorization_revocation_and_profile_pairing_isolation(monkeypatch):
    live=live_ready(monkeypatch)
    live_p,live_ah=live[0],live[1]
    assert client.post('/v1/app/live-authorization',headers=live_ah,json={'confirm_live_trading':True}).status_code==200
    demo_p,demo_ah=managed_pair()
    assert client.post('/v1/app/managed-account',headers=demo_ah,json={'login':'800001','server':'Demo','password':'demo-test','access_type':'MASTER','account_mode':'DEMO','consent':True}).status_code==202
    demo_state=client.get('/v1/app/state',headers=demo_ah).json()
    assert demo_state.get('managed_execution',{}).get('live_authorized') is False
    assert client.delete('/v1/app/live-authorization',headers=live_ah).json()['revoked'] is True
    assert client.get('/v1/app/state',headers=live_ah).json()['managed_execution']['live_authorized'] is False

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
    now_ms=int(time.time()*1000)
    x={'state':'READY','timestamp':app.iso(),'terminal_connected':True,'trade_allowed':True,'account':{**ACCOUNT,'balance':10000,'equity':9990,'margin':100,'free_margin':9890,'margin_level':9990,'leverage':100},'configuration':{'strategy':'demo'},'news_verdict':{'status':'CLEAR','detail':'NEWS CLEAR','source':'fixture-provider','retrieved_at_ms':now_ms,'coverage_from_ms':now_ms-86400000,'coverage_to_ms':now_ms+7*86400000,'affected_currencies':['EUR','USD']},'last_signal':None,'last_error':'','open_positions':[],'active_orders':[],'closed_deals':[]}; x.update(kwargs); return x
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
    assert set(state)=={'pairing_status','terminal_online','heartbeat_age_seconds','heartbeat_stale_seconds','ea_state','terminal_connected','trade_allowed','account','account_identity_verified','configuration','news_verdict','last_signal','last_error','open_positions','active_orders','closed_deals','last_heartbeat_at','last_command'}
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
    assert res.status_code==200 and res.json()['account']=={'login':'99881','server':'Broker','password':'topsecret','access_type':'MASTER','account_mode':'DEMO','cost_model':'OBSERVED_ROUND_TURN','commission_per_lot':0.0,'cost_evidence':''}
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
    assert state['managed_execution']['status']!='READY'
    status=client.get('/v1/worker/jobs/'+job['job_id']+'/status?worker_id=worker',headers=wh).json()
    assert status['status']=='READY'
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

def test_ready_job_is_downgraded_when_heartbeat_goes_stale(monkeypatch):
    managed_env(monkeypatch); p,ah=managed_pair()
    client.post('/v1/app/managed-account',headers=ah,json={'login':'123456','server':'Demo-Server','password':'secret','access_type':'MASTER','account_mode':'DEMO','consent':True})
    wh={'Authorization':'Bearer operator-secret-test-fixture-2026-very-long'}
    job=client.post('/v1/worker/jobs/claim',headers=wh,json={'worker_id':'worker-stale'}).json()
    client.patch('/v1/worker/jobs/'+job['job_id'],headers=wh,json={'worker_id':'worker-stale','status':'AUTHENTICATING'})
    terminal=client.post('/v1/terminal/claim',json={'pairing_code':job['pairing_code'],'terminal_instance_id':'stale-terminal','account':ACCOUNT}).json()
    th={'Authorization':'Bearer '+terminal['terminal_token']}
    client.post('/v1/terminal/heartbeat',headers=th,json=heartbeat_data())
    assert client.get('/v1/worker/jobs/'+job['job_id']+'/status?worker_id=worker-stale',headers=wh).json()['status']=='READY'
    with app.tx(True) as c:
        c.execute('UPDATE pairings SET heartbeat=? WHERE id=?',(app.json.dumps({**heartbeat_data(),'timestamp':'2000-01-01T00:00:00Z'}),p['pairing_id']))
    status=client.get('/v1/worker/jobs/'+job['job_id']+'/status?worker_id=worker-stale',headers=wh).json()
    assert status['status']=='AUTHENTICATING' and status['error_code']=='TERMINAL_HEARTBEAT_STALE'

def test_ready_job_recovers_within_grace_and_fails_after_recovery_deadline(monkeypatch):
    managed_env(monkeypatch); p,ah=managed_pair()
    client.post('/v1/app/managed-account',headers=ah,json={'login':'123456','server':'Demo-Server','password':'secret','access_type':'MASTER','account_mode':'DEMO','consent':True})
    wh={'Authorization':'Bearer operator-secret-test-fixture-2026-very-long'}
    job=client.post('/v1/worker/jobs/claim',headers=wh,json={'worker_id':'worker-recovery'}).json()
    client.patch('/v1/worker/jobs/'+job['job_id'],headers=wh,json={'worker_id':'worker-recovery','status':'AUTHENTICATING'})
    terminal=client.post('/v1/terminal/claim',json={'pairing_code':job['pairing_code'],'terminal_instance_id':'recovery-terminal','account':ACCOUNT}).json()
    th={'Authorization':'Bearer '+terminal['terminal_token']}
    client.post('/v1/terminal/heartbeat',headers=th,json=heartbeat_data())
    assert client.get('/v1/worker/jobs/'+job['job_id']+'/status?worker_id=worker-recovery',headers=wh).json()['status']=='READY'
    with app.tx(True) as c: c.execute('UPDATE pairings SET heartbeat=? WHERE id=?',(app.json.dumps({**heartbeat_data(),'timestamp':'2000-01-01T00:00:00Z'}),p['pairing_id']))
    assert client.get('/v1/worker/jobs/'+job['job_id']+'/status?worker_id=worker-recovery',headers=wh).json()['status']=='AUTHENTICATING'
    assert client.post('/v1/terminal/heartbeat',headers=th,json=heartbeat_data()).status_code==200
    assert client.get('/v1/worker/jobs/'+job['job_id']+'/status?worker_id=worker-recovery',headers=wh).json()['status']=='READY'
    with app.tx(True) as c:
        c.execute('UPDATE pairings SET heartbeat=? WHERE id=?',(app.json.dumps({**heartbeat_data(),'timestamp':'2000-01-01T00:00:00Z'}),p['pairing_id']))
        c.execute('UPDATE worker_jobs SET status="AUTHENTICATING",stale_since=?,recovery_deadline=? WHERE id=?',(time.time()-300,time.time()-1,job['job_id']))
    assert client.get('/v1/worker/jobs/'+job['job_id']+'/status?worker_id=worker-recovery',headers=wh).json()['status']=='FAILED'

def test_error_heartbeat_blocks_start_but_stop_remains_available(monkeypatch):
    managed_env(monkeypatch); p,ah=managed_pair()
    client.post('/v1/app/managed-account',headers=ah,json={'login':'123456','server':'Demo-Server','password':'secret','access_type':'MASTER','account_mode':'DEMO','consent':True})
    wh={'Authorization':'Bearer operator-secret-test-fixture-2026-very-long'}
    job=client.post('/v1/worker/jobs/claim',headers=wh,json={'worker_id':'worker-error'}).json()
    client.patch('/v1/worker/jobs/'+job['job_id'],headers=wh,json={'worker_id':'worker-error','status':'AUTHENTICATING'})
    terminal=client.post('/v1/terminal/claim',json={'pairing_code':job['pairing_code'],'terminal_instance_id':'error-terminal','account':ACCOUNT}).json()
    th={'Authorization':'Bearer '+terminal['terminal_token']}
    client.post('/v1/terminal/heartbeat',headers=th,json=heartbeat_data(state='ERROR',last_error='EA failed'))
    assert client.get('/v1/worker/jobs/'+job['job_id']+'/status?worker_id=worker-error',headers=wh).json()['status']=='AUTHENTICATING'
    assert client.post('/v1/app/commands',headers=ah,json=command_req('START')).status_code==409
    assert client.post('/v1/app/commands',headers=ah,json=command_req('STOP')).status_code==202

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
    assert client.get('/v1/worker/jobs/'+job['job_id']+'/status?worker_id=worker-ro',headers=wh).json()['status']=='READY'
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
    assert client.get('/v1/worker/jobs/'+job['job_id']+'/status?worker_id=worker-live',headers=wh).json()['status']=='READY'
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


def test_readiness_requires_valid_managed_configuration_and_storage(monkeypatch):
    monkeypatch.delenv('CREDENTIALS_FERNET_KEY', raising=False)
    monkeypatch.delenv('WORKER_TOKEN', raising=False)
    monkeypatch.delenv('BRIDGE_BASE_URL', raising=False)
    response=client.get('/health/ready')
    assert response.status_code==503
    managed_env(monkeypatch)
    response=client.get('/health/ready')
    assert response.status_code==200
    assert response.json()=={'status':'ready','database':'ok','managed_execution':'configured'}
def test_documented_health_paths_are_implemented(monkeypatch):
    assert client.get('/health').status_code==200
    monkeypatch.setenv('CREDENTIALS_FERNET_KEY', app.Fernet.generate_key().decode())
    monkeypatch.setenv('WORKER_TOKEN','operator-secret-test-fixture-2026-very-long')
    monkeypatch.setenv('BRIDGE_BASE_URL','https://bridge.realcop.com')
    assert client.get('/health/ready').status_code==200


def test_malformed_managed_origin_fails_closed(monkeypatch):
    managed_env(monkeypatch)
    monkeypatch.setenv('BRIDGE_BASE_URL','https://bridge.invalid:99999')
    p,ah=managed_pair()
    response=client.post('/v1/app/managed-account',headers=ah,json={'login':'123','server':'S','password':'x','access_type':'MASTER','account_mode':'DEMO','consent':True})
    assert response.status_code==503
    assert response.json()['detail']['code']=='CONFIGURATION_REQUIRED'


def test_origin_validation_rejects_non_public_and_malformed_values():
    for origin in ('https://bridge.invalid:0','https://bridge.invalid:99999','https://10.0.0.4','https://100.64.0.1','https://192.0.2.1','https://198.18.0.1','https://203.0.113.1','https://[::1]','https://[::ffff:10.0.0.1]','https://[fe80::1%25eth0]','https://[2001:db8::1]',
                   'https://user:pass@bridge.invalid','https://bridge.invalid/path','https://bridge.invalid/?x=1'):
        assert app.validate_origin(origin) is None
    assert app.validate_origin('http://127.0.0.1', allow_loopback=True)=='http://127.0.0.1'

def test_news_verdict_validation_is_fail_closed_for_stale_unknown_and_relevant_events():
    now_ms=int(time.time()*1000)
    base={'status':'CLEAR','source':'fixture','retrieved_at_ms':now_ms,'coverage_from_ms':now_ms-1000,'coverage_to_ms':now_ms+7*86400000,'affected_currencies':['EUR','USD']}
    assert app.validate_news_verdict(base,'EURUSD')[0] is True
    assert app.validate_news_verdict({**base,'status':'HIGH_IMPACT_WINDOW'},'EURUSD')[0] is False
    assert app.validate_news_verdict({**base,'retrieved_at_ms':now_ms-7*3600000},'EURUSD')[0] is False
    assert app.validate_news_verdict({**base,'coverage_to_ms':now_ms+1000},'EURUSD')[0] is False
    assert app.validate_news_verdict({**base,'affected_currencies':['JPY']},'EURUSD')[0] is True
    assert app.validate_news_verdict(base,'UNKNOWN-SYMBOL')[0] is False

def test_trade_ledger_ingestion_is_idempotent_and_paginated():
    p=pair(); payload=heartbeat_data(last_signal={'order':'99','position':'77','symbol':'EURUSD','direction':'BUY','executed_volume':0.10,'requested_volume':0.10},closed_deals=[{'ticket':'55','position_id':'77','symbol':'EURUSD','closed_at':app.iso(),'profit':12.0,'commission':-1.0,'swap':-0.1}])
    with app.tx(True) as c:
        app.ingest_trade_ledger(c,p['pairing_id'],'DEMO',payload,time.time())
        app.ingest_trade_ledger(c,p['pairing_id'],'DEMO',payload,time.time())
        assert c.execute('SELECT COUNT(*) FROM trade_ledger WHERE pairing_id=?',(p['pairing_id'],)).fetchone()[0]==1
    rows=client.get('/v1/app/trades?limit=1',headers={'Authorization':'Bearer '+p['app_token']})
    assert rows.status_code==200 and len(rows.json()['trades'])==1 and rows.json()['cursor']>0

def test_news_verdict_fail_closed_and_symbol_relevance():
    now=int(time.time()*1000)
    clear={'status':'CLEAR','source':'fixture-provider','retrieved_at_ms':now,'coverage_from_ms':now-1000,'coverage_to_ms':now+3600000,'affected_currencies':['EUR','USD']}
    assert app.validate_news_verdict(clear,'EURUSD')[0] is True
    stale={**clear,'retrieved_at_ms':now-121000}
    assert app.validate_news_verdict(stale,'EURUSD')[0] is False
    incomplete={**clear,'source':'','affected_currencies':[]}
    assert app.validate_news_verdict(incomplete,'EURUSD')[0] is False
    event=now+600000
    blocked={**clear,'status':'HIGH_IMPACT_WINDOW','event_time_ms':event,'currency':'USD','event':'CPI','affected_currencies':['USD']}
    ok,detail=app.validate_news_verdict(blocked,'EURUSD')
    assert ok is False and 'BLOCKED' in detail
    unrelated={**blocked,'affected_currencies':['JPY'],'currency':'JPY'}
    ok,detail=app.validate_news_verdict(unrelated,'EURUSD')
    assert ok is True and 'no relevant' in detail
    assert app.validate_news_verdict(clear,'UNKNOWN_SYMBOL')[0] is False



def test_trade_event_cursor_captures_updates_to_existing_ledger_rows(monkeypatch):
    p=pair(); headers={'Authorization':'Bearer '+p['app_token']}
    first=heartbeat_data(last_signal={'state':'ORDER_ACCEPTED','execution_state':'PARTIALLY_FILLED','order':'901','position':'777','symbol':'EURUSD','direction':'BUY','requested_volume':0.02,'executed_volume':0.01,'actual_entry':1.101,'opened_at':'2026-10-28 10:00:00 (broker server time)'})
    with app.tx(True) as c:
        app.ingest_trade_ledger(c,p['pairing_id'],'DEMO',first,time.time())
        app.ingest_trade_ledger(c,p['pairing_id'],'DEMO',first,time.time())
    page1=client.get('/v1/app/trades?limit=200',headers=headers).json()
    assert len(page1['trades'])==1
    cursor=page1['cursor']
    changed=heartbeat_data(last_signal={**first['last_signal'],'state':'ORDER_ACCEPTED','execution_state':'POSITION_RECONCILED_SLTP_VERIFIED','executed_volume':0.02})
    with app.tx(True) as c:
        app.ingest_trade_ledger(c,p['pairing_id'],'DEMO',changed,time.time()+1)
    page2=client.get(f"/v1/app/trades?cursor={cursor}&limit=200",headers=headers).json()
    assert len(page2['trades'])==1
    assert page2['cursor']>cursor
    assert page2['trades'][0]['payload']['signal']['execution_state']=='POSITION_RECONCILED_SLTP_VERIFIED'


def test_trade_event_cursor_pages_more_than_200_rows_without_skipping():
    p=pair(); headers={'Authorization':'Bearer '+p['app_token']}
    t=time.time()
    with app.tx(True) as c:
        for i in range(205):
            identity=f'position:{10000+i}'
            payload={'kind':'last_signal','account':ACCOUNT,'signal':{'state':'ORDER_ACCEPTED','execution_state':'POSITION_RECONCILED_SLTP_VERIFIED','order':str(20000+i),'position':str(10000+i),'symbol':'EURUSD','direction':'BUY','requested_volume':0.01,'executed_volume':0.01}}
            c.execute('''INSERT INTO trade_ledger(pairing_id,identity,account_mode,position_id,order_ticket,deal_ticket,symbol,direction,status,payload,first_seen,updated)
                         VALUES(?,?,?,?,?,?,?,?,?,?,?,?)''',
                      (p['pairing_id'],identity,'DEMO',str(10000+i),str(20000+i),None,'EURUSD','BUY','OPEN',app.json.dumps(payload),t,t))
    page1=client.get('/v1/app/trades?limit=200',headers=headers).json()
    page2=client.get(f"/v1/app/trades?cursor={page1['cursor']}&limit=200",headers=headers).json()
    assert len(page1['trades'])==200
    assert len(page2['trades'])==5
    ids=[x['id'] for x in page1['trades']+page2['trades']]
    assert len(set(ids))==205



def test_closed_trade_ledger_preserves_broker_entry_metadata_and_true_costs():
    p=pair(); headers={'Authorization':'Bearer '+p['app_token']}
    now=app.iso()
    closed={'ticket':'5002','position_id':'9002','symbol':'EURUSD','direction':'BUY',
            'entry_price':1.10125,'opened_at':'2026-10-28 10:00:00 (broker server time)',
            'closed_at':'2026-10-28 10:30:00 (broker server time)','volume':0.01,'price':1.10325,
            'profit':2.0,'commission':-0.14,'swap':-0.01,'fees':-0.02}
    payload=heartbeat_data(last_signal=None,closed_deals=[closed])
    with app.tx(True) as c:
        app.ingest_trade_ledger(c,p['pairing_id'],'DEMO',payload,time.time())
    page=client.get('/v1/app/trades?limit=20',headers=headers)
    assert page.status_code==200
    trade=next(x for x in page.json()['trades'] if x['position_id']=='9002')
    assert trade['direction']=='BUY'
    assert trade['payload']['deals'][0]['entry_price']==1.10125
    assert trade['payload']['deals'][0]['opened_at']=='2026-10-28 10:00:00 (broker server time)'
    assert trade['payload']['deals'][0]['commission']==-0.14
    assert trade['payload']['deals'][0]['fees']==-0.02


# Hosted deployment and ShowMyTrades adapter regressions.
from email.message import Message
from datetime import datetime, timezone
import json

def _showmytrades_payload(events=None, generated_offset_seconds=0):
    now_s=time.time()
    def stamp(seconds):
        return datetime.fromtimestamp(seconds,timezone.utc).isoformat().replace('+00:00','Z')
    return {'server_time':now_s,'generated_at':stamp(now_s-generated_offset_seconds),
            'from':stamp(now_s-6*60*60),'to':stamp(now_s+2*24*60*60),
            'events':events or [],
            'attribution':{'required':False,'text':'Economic calendar by ShowMyTrades',
                           'url':'https://showmytrades.com/tools/economic-calendar'}}

class _MockCalendarResponse:
    def __init__(self,payload):
        self._body=json.dumps(payload).encode()
        self.headers=Message()
        self.headers['Content-Type']='application/json'
    def __enter__(self): return self
    def __exit__(self,*args): return False
    def read(self,limit=-1): return self._body if limit<0 else self._body[:limit]

def test_showmytrades_adapter_blocks_relevant_high_impact_window(monkeypatch):
    monkeypatch.setattr(app,'NEWS_PROVIDER_URL','https://showmytrades.com/api/public/calendar/v1')
    event={'ts':time.time()+600,'currency':'USD','impact':'high','title':'Test CPI','status':'scheduled'}
    monkeypatch.setattr(app.urllib.request,'urlopen',lambda request,timeout: _MockCalendarResponse(_showmytrades_payload([event])))
    verdict=app.provider_news('EURUSD')
    assert verdict['status']=='HIGH_IMPACT_WINDOW'
    assert verdict['currency']=='USD'
    assert 'high-impact' in verdict['detail'].lower()

def test_showmytrades_adapter_clears_only_with_fresh_complete_coverage(monkeypatch):
    monkeypatch.setattr(app,'NEWS_PROVIDER_URL','https://showmytrades.com/api/public/calendar/v1')
    payload=_showmytrades_payload([])
    monkeypatch.setattr(app.urllib.request,'urlopen',lambda request,timeout: _MockCalendarResponse(payload))
    verdict=app.provider_news('EURUSD')
    assert verdict['status']=='CLEAR'
    assert verdict['coverage_to_ms']>=int(time.time()*1000)+app.NEWS_BEFORE*1000

def test_showmytrades_adapter_fails_closed_on_stale_calendar_snapshot(monkeypatch):
    monkeypatch.setattr(app,'NEWS_PROVIDER_URL','https://showmytrades.com/api/public/calendar/v1')
    payload=_showmytrades_payload([],generated_offset_seconds=1200)
    monkeypatch.setattr(app.urllib.request,'urlopen',lambda request,timeout: _MockCalendarResponse(payload))
    verdict=app.provider_news('EURUSD')
    assert verdict['status']=='UNKNOWN'
    assert 'stale' in verdict['detail'].lower()

def test_showmytrades_adapter_fails_closed_on_provider_errors(monkeypatch):
    monkeypatch.setattr(app,'NEWS_PROVIDER_URL','https://showmytrades.com/api/public/calendar/v1')
    def broken(*args,**kwargs): raise OSError('offline')
    monkeypatch.setattr(app.urllib.request,'urlopen',broken)
    assert app.provider_news('EURUSD')['status']=='UNKNOWN'

def test_news_cache_is_short_lived_and_symbol_specific(monkeypatch):
    monkeypatch.setattr(app,'NEWS_PROVIDER_URL','https://calendar.example.test/verdict')
    calls=[]
    def response(request, timeout):
        calls.append(request.full_url)
        now_ms=int(time.time()*1000)
        return _MockCalendarResponse({'status':'CLEAR','source':'fixture','retrieved_at_ms':now_ms,
            'coverage_from_ms':now_ms-1000,'coverage_to_ms':now_ms+app.NEWS_BEFORE*1000+1000,
            'affected_currencies':['EUR','USD']})
    monkeypatch.setattr(app.urllib.request,'urlopen',response)
    assert app.provider_news('EURUSD')['status']=='CLEAR'
    assert app.provider_news('EURUSD')['status']=='CLEAR'
    assert app.provider_news('USDJPY')['status']=='CLEAR'
    assert len(calls)==2

def test_render_external_url_fallback_can_make_bridge_ready(monkeypatch):
    monkeypatch.delenv('BRIDGE_BASE_URL',raising=False)
    monkeypatch.setenv('RENDER_EXTERNAL_URL','https://destinedfx-managed-bridge.onrender.com')
    monkeypatch.setenv('CREDENTIALS_FERNET_KEY', app.Fernet.generate_key().decode())
    monkeypatch.setenv('WORKER_TOKEN','render-worker-token-test-fixture-at-least-32-chars')
    assert client.get('/health/ready').status_code==200

def test_render_tls_forwarding_is_accepted_only_for_configured_render_origin(monkeypatch):
    from starlette.requests import Request
    monkeypatch.setenv('RENDER_EXTERNAL_URL','https://destinedfx-managed-bridge.onrender.com')
    monkeypatch.setattr(app,'ALLOW_LOOPBACK',False)
    http_client=TestClient(app.app,base_url='https://destinedfx-managed-bridge.onrender.com')
    assert http_client.get('/health').status_code==200
    scope={'type':'http','scheme':'http','method':'GET','path':'/health','raw_path':b'/health',
           'query_string':b'','headers':[(b'host',b'attacker.invalid'),(b'x-forwarded-proto',b'https')],
           'client':('127.0.0.1',1234),'server':('127.0.0.1',8000),'http_version':'1.1'}
    try:
        app.secure(Request(scope))
        assert False, 'mismatched forwarded Host must fail closed'
    except app.HTTPException as exc:
        assert exc.status_code==400


def test_configured_round_turn_cost_model_is_persisted_and_passed_to_worker(monkeypatch):
    managed_env(monkeypatch); p,ah=managed_pair()
    evidence='Official broker pricing schedule section 4'
    r=client.post('/v1/app/managed-account',headers=ah,json={'login':'77881','server':'Broker-Demo','password':'secret','access_type':'MASTER','account_mode':'DEMO','consent':True,'cost_model':'CONFIGURED_ROUND_TURN','commission_per_lot':7.5,'cost_evidence':evidence})
    assert r.status_code==202, r.text
    with app.tx() as c:
        row=c.execute('SELECT cost_model,commission_per_lot,cost_evidence FROM managed_accounts WHERE pairing_id=?',(p['pairing_id'],)).fetchone()
        assert tuple(row)==('CONFIGURED_ROUND_TURN',7.5,evidence)
    wh={'Authorization':'Bearer operator-secret-test-fixture-2026-very-long'}
    job=client.post('/v1/worker/jobs/claim',headers=wh,json={'worker_id':'cost-worker'}).json()
    assert job['account']['cost_model']=='CONFIGURED_ROUND_TURN'
    assert job['account']['commission_per_lot']==7.5
    assert job['account']['cost_evidence']==evidence

def test_zero_commission_spread_only_cost_model_is_explicit_and_passed_to_worker(monkeypatch):
    managed_env(monkeypatch); p,ah=managed_pair()
    evidence='Official broker schedule: zero commission for FX'
    r=client.post('/v1/app/managed-account',headers=ah,json={'login':'77882','server':'Broker-Demo','password':'secret','access_type':'MASTER','account_mode':'DEMO','consent':True,'cost_model':'ZERO_COMMISSION_SPREAD_ONLY','commission_per_lot':0.0,'cost_evidence':evidence})
    assert r.status_code==202, r.text
    wh={'Authorization':'Bearer operator-secret-test-fixture-2026-very-long'}
    job=client.post('/v1/worker/jobs/claim',headers=wh,json={'worker_id':'spread-worker'}).json()
    assert job['account']['cost_model']=='ZERO_COMMISSION_SPREAD_ONLY'
    assert job['account']['commission_per_lot']==0.0
    assert job['account']['cost_evidence']==evidence

def test_invalid_managed_cost_configurations_are_rejected(monkeypatch):
    managed_env(monkeypatch)
    invalid_costs=[
        {'cost_model':'CONFIGURED_ROUND_TURN','commission_per_lot':0.0,'cost_evidence':'Official broker pricing schedule'},
        {'cost_model':'CONFIGURED_PER_SIDE','commission_per_lot':2.5,'cost_evidence':'short'},
        {'cost_model':'ZERO_COMMISSION_SPREAD_ONLY','commission_per_lot':2.5,'cost_evidence':'Official broker pricing schedule'},
        {'cost_model':'UNKNOWN','commission_per_lot':0.0,'cost_evidence':''},
        {'cost_model':'OBSERVED_ROUND_TURN','commission_per_lot':1.0,'cost_evidence':'Official broker pricing schedule'},
        {'cost_model':'ZERO_COMMISSION_SPREAD_ONLY','commission_per_lot':0.0,'cost_evidence':'bad\nreference'}
    ]
    for cost in invalid_costs:
        p,ah=managed_pair()
        payload={'login':'77883','server':'Broker-Demo','password':'secret','access_type':'MASTER','account_mode':'DEMO','consent':True,**cost}
        assert client.post('/v1/app/managed-account',headers=ah,json=payload).status_code==422, cost

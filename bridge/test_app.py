import os, time, uuid
os.environ['ALLOW_LOOPBACK_HTTP']='true'
os.environ['BRIDGE_DB_PATH']='/tmp/destinedfx-bridge-test.sqlite3'
try: os.unlink(os.environ['BRIDGE_DB_PATH'])
except FileNotFoundError: pass
from fastapi.testclient import TestClient
import app
client=TestClient(app.app, base_url='https://testserver')
ACCOUNT={'login':'123456','server':'Demo-Server','company':'Demo Broker','currency':'USD'}
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
    req={'request_id':str(uuid.uuid4()),'action':'START','config':{'strategy':'ict-silver-bullet','symbols':['EURUSD']}}
    a=client.post('/v1/app/commands',json=req,headers=ah); b=client.post('/v1/app/commands',json=req,headers=ah)
    assert a.status_code==202 and a.json()['id']==b.json()['id']
    changed={**req,'action':'STOP'}
    assert client.post('/v1/app/commands',json=changed,headers=ah).status_code==409
    delivered=client.get('/v1/terminal/commands?cursor=0',headers=th).json(); assert delivered['commands'][0]['id']==a.json()['id']
    assert client.get('/v1/terminal/commands?cursor='+str(delivered['cursor']),headers=th).json()['commands']
    ack={'outcome':'APPLIED','state':'READY','message':'accepted by demo EA'}
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
    assert set(state)=={'pairing_status','terminal_online','heartbeat_age_seconds','heartbeat_stale_seconds','ea_state','terminal_connected','trade_allowed','account','configuration','last_signal','last_error','open_positions','closed_deals','last_heartbeat_at','last_command'}
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

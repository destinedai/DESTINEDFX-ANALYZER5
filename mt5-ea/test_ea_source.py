from pathlib import Path
import re
src=Path('DestinedFXTraderEA.mq5').read_text(); hb=src[src.index('void Heartbeat()'):src.index('int OnInit()')]
checks={
 'heartbeat cadence 5s':'InpHeartbeatSeconds=5' in src,
 'poll cadence 5s':'InpPollSeconds=5' in src,
 'revoked token fails closed':'code==401||code==403' in src and 'RevokeSession' in src and 'g_state="STOPPED"' in src and 'FileDelete("DestinedFX_terminal_token.txt")' in src and 'g_terminalToken=""' in src,
 'stale heartbeat fails closed':'HEARTBEAT_STALE_SECONDS 15' in src and 'TimeGMT()-g_lastHeartbeatAcceptedUtc>=HEARTBEAT_STALE_SECONDS' in src and 'No accepted heartbeat within HEARTBEAT_STALE_SECONDS=15' in src,
 'trade gate requires fresh accepted heartbeat':'g_lastHeartbeatAcceptedUtc==0||TimeGMT()-g_lastHeartbeatAcceptedUtc>=HEARTBEAT_STALE_SECONDS' in src and 'void Scan(){if(g_state!="RUNNING"' in src,
 'heartbeat schema excludes app-only extras': all(x not in hb for x in ['pairing_status','terminal_online','heartbeat_age_seconds','ea_state','last_heartbeat_at','last_command']),
 'heartbeat required fields': all(('\\"'+x+'\\"') in hb for x in ['state','timestamp','terminal_connected','trade_allowed','account','configuration','last_signal','last_error','open_positions','closed_deals']),
 'ISO UTC formatter':'T%02d:%02d:%02dZ' in src and 'IsoUtc(TimeGMT())' in hb and 'TimeToString' not in src,
 'no body NUL sent':'if(n>0&&data[n-1]==0)ArrayResize(data,n-1)' in src,
 'nested command runtime config':'ConfigObject(obj)' in src and 'ValidConfig(cfgobj,syms,tfs,risk,daily,concurrent,why)' in src,
 'runtime configuration is applied':'ArrayCopy(g_symbols,syms)' in src and 'g_maxConcurrent=concurrent' in src and 'g_dailyLossPercent=daily' in src,
 'risk validated without clamping':'risk<=0||risk>1' in src and 'g_riskPercent=risk' in src,
 'processed command retains original ack':'Ack(id,g_ackOutcomes[ai],g_ackMessages[ai])' in src and 'g_ackOutcomes[n]=outcome' in src,
 'persistent daily equity':'GlobalVariableSet(key,AccountInfoDouble(ACCOUNT_EQUITY))' in src,
 'rates readiness':'CopyRates(syms[si],mtf,0,80,hr)<50' in src and 'MarketReady(syms[si],mtf)' in src,
 'trade permission readiness':'!MQLInfoInteger(MQL_TRADE_ALLOWED)||!AccountInfoInteger(ACCOUNT_TRADE_ALLOWED)' in src and '!AccountInfoInteger(ACCOUNT_TRADE_ALLOWED)' in src,
 'structured signal object':'LastSignalJson()' in src and '"last_signal\\":%s' in src and 'ORDER_ACCEPTED' in src and 'REJECTED' in src,
 'heartbeat reports active arrays':'JsonArray(g_symbols)' in src and 'JsonArray(g_tfs)' in src and 'g_riskPercent,g_dailyLossPercent,g_maxConcurrent' in src,
 'price refresh and tick normalization':'MathFloor(sl/tick)' in src and 'MathCeil(tp/tick)' in src,
 'risk recheck before submission':'if(!RiskDayOK()){g_lastReason="Daily loss cap changed before order"' in src,
 'claim persistence failure stops token use':'if(!SaveToken(g_terminalToken)){g_terminalToken="";' in src,
 'open-position fee explicitly unavailable':'\\"commission\\":null' in src,
 'signal dedupe persisted and loaded':'PersistSeen();' in src and 'FileOpen(SeenFile(),FILE_READ' in src,
 'README discloses persistent dedupe':'persisted in an account-login-scoped' in Path('README.md').read_text(),
}
# Strip strings/comments with a single-pass lexer, then verify bracket nesting.
def scrub(s):
 out=[]; i=0; mode='code'
 while i<len(s):
  if mode=='code':
   if s.startswith('//',i): mode='line';i+=2;continue
   if s.startswith('/*',i): mode='block';i+=2;continue
   if s[i]=='"': mode='string';i+=1;continue
   if s[i]=="'": mode='char';i+=1;continue
   out.append(s[i]);i+=1
  elif mode=='line':
   if s[i]=='\n': mode='code';out.append('\n')
   i+=1
  elif mode=='block':
   if s.startswith('*/',i):mode='code';i+=2
   else:i+=1
  elif mode in ('string','char'):
   terminator='"' if mode=='string' else "'"
   if s[i]=='\\':i+=2
   elif s[i]==terminator:mode='code';i+=1
   else:i+=1
 return ''.join(out)
clean=scrub(src)
for op,cl in [('(',')'),('{','}'),('[',']')]:
 depth=0;valid=True
 for c in clean:
  if c==op:depth+=1
  elif c==cl:
   depth-=1
   if depth<0:valid=False;break
 checks[f'balanced {op}{cl}']=valid and depth==0
for name,ok in checks.items():print(('PASS' if ok else 'FAIL'),name)
if not all(checks.values()):raise SystemExit(1)

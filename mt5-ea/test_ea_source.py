from pathlib import Path


def _scrub(source: str) -> str:
    out = []
    i = 0
    mode = "code"
    while i < len(source):
        if mode == "code":
            if source.startswith("//", i):
                mode = "line"
                i += 2
                continue
            if source.startswith("/*", i):
                mode = "block"
                i += 2
                continue
            if source[i] == '"':
                mode = "string"
                i += 1
                continue
            if source[i] == "'":
                mode = "char"
                i += 1
                continue
            out.append(source[i])
            i += 1
        elif mode == "line":
            if source[i] == "\n":
                mode = "code"
                out.append("\n")
            i += 1
        elif mode == "block":
            if source.startswith("*/", i):
                mode = "code"
                i += 2
            else:
                i += 1
        else:
            terminator = '"' if mode == "string" else "'"
            if source[i] == "\\":
                i += 2
            elif source[i] == terminator:
                mode = "code"
                i += 1
            else:
                i += 1
    return "".join(out)


def test_ea_source_contract_and_balanced_delimiters():
    root = Path(__file__).resolve().parent
    source = (root / "DestinedFXTraderEA.mq5").read_text()
    readme = (root / "README.md").read_text()
    heartbeat = source[source.index("void Heartbeat()") : source.index("int OnInit()")]
    checks = {
        "heartbeat cadence 5s": "InpHeartbeatSeconds=5" in source,
        "poll cadence 5s": "InpPollSeconds=5" in source,
        "revoked token fails closed": "code==401||code==403" in source and "RevokeSession" in source and 'g_state="STOPPED"' in source and 'FileDelete("DestinedFX_terminal_token.txt")' in source and 'g_terminalToken=""' in source,
        "stale heartbeat fails closed": "HEARTBEAT_STALE_SECONDS 15" in source and "TimeGMT()-g_lastHeartbeatAcceptedUtc>=HEARTBEAT_STALE_SECONDS" in source and "No accepted heartbeat within HEARTBEAT_STALE_SECONDS=15" in source,
        "trade gate requires fresh accepted heartbeat": "g_lastHeartbeatAcceptedUtc==0||TimeGMT()-g_lastHeartbeatAcceptedUtc>=HEARTBEAT_STALE_SECONDS" in source and 'void Scan(){if(g_state!="RUNNING"' in source,
        "heartbeat excludes app-only fields": all(x not in heartbeat for x in ["pairing_status", "terminal_online", "heartbeat_age_seconds", "ea_state", "last_heartbeat_at", "last_command"]),
        "heartbeat required fields": all(('\\"' + x + '\\"') in heartbeat for x in ["state", "timestamp", "terminal_connected", "trade_allowed", "account", "account_mode", "configuration", "last_signal", "last_error", "open_positions", "active_orders", "closed_deals"]),
        "ISO UTC formatter": "T%02d:%02d:%02dZ" in source and "IsoUtc(TimeGMT())" in heartbeat and "TimeToString" not in source,
        "no body NUL sent": "if(n>0&&data[n-1]==0)ArrayResize(data,n-1)" in source,
        "nested command runtime config": "ConfigObject(obj)" in source and "ValidConfig(cfgobj,syms,tfs,risk,daily,concurrent,why)" in source,
        "runtime configuration applied": "ArrayCopy(g_symbols,syms)" in source and "g_maxConcurrent=concurrent" in source and "g_dailyLossPercent=daily" in source,
        "risk validated without clamping": "risk<=0||risk>1" in source and "g_riskPercent=risk" in source,
        "processed command retains original ack": "Ack(id,g_ackOutcomes[ai],g_ackMessages[ai])" in source and "g_ackOutcomes[n]=outcome" in source,
        "persistent daily equity": "GlobalVariableSet(key,AccountInfoDouble(ACCOUNT_EQUITY))" in source,
        "rates readiness": "CopyRates(syms[si],mtf,0,80,hr)<50" in source and "MarketReady(syms[si],mtf)" in source,
        "trade permission readiness": "!MQLInfoInteger(MQL_TRADE_ALLOWED)||!AccountInfoInteger(ACCOUNT_TRADE_ALLOWED)" in source and "!AccountInfoInteger(ACCOUNT_TRADE_ALLOWED)" in source,
        "structured signal object": "LastSignalJson()" in source and '"last_signal\\":%s' in source and "ORDER_ACCEPTED" in source and "REJECTED" in source,
        "heartbeat reports active arrays": "JsonArray(g_symbols)" in source and "JsonArray(g_tfs)" in source and "g_riskPercent,g_dailyLossPercent,g_maxConcurrent" in source,
        "price refresh and tick normalization": "MathFloor(sl/tick)" in source and "MathCeil(tp/tick)" in source,
        "risk recheck before submission": 'if(!RiskDayOK()){g_lastReason="Daily loss cap changed before order"' in source,
        "claim persistence failure stops token use": 'if(!SaveToken(g_terminalToken)){g_terminalToken="";' in source,
        "mode comes from broker terminal enum": "AccountInfoInteger(ACCOUNT_TRADE_MODE)" in source and "ACCOUNT_TRADE_MODE_DEMO" in source and "ACCOUNT_TRADE_MODE_REAL" in source,
        "claim and heartbeat bind reported mode": 'InpExpectedAccountMode' in source and 'BrokerAccountMode()' in source and 'AccountIdentityMatches()' in source and '\\"account_mode\\":\\"%s\\"' in source,
        "actual identity checked before every order": 'bool Place(' in source and 'if(!AccountIdentityMatches())' in source and 'InpExpectedLogin' in source and 'InpExpectedServer' in source,
        "open-position fee unavailable": '\\"commission\\":null' in source,
        "pending and partial order evidence": "OrdersJson()" in source and "TRADE_RETCODE_PLACED" in source and "PARTIALLY_FILLED" in source and "executed_volume" in source,
        "commission history is required for sizing": "ObservedCommissionPerLot" in source and "Commission history unavailable; trade blocked" in source,
        "signal dedupe persisted before submission": "if(!Remember(key))" in source and "PersistSeen()" in source and 'FileOpen(SeenFile(),FILE_READ' in source and "AtomicWrite" in source,
        "README discloses persisted dedupe": "persisted in an account-login-scoped" in readme,
    }

    clean = _scrub(source)
    for opening, closing in [("(", ")"), ("{", "}"), ("[", "]")]:
        depth = 0
        valid = True
        for char in clean:
            if char == opening:
                depth += 1
            elif char == closing:
                depth -= 1
                if depth < 0:
                    valid = False
                    break
        checks[f"balanced {opening}{closing}"] = valid and depth == 0

    failed = [name for name, ok in checks.items() if not ok]
    assert not failed, "EA source contract checks failed: " + ", ".join(failed)

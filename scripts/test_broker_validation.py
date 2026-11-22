#!/usr/bin/env python3
"""Read-only DNS and TCP/443 checks for the four configured DestinedFX MT5 brokers."""
import argparse,json,os,re,socket,sys
from datetime import datetime,timezone
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
CONFIG=ROOT/"scripts/broker_validation_config.json"
SPECS=ROOT/"app/src/main/java/com/example/data/broker/BrokerLoginSpec.kt"
EXPECTED={"FBS_MT5","EXNESS_MT5","HFM_MT5","DERIV_MT5"}
LABELS={"FBS_MT5":"FBS","EXNESS_MT5":"Exness","HFM_MT5":"HFM","DERIV_MT5":"Deriv MT5"}

def read_mt5_specs():
    source=SPECS.read_text(encoding="utf-8")
    # Connector enum includes the digit in MT5_TERMINAL.
    pattern=re.compile(r'BrokerLoginSpec\(\s*id\s*=\s*"(?P<id>[^"]+)".*?connector\s*=\s*LoginConnector\.(?P<c>[A-Z0-9_]+)',re.S)
    return {m["id"] for m in pattern.finditer(source) if m["c"]=="MT5_TERMINAL"}

def validate(data):
    actual=read_mt5_specs()
    found={b.get("spec_id","") for b in data["brokers"]}
    errors=[]
    if actual!=EXPECTED: errors.append(f"Repository MT5 catalog mismatch: {sorted(actual)}")
    if found!=EXPECTED or len(data["brokers"])!=4: errors.append(f"Config must contain exactly {sorted(EXPECTED)}; found {sorted(found)}")
    for b in data["brokers"]:
        for mode in ("demo","real"):
            e=b.get(mode,{})
            names=e.get("server_names",[]); hosts=e.get("dns_hosts",[])
            if not names or not all(isinstance(x,str) and x.strip() for x in names): errors.append(f"{b.get('spec_id')} {mode}: missing server label")
            if not isinstance(hosts,list) or not all(isinstance(h,str) and h.strip() and "://" not in h and "/" not in h and ":" not in h for h in hosts): errors.append(f"{b.get('spec_id')} {mode}: invalid DNS hosts")
    return errors

def probe(host,timeout):
    try:
        rows=socket.getaddrinfo(host,443,type=socket.SOCK_STREAM)
        ips=sorted({x[4][0] for x in rows})
    except OSError as exc:
        return {"host":host,"dns":"NO","tcp":"NOT_TESTED","ips":[],"dns_error":f"{type(exc).__name__}: {exc}","tcp_error":"not attempted: DNS failed"}
    if not ips: return {"host":host,"dns":"NO","tcp":"NOT_TESTED","ips":[],"dns_error":"getaddrinfo returned no addresses","tcp_error":"not attempted: no DNS addresses"}
    errors=[]
    for ip in ips:
        family=socket.AF_INET6 if ":" in ip else socket.AF_INET
        dest=(ip,443,0,0) if family==socket.AF_INET6 else (ip,443)
        try:
            with socket.socket(family,socket.SOCK_STREAM) as sock:
                sock.settimeout(timeout); sock.connect(dest)
            return {"host":host,"dns":"YES","tcp":"YES","ips":ips,"dns_error":"","tcp_error":""}
        except OSError as exc: errors.append(f"{ip}: {type(exc).__name__}: {exc}")
    return {"host":host,"dns":"YES","tcp":"NO","ips":ips,"dns_error":"","tcp_error":"; ".join(errors)}

def probe_mode(entry,timeout):
    hosts=entry.get("dns_hosts",[])
    if not hosts:
        return {"server_names":entry["server_names"],"dns":"NOT_CONFIGURED","tcp":"NOT_CONFIGURED","probes":[],"note":entry.get("network_note","No server FQDN configured; no hostname guessed.")}
    probes=[probe(h,timeout) for h in hosts]
    return {"server_names":entry["server_names"],"dns":"YES" if any(p["dns"]=="YES" for p in probes) else "NO","tcp":"YES" if any(p["tcp"]=="YES" for p in probes) else "NO","probes":probes,"note":entry.get("network_note","")}

def render(report):
    out=["# DestinedFX broker network validation (read-only)","",f"- UTC: {report['timestamp_utc']}",f"- Commit: {report['commit']}","- Scope: exactly four MT5 broker profiles configured in this repository","- Credentials used or printed: NONE","- Orders/trades sent: NONE","- DNS and TCP port 443 only","- Authenticated MT5 handshake/version: NOT PROVEN without account login/session","",
    "| Broker | Demo Server | DNS OK? | TCP 443 OK? | Real Server | DNS OK? | TCP 443 OK? |","|---|---|---|---|---|---|---|"]
    safe=lambda x:str(x).replace("|","\\|").replace("\n"," ")
    for r in report["brokers"]:
        d,l=r["demo"],r["real"]
        out.append(f"| {safe(r['name'])} | {safe(', '.join(d['server_names']))} | {d['dns']} | {d['tcp']} | {safe(', '.join(l['server_names']))} | {l['dns']} | {l['tcp']} |")
    out += ["","## Exact diagnostics",""]
    for r in report["brokers"]:
        for mode,e in (("DEMO",r["demo"]),("REAL",r["real"])):
            out.append(f"### {r['name']} {mode}")
            if not e["probes"]: out.append("Not probed: "+e["note"])
            for p in e["probes"]:
                out.append(f"- {p['host']}: DNS {p['dns']}; TCP 443 {p['tcp']}")
                if p["dns_error"]: out.append("- Exact DNS error: "+p["dns_error"])
                if p["tcp_error"]: out.append("- Exact TCP result: "+p["tcp_error"])
            if e["note"]: out.append("Config note: "+e["note"])
            out.append("")
    out += ["## Interpretation","- NOT_CONFIGURED means the repository does not have a broker-server FQDN for this account mode; it is not a pass or failure.","- An HFM published cluster result does not prove the owner's account is assigned to that cluster.","- TCP 443 success proves only that a TCP socket accepted a connection; it does not prove MT5 authentication.","- No MT5 handshake, account login, terminal version, or trading was tested."]
    return "\n".join(out)+"\n"

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--config",type=Path,default=CONFIG); p.add_argument("--timeout",type=float,default=4.0)
    p.add_argument("--strict-network",action="store_true"); p.add_argument("--json-out",type=Path); p.add_argument("--markdown-out",type=Path)
    p.add_argument("--commit",default=os.environ.get("GITHUB_SHA","working-tree")); a=p.parse_args()
    if not 0.2<=a.timeout<=15: p.error("timeout must be 0.2..15 seconds")
    data=json.loads(a.config.read_text(encoding="utf-8"))
    if data.get("schema_version")!=1 or not isinstance(data.get("brokers"),list): raise SystemExit("Invalid broker config")
    errors=validate(data)
    if errors:
        for e in errors: print("CONFIG FAIL: "+e,file=sys.stderr)
        return 2
    byid={b["spec_id"]:b for b in data["brokers"]}; results=[]; failures=[]
    for sid in ("FBS_MT5","EXNESS_MT5","HFM_MT5","DERIV_MT5"):
        b=byid[sid]; d=probe_mode(b["demo"],a.timeout); r=probe_mode(b["real"],a.timeout)
        for mode,e in (("DEMO",d),("REAL",r)):
            if e["dns"]=="NO": failures.append(f"{LABELS[sid]} {mode}: DNS failed")
            if e["tcp"]=="NO": failures.append(f"{LABELS[sid]} {mode}: TCP 443 failed")
        results.append({"spec_id":sid,"name":LABELS[sid],"demo":d,"real":r})
    report={"schema_version":1,"timestamp_utc":datetime.now(timezone.utc).isoformat(),"commit":a.commit,"brokers":results,"network_failures":failures,"credentials_used":False,"orders_sent":False,"mt5_authenticated_handshake":"NOT_PROVEN"}
    md=render(report); print(md,end="")
    if a.json_out: a.json_out.parent.mkdir(parents=True,exist_ok=True); a.json_out.write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    if a.markdown_out: a.markdown_out.parent.mkdir(parents=True,exist_ok=True); a.markdown_out.write_text(md,encoding="utf-8")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"],"a",encoding="utf-8") as f: f.write(md)
    if a.strict_network and failures:
        print("NETWORK FAIL: "+"; ".join(failures),file=sys.stderr); return 1
    return 0
if __name__=="__main__": raise SystemExit(main())

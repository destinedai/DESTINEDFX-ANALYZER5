#!/usr/bin/env python3
"""Read-only provider-documented MT5 endpoint DNS and TCP checks."""
import argparse, json, os, re, socket, sys
from datetime import datetime, timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
CONFIG=ROOT/"scripts/broker_validation_config.json"
SPECS=ROOT/"app/src/main/java/com/example/data/broker/BrokerLoginSpec.kt"
EXPECTED={"FBS_MT5","EXNESS_MT5","HFM_MT5","DERIV_MT5"}
ORDER=("FBS_MT5","EXNESS_MT5","HFM_MT5","DERIV_MT5")
LABELS={"FBS_MT5":"FBS","EXNESS_MT5":"Exness","HFM_MT5":"HFM","DERIV_MT5":"Deriv MT5"}
SKIPPED="SKIPPED - NEEDS OWNER SERVER"

def read_mt5_specs():
    source=SPECS.read_text(encoding="utf-8")
    pattern=re.compile(
        r'BrokerLoginSpec\(\s*id\s*=\s*"(?P<id>[^"]+)".*?'
        r'connector\s*=\s*LoginConnector\.(?P<connector>[A-Z0-9_]+)',
        re.DOTALL,
    )
    return {m["id"] for m in pattern.finditer(source) if m["connector"]=="MT5_TERMINAL"}

def validate_catalog(data):
    errors=[]
    actual=read_mt5_specs()
    configured={str(item.get("spec_id","")) for item in data.get("brokers",[])}
    if actual!=EXPECTED:
        errors.append(f"Repository MT5 catalog mismatch: expected {sorted(EXPECTED)}, found {sorted(actual)}")
    if configured!=EXPECTED or len(data.get("brokers",[]))!=4:
        errors.append(f"Config must contain exactly {sorted(EXPECTED)}; found {sorted(configured)}")
    for broker in data.get("brokers",[]):
        bid=broker.get("spec_id","<missing>")
        for mode in ("demo","real"):
            entry=broker.get(mode)
            if not isinstance(entry,dict):
                errors.append(f"{bid} {mode}: missing mode config")
                continue
            labels=entry.get("server_names")
            endpoints=entry.get("network_endpoints",[])
            if not isinstance(labels,list) or not labels or not all(isinstance(x,str) and x.strip() for x in labels):
                errors.append(f"{bid} {mode}: missing server label")
            if not isinstance(endpoints,list):
                errors.append(f"{bid} {mode}: network_endpoints must be a list")
                continue
            for endpoint in endpoints:
                host=endpoint.get("host")
                port=endpoint.get("port")
                if (not isinstance(host,str) or not host.strip() or "://" in host or "/" in host or ":" in host
                    or not isinstance(port,int) or not 1<=port<=65535):
                    errors.append(f"{bid} {mode}: invalid endpoint {endpoint!r}")
    return errors

def probe(host,port,timeout):
    """Resolve a host and attempt TCP to the exact provider-documented port."""
    try:
        rows=socket.getaddrinfo(host,port,type=socket.SOCK_STREAM)
        addresses=sorted({row[4][0] for row in rows})
    except OSError as exc:
        return {"host":host,"port":port,"dns":"NO","tcp":"NOT_TESTED","addresses":[],
                "dns_error":f"{type(exc).__name__}: {exc}",
                "tcp_error":"not attempted because DNS resolution failed"}
    if not addresses:
        return {"host":host,"port":port,"dns":"NO","tcp":"NOT_TESTED","addresses":[],
                "dns_error":"getaddrinfo returned no addresses",
                "tcp_error":"not attempted because DNS returned no addresses"}
    failures=[]
    for address in addresses:
        family=socket.AF_INET6 if ":" in address else socket.AF_INET
        destination=(address,port,0,0) if family==socket.AF_INET6 else (address,port)
        try:
            with socket.socket(family,socket.SOCK_STREAM) as sock:
                sock.settimeout(timeout)
                sock.connect(destination)
            return {"host":host,"port":port,"dns":"YES","tcp":"YES","addresses":addresses,"dns_error":"","tcp_error":""}
        except OSError as exc:
            failures.append(f"{address}:{port}: {type(exc).__name__}: {exc}")
    return {"host":host,"port":port,"dns":"YES","tcp":"NO","addresses":addresses,"dns_error":"","tcp_error":"; ".join(failures)}

def probe_mode(entry,timeout):
    endpoints=entry.get("network_endpoints",[])
    if not endpoints:
        return {
            "server_names":entry["server_names"],"dns":"SKIPPED","tcp":"SKIPPED",
            "port_tested":None,"probes":[],"result":SKIPPED,
            "note":entry.get("network_note","Owner must supply the exact account server."),
        }
    results=[]
    for endpoint in endpoints:
        result=probe(endpoint["host"],endpoint["port"],timeout)
        result["server_label"]=endpoint["server_label"]
        results.append(result)
    passed=all(x["dns"]=="YES" and x["tcp"]=="YES" for x in results)
    return {
        "server_names":entry["server_names"],
        "dns":"YES" if all(x["dns"]=="YES" for x in results) else "NO",
        "tcp":"YES" if all(x["tcp"]=="YES" for x in results) else "NO",
        "port_tested":", ".join(str(x["port"]) for x in results),
        "probes":results,"result":"PASS" if passed else "FAIL",
        "note":entry.get("network_note",""),
    }

def render(report):
    lines=[
        "# DestinedFX broker endpoint validation (read-only)","",
        f"- UTC: {report['timestamp_utc']}",f"- Commit: {report['commit']}",
        "- Scope: exactly four MT5 broker profiles configured in this repository",
        "- Account credentials used: NONE","- Orders/trades sent: NONE",
        "- Test: DNS plus TCP to the exact configured MT5 service port",
        "- Authenticated MT5 handshake/version: NOT PROVEN until the owner logs in with DEMO","",
        "| Broker | Mode | Server label | Port tested | DNS | TCP | Result |",
        "|---|---|---|---:|---|---|---|"
    ]
    safe=lambda value: str(value).replace("|",r"\|").replace("\n"," ")
    for broker in report["brokers"]:
        for mode_name,mode in (("DEMO",broker["demo"]),("REAL",broker["real"])):
            label=", ".join(mode["server_names"])
            port=mode["port_tested"] or "—"
            lines.append(f"| {safe(broker['name'])} | {mode_name} | {safe(label)} | {port} | {mode['dns']} | {mode['tcp']} | {safe(mode['result'])} |")
    lines.extend(["","## Exact endpoint diagnostics",""])
    for broker in report["brokers"]:
        for mode_name,mode in (("DEMO",broker["demo"]),("REAL",broker["real"])):
            lines.append(f"### {broker['name']} — {mode_name}")
            if not mode["probes"]:
                lines.append(f"- DNS/TCP skipped: {mode['result']}")
                lines.append(f"- Reason: {mode['note']}")
            for item in mode["probes"]:
                lines.append(f"- {item['server_label']} at {item['host']}:{item['port']}: DNS {item['dns']}; TCP {item['tcp']}")
                if item["dns_error"]: lines.append(f"  - Exact DNS error: {item['dns_error']}")
                if item["tcp_error"]: lines.append(f"  - Exact TCP result: {item['tcp_error']}")
            if mode["note"] and mode["probes"]: lines.append(f"- Config note: {mode['note']}")
            lines.append("")
    lines.extend([
        "## Interpretation",
        f"- {SKIPPED} is an owner-action item, not a network failure. The owner must type the exact account server in the app.",
        "- HFM Demo uses port 1950 and HFM Live1 uses port 1951 on the configured Europe1 cluster host.",
        "- TCP success proves only that a socket accepted a connection on the tested port.",
        "- DNS/TCP checks do not authenticate an account, verify terminal version, or prove the account's actual DEMO/REAL mode.",
        "- No MT5 handshake, account login, or trading was attempted.",
    ])
    return "\n".join(lines)+"\n"

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config",type=Path,default=CONFIG)
    parser.add_argument("--timeout",type=float,default=5.0)
    parser.add_argument("--strict-network",action="store_true")
    parser.add_argument("--json-out",type=Path)
    parser.add_argument("--markdown-out",type=Path)
    parser.add_argument("--commit",default=os.environ.get("GITHUB_SHA","working-tree"))
    args=parser.parse_args()
    if not 0.2<=args.timeout<=15: parser.error("timeout must be between 0.2 and 15 seconds")
    data=json.loads(args.config.read_text(encoding="utf-8"))
    if data.get("schema_version")!=2 or not isinstance(data.get("brokers"),list):
        raise SystemExit("Invalid broker validation config")
    errors=validate_catalog(data)
    if errors:
        for error in errors: print(f"CONFIG FAIL: {error}",file=sys.stderr)
        return 2
    by_id={item["spec_id"]:item for item in data["brokers"]}
    results=[]
    failures=[]
    for spec_id in ORDER:
        broker=by_id[spec_id]
        demo=probe_mode(broker["demo"],args.timeout)
        real=probe_mode(broker["real"],args.timeout)
        for mode_name,mode in (("DEMO",demo),("REAL",real)):
            if mode["result"]=="FAIL":
                failures.append(f"{LABELS[spec_id]} {mode_name}: configured endpoint DNS/TCP failed")
        results.append({"spec_id":spec_id,"name":LABELS[spec_id],"demo":demo,"real":real})
    report={
        "schema_version":2,"timestamp_utc":datetime.now(timezone.utc).isoformat(),
        "commit":args.commit,"brokers":results,"network_failures":failures,
        "credentials_used":False,"orders_sent":False,"mt5_authenticated_handshake":"NOT_PROVEN",
    }
    markdown=render(report)
    print(markdown,end="")
    if args.json_out:
        args.json_out.parent.mkdir(parents=True,exist_ok=True)
        args.json_out.write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    if args.markdown_out:
        args.markdown_out.parent.mkdir(parents=True,exist_ok=True)
        args.markdown_out.write_text(markdown,encoding="utf-8")
    summary=os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary,"a",encoding="utf-8") as stream: stream.write(markdown)
    # Only explicitly configured endpoint failures gate CI; owner-required skips do not.
    if args.strict_network and failures:
        print("NETWORK FAIL: "+"; ".join(failures),file=sys.stderr)
        return 1
    return 0

if __name__=="__main__": raise SystemExit(main())

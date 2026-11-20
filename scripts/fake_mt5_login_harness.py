#!/usr/bin/env python3
"""FIXTURE ONLY: deterministic fake MT5 journal/heartbeat producer; never connects to a broker."""
import argparse, json, time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--journal", required=True, type=Path)
    parser.add_argument("--heartbeat", required=True, type=Path)
    parser.add_argument("--scenario", choices=["success", "wrong_password", "wrong_server", "no_connection", "terminal_killed", "ea_killed", "stale_heartbeat"], default="success")
    args = parser.parse_args()
    args.journal.parent.mkdir(parents=True, exist_ok=True); args.heartbeat.parent.mkdir(parents=True, exist_ok=True)
    now = time.time()
    if args.scenario == "wrong_password":
        line = "2026.11.08 07:01:00.000 Network '123456': authorization on Fixture-Demo failed (Invalid password)\n"
        args.journal.write_text(line, encoding="utf-16le")
    elif args.scenario == "wrong_server":
        line = "2026.11.08 07:01:00.000 Network '123456': authorization on Missing-Server failed (Server not found)\n"
        args.journal.write_text(line, encoding="utf-16le")
    elif args.scenario == "no_connection":
        args.journal.write_text("2026.11.08 07:01:00.000 Network: No connection\n", encoding="utf-16le")
    elif args.scenario in {"terminal_killed", "ea_killed"}:
        args.journal.write_text("2026.11.08 07:01:00.000 Terminal stopped\n", encoding="utf-16le")
    else:
        args.journal.write_text("2026.11.08 07:01:00.000 Network '123456': authorization on Fixture-Demo succeeded\n", encoding="utf-16le")
    heartbeat = {"fixture": True, "timestamp": now - (20 if args.scenario == "stale_heartbeat" else 0), "terminal_connected": args.scenario not in {"terminal_killed", "ea_killed"}, "state": "READY" if args.scenario == "success" else "OFFLINE", "account": {"login": "123456", "server": "Fixture-Demo", "account_mode": "DEMO"}}
    args.heartbeat.write_text(json.dumps(heartbeat), encoding="utf-8")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())

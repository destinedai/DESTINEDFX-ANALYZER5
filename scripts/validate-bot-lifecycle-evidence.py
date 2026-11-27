#!/usr/bin/env python3
"""Validate real Android-emulator service evidence and instrumentation results."""
from __future__ import annotations
import argparse
import json
import math
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REQUIRED_TRANSITIONS = {
    "Start", "Run", "Pause", "Resume", "Stop", "Emergency-Stop", "Restart",
    "NoAutoRestartAfterStop", "RestartAfterStop",
    "NoAutoRestartAfterEmergencyStop", "RestartAfterEmergencyStop",
    "CleanupStop", "FiveManagedDemoCycles", "PauseAfterCycles", "FinalStopAfterCycles",
}
REQUIRED_CYCLE_FIELDS = (
    "signal_id", "contract_id", "symbol", "trade_side", "direction", "stake",
    "lot", "entry_price", "exit_price", "profit", "close_reason",
    "scan_at_utc", "signal_at_utc", "open_at_utc", "manage_at_utc",
    "close_at_utc", "next_open_at_utc", "account_mode", "fake_gateway_only",
    "live_enabled",
)
ALLOWED_CLOSE_REASONS = {"TAKE_PROFIT", "STOP_LOSS", "REVERSAL", "BROKER_CONFIRMED_SETTLEMENT"}

def fail(message: str) -> None:
    raise SystemExit(message)

def validate_evidence(path: Path) -> tuple[int, int, int]:
    if not path.is_file():
        fail(f"I FAIL, EVIDENCE MISSING: emulator did not write {path}.")
    if path.stat().st_size <= 100:
        fail(f"I FAIL, EVIDENCE EMPTY: emulator wrote {path} but it is too small.")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"I FAIL, EVIDENCE INVALID JSON: {exc}")
    if not isinstance(payload, dict):
        fail("Emulator evidence must be a JSON object written by the Android service test.")
    if (payload.get("account_mode") != "DEMO" or payload.get("fake_gateway_only") is not True
            or payload.get("live_enabled") is not False):
        fail("Evidence violates the DEMO-only fake-gateway boundary; LIVE must remain disabled.")

    transitions = payload.get("transitions")
    if not isinstance(transitions, list):
        fail("Emulator evidence is missing its timestamped transitions array.")
    present = {row.get("step") for row in transitions if isinstance(row, dict)}
    missing = sorted(REQUIRED_TRANSITIONS - present)
    if missing:
        fail("Evidence is missing required lifecycle transitions: " + ", ".join(missing))
    for row in transitions:
        if not isinstance(row, dict) or not row.get("timestamp_utc"):
            fail("Evidence contains a transition without a timestamp.")
        if "state_before" not in row or "state_after" not in row:
            fail(f"Transition {row.get('step')} is missing state_before/state_after.")
        if row.get("account_mode") != "DEMO" or row.get("live_enabled") is not False:
            fail(f"Transition {row.get('step')} violates the DEMO/LIVE boundary.")
        if not isinstance(row.get("broker_gateway_log"), list):
            fail(f"Transition {row.get('step')} is missing broker gateway evidence.")

    cycles = payload.get("trade_cycles")
    start, count = payload.get("managed_cycle_start_index"), payload.get("managed_cycle_count")
    if not isinstance(cycles, list):
        fail("Emulator evidence is missing trade_cycles.")
    if not isinstance(start, int) or start < 0:
        fail("Evidence does not identify the continuous-service cycle start index.")
    if count != 5 or start + 5 > len(cycles):
        fail(f"Expected exactly 5 complete managed-service cycles; found count={count}, total={len(cycles)}.")
    managed_cycles = cycles[start:start + 5]
    sides: set[str] = set()
    for index, cycle in enumerate(managed_cycles, start=1):
        if not isinstance(cycle, dict):
            fail(f"Managed trade cycle {index} is not a JSON object.")
        missing_fields = [f for f in REQUIRED_CYCLE_FIELDS if f not in cycle or cycle[f] in (None, "")]
        if missing_fields:
            fail(f"Managed trade cycle {index} is incomplete: {', '.join(missing_fields)}.")
        for field in ("stake", "entry_price", "exit_price", "profit"):
            value = cycle[field]
            if not isinstance(value, (int, float)) or not math.isfinite(value):
                fail(f"Managed trade cycle {index} has invalid {field}.")
        if cycle["stake"] <= 0 or cycle["entry_price"] <= 0 or cycle["exit_price"] < 0:
            fail(f"Managed trade cycle {index} has invalid stake/entry/exit values.")
        if cycle["trade_side"] not in {"BUY", "SELL"} or cycle["direction"] not in {"LONG", "SHORT"}:
            fail(f"Managed trade cycle {index} has invalid direction.")
        if (cycle["account_mode"] != "DEMO" or cycle["fake_gateway_only"] is not True
                or cycle["live_enabled"] is not False):
            fail(f"Managed trade cycle {index} crosses the DEMO/LIVE boundary.")
        if cycle["close_reason"] not in ALLOWED_CLOSE_REASONS:
            fail(f"Managed trade cycle {index} has unsupported close reason {cycle['close_reason']!r}.")
        for field in ("scan_at_utc", "signal_at_utc", "open_at_utc", "manage_at_utc", "close_at_utc", "next_open_at_utc"):
            if not isinstance(cycle[field], str) or not cycle[field].strip():
                fail(f"Managed trade cycle {index} is missing {field}.")
        sides.add(cycle["trade_side"])
    if sides != {"BUY", "SELL"}:
        fail(f"The five managed-service cycles must include BUY and SELL; found {sorted(sides)}.")
    required_stop_reason = "Stopped — Deriv broker confirms all DestinedFX Bot contracts are closed"
    if payload.get("stop_reason") != required_stop_reason:
        fail(f"Final STOP was not fully broker-confirmed: {payload.get('stop_reason')!r}.")
    final_stop = next((row for row in reversed(transitions) if row.get("step") == "FinalStopAfterCycles"), None)
    if final_stop is None or "confirms every Bot-owned DEMO contract closed" not in final_stop.get("detail", ""):
        fail("FinalStopAfterCycles transition is missing explicit full-ledger STOP confirmation.")
    return len(transitions), len(payload.get("trade_events", [])), count

def validate_test_results(directory: Path) -> tuple[int, int]:
    files = sorted(directory.rglob("TEST-*.xml")) if directory.exists() else []
    if not files:
        fail(f"No Android instrumentation XML results found under {directory}.")
    executed = failed = 0
    failed_names: list[str] = []
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            fail(f"Malformed Android instrumentation result {path}: {exc}")
        for case in root.iter("testcase"):
            if case.find("skipped") is None:
                executed += 1
            if case.find("failure") is not None or case.find("error") is not None:
                failed += 1
                failed_names.append(f"{case.attrib.get('classname', '')}.{case.attrib.get('name', '')}")
    if executed == 0:
        fail("Emulator task produced zero executed instrumentation tests.")
    if failed:
        fail(f"Emulator acceptance failed: {failed} instrumentation test(s): " + "; ".join(failed_names))
    return executed, failed

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--test-results", type=Path, required=True)
    args = parser.parse_args()
    transitions, events, cycle_count = validate_evidence(args.evidence)
    executed, _ = validate_test_results(args.test_results)
    print(f"Android emulator PASS: {executed} instrumentation tests; {transitions} lifecycle transitions; {events} service events; {cycle_count} complete continuous DEMO cycles; BUY and SELL; LIVE disabled.")
    return 0

if __name__ == "__main__":
    sys.exit(main())

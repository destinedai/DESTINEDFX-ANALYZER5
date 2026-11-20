#!/usr/bin/env python3
"""Read-only DestinedFX LIVE infrastructure preflight.

Checks management API health and Ed25519 public-key publication, then compares
that key's SHA-256 SPKI fingerprint with the running Bridge's health/config.
It also checks persistent/durable Bridge configuration and terminal readiness.
It NEVER changes LIVE state, reads account credentials, fabricates DEMO evidence,
or submits a trading command/order. The optional PEM output is PUBLIC only.
"""
from __future__ import annotations
import argparse, hashlib, json, os, re, stat, tempfile, urllib.error, urllib.parse, urllib.request
from pathlib import Path
from typing import Any
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

BROKERS = ("FBS", "EXNESS", "HFM", "DERIV")
ISSUER, AUDIENCE = "destinedfx-management", "destinedfx-bridge"
MAX_BYTES = 65536

class PreflightError(RuntimeError):
    """Sanitized error code; response bodies and secrets are never included."""

def base_url(value: str, label: str) -> str:
    try:
        p = urllib.parse.urlsplit((value or "").strip().rstrip("/")); port = p.port
    except ValueError:
        raise PreflightError(f"{label}_URL_INVALID") from None
    if p.scheme != "https" or not p.hostname or p.username or p.password or p.query or p.fragment or p.path not in ("", "/"):
        raise PreflightError(f"{label}_URL_MUST_BE_HTTPS_ORIGIN")
    return f"https://{p.hostname.lower()}" + (f":{port}" if port else "")

def fingerprint(pem: str | bytes) -> str:
    key = serialization.load_pem_public_key(pem.encode() if isinstance(pem, str) else pem)
    if not isinstance(key, Ed25519PublicKey):
        raise PreflightError("STEP_UP_KEY_NOT_ED25519_PUBLIC")
    der = key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return hashlib.sha256(der).hexdigest()

def get_json(url: str) -> tuple[int, dict[str, Any]]:
    req = urllib.request.Request(url, headers={"Accept":"application/json", "User-Agent":"DestinedFX-LIVE-Preflight/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            status, raw = int(r.status), r.read(MAX_BYTES + 1)
    except urllib.error.HTTPError as e:
        status = int(e.code)
        try: raw = e.read(MAX_BYTES + 1)
        except OSError: raw = b""
    except (OSError, TimeoutError, urllib.error.URLError):
        raise PreflightError("ENDPOINT_UNREACHABLE") from None
    if len(raw) > MAX_BYTES: raise PreflightError("ENDPOINT_RESPONSE_TOO_LARGE")
    try: value = json.loads(raw.decode()) if raw else {}
    except (UnicodeError, json.JSONDecodeError): raise PreflightError("ENDPOINT_RESPONSE_NOT_JSON") from None
    if not isinstance(value, dict): raise PreflightError("ENDPOINT_SCHEMA_INVALID")
    return status, value

def error_code(payload: dict[str, Any]) -> str:
    detail = payload.get("detail")
    code = detail.get("code") if isinstance(detail, dict) else None
    return code if isinstance(code, str) and re.fullmatch(r"[A-Z0-9_]{1,80}", code) else "HTTP_REQUEST_BLOCKED"

def write_public_key(path: Path, pem: str, overwrite: bool = False) -> Path:
    dest = path.expanduser().resolve()
    if dest.exists() and not overwrite: raise PreflightError("PUBLIC_KEY_OUTPUT_EXISTS_USE_OVERWRITE")
    fingerprint(pem)
    dest.parent.mkdir(parents=True, exist_ok=True)
    fd, temp = tempfile.mkstemp(prefix=f".{dest.name}.", dir=dest.parent)
    try:
        with os.fdopen(fd, "w", encoding="ascii", newline="\n") as f:
            f.write(pem.rstrip() + "\n"); f.flush(); os.fsync(f.fileno())
        if os.name != "nt": os.chmod(temp, stat.S_IRUSR | stat.S_IWUSR)
        os.replace(temp, dest)
    except Exception:
        try: os.unlink(temp)
        except OSError: pass
        raise
    return dest

def run(management_url: str, bridge_url: str, *, key_file: Path | None = None,
        write_key: Path | None = None, overwrite: bool = False,
        brokers: tuple[str, ...] = BROKERS) -> dict[str, Any]:
    management, bridge = base_url(management_url, "MANAGEMENT"), base_url(bridge_url, "BRIDGE")
    checks: list[dict[str, Any]] = []
    pem = issuer = audience = None
    remote_fp = None
    try:
        code, data = get_json(management + "/ready")
        ok = code == 200 and data.get("status") == "ready"
        checks.append({"name":"management_backend","status":"PASS" if ok else "BLOCKED","code":"BACKEND_READY" if ok else error_code(data) if code >= 400 else "BACKEND_NOT_READY"})
    except PreflightError as e: checks.append({"name":"management_backend","status":"BLOCKED","code":str(e)})
    try:
        code, data = get_json(management + "/api/v1/auth/bridge-verification-key")
        if code != 200: raise PreflightError(error_code(data))
        if data.get("algorithm") != "EdDSA": raise PreflightError("STEP_UP_ALGORITHM_MISMATCH")
        pem, issuer, audience = data.get("public_key_pem"), data.get("issuer"), data.get("audience")
        if not isinstance(pem, str) or not isinstance(issuer, str) or not isinstance(audience, str): raise PreflightError("STEP_UP_KEY_RESPONSE_INCOMPLETE")
        remote_fp = fingerprint(pem)
        if (issuer, audience) != (ISSUER, AUDIENCE): raise PreflightError("STEP_UP_ISSUER_AUDIENCE_MISMATCH")
        checks.append({"name":"management_step_up_key","status":"PASS","code":"ED25519_PUBLIC_KEY_AVAILABLE","fingerprint_sha256":remote_fp})
    except (PreflightError, TypeError, ValueError):
        checks.append({"name":"management_step_up_key","status":"BLOCKED","code":"STEP_UP_KEY_MISSING_INVALID_OR_MISCONFIGURED"})
    if key_file:
        try:
            local_fp = fingerprint(key_file.expanduser().read_bytes()); matched = bool(remote_fp and local_fp == remote_fp)
            checks.append({"name":"local_public_key_file","status":"PASS" if matched else "BLOCKED","code":"PUBLIC_KEY_MATCH" if matched else "PUBLIC_KEY_MISMATCH","fingerprint_sha256":local_fp})
        except (OSError, PreflightError, ValueError): checks.append({"name":"local_public_key_file","status":"BLOCKED","code":"LOCAL_PUBLIC_KEY_MISSING_OR_INVALID"})
    if write_key:
        try:
            if not pem: raise PreflightError("BACKEND_PUBLIC_KEY_UNAVAILABLE")
            dest = write_public_key(write_key, pem, overwrite)
            checks.append({"name":"write_public_key","status":"PASS","code":"PUBLIC_KEY_WRITTEN","path":str(dest)})
        except (OSError, PreflightError): checks.append({"name":"write_public_key","status":"BLOCKED","code":"PUBLIC_KEY_WRITE_FAILED_OR_REFUSED"})
    config = {}
    try:
        code, config = get_json(bridge + "/health/config")
        if code != 200 or config.get("status") != "configured": raise PreflightError(error_code(config))
        checks.append({"name":"bridge_config","status":"PASS","code":"BRIDGE_CONFIGURED"})
        persistent = config.get("host_profile") == "PERSISTENT"
        checks.append({"name":"persistent_host","status":"PASS" if persistent else "BLOCKED","code":"PERSISTENT_HOST" if persistent else "HOST_NOT_PERSISTENT"})
        durable = config.get("database") == "ok" and config.get("managed_execution") == "configured"
        checks.append({"name":"durable_storage","status":"PASS" if durable else "BLOCKED","code":"DURABLE_CONFIGURED" if durable else "DURABLE_CONFIG_NOT_READY"})
        bridge_fp = config.get("management_step_up_key_fingerprint_sha256")
        trust = bool(remote_fp and bridge_fp == remote_fp)
        checks.append({"name":"management_bridge_trust","status":"PASS" if trust else "BLOCKED","code":"PUBLIC_KEY_FINGERPRINT_MATCH" if trust else "BRIDGE_KEY_MISSING_OR_MISMATCHED","bridge_fingerprint_sha256":bridge_fp if isinstance(bridge_fp, str) else ""})
        issuer_match = bool(issuer and audience and config.get("management_step_up_issuer") == issuer and config.get("management_step_up_audience") == audience)
        checks.append({"name":"management_bridge_issuer_audience","status":"PASS" if issuer_match else "BLOCKED","code":"ISSUER_AUDIENCE_MATCH" if issuer_match else "ISSUER_AUDIENCE_MISMATCH"})
    except PreflightError as e:
        for name in ("bridge_config", "persistent_host", "durable_storage", "management_bridge_trust", "management_bridge_issuer_audience"):
            checks.append({"name":name,"status":"BLOCKED","code":str(e) if name == "bridge_config" else "BRIDGE_CONFIG_UNAVAILABLE"})
    try:
        code, data = get_json(bridge + "/health/ready")
        ready = code == 200 and data.get("status") == "ready"
        checks.append({"name":"verified_mt5_terminal","status":"PASS" if ready else "PENDING","code":"TERMINAL_READY" if ready else error_code(data) if code >= 400 else "TERMINAL_NOT_READY"})
    except PreflightError: checks.append({"name":"verified_mt5_terminal","status":"PENDING","code":"BRIDGE_READINESS_UNREACHABLE"})
    chosen = tuple(dict.fromkeys(b.upper() for b in brokers))
    valid = bool(chosen) and all(b in BROKERS for b in chosen)
    checks.append({"name":"broker_code_paths","status":"PASS" if valid else "BLOCKED","code":"SOURCE_SUPPORT_ONLY_NOT_BROKER_ACCEPTANCE" if valid else "INVALID_BROKER_LIST","brokers":list(chosen)})
    blocked = any(c["status"] == "BLOCKED" for c in checks)
    pending = any(c["name"] == "verified_mt5_terminal" and c["status"] != "PASS" for c in checks)
    state = "BLOCKED" if blocked else "INFRASTRUCTURE_READY_ACCOUNT_ACCEPTANCE_PENDING" if pending else "INFRASTRUCTURE_AND_TERMINAL_READY_LIVE_GATES_STILL_APPLY"
    return {"schema_version":1,"overall_status":state,"checks":checks,
            "non_actions":["No LIVE switch changed","No credentials read","No broker evidence fabricated","No order or trading command submitted"],
            "still_required":["Same-user, same-broker, separately paired DEMO and LIVE profiles","Genuine 5-7 day DEMO PASS with order lifecycle, reconciliation, restart and risk evidence","Fresh LIVE MT5 identity, MASTER trade permission and authenticated EA heartbeat","Fresh risk telemetry within limits and clear news verdict","Password reauthentication and exact typed LIVE confirmation","Account-bound 15-minute LIVE authorization"]}

def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--management-url", required=True, help="Management API HTTPS origin")
    p.add_argument("--bridge-url", required=True, help="Persistent Windows Bridge private HTTPS origin")
    p.add_argument("--public-key-file", type=Path, help="Optional local public PEM to compare with the backend")
    p.add_argument("--write-public-key", type=Path, help="Optional output path for backend PUBLIC PEM")
    p.add_argument("--overwrite-public-key", action="store_true")
    p.add_argument("--broker", action="append", choices=BROKERS, help="Repeat for selected brands; default is all four")
    a = p.parse_args(argv)
    try: report = run(a.management_url, a.bridge_url, key_file=a.public_key_file, write_key=a.write_public_key, overwrite=a.overwrite_public_key, brokers=tuple(a.broker or BROKERS))
    except PreflightError as e:
        print(json.dumps({"overall_status":"BLOCKED","fatal_code":str(e)}, indent=2)); return 2
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["overall_status"] == "INFRASTRUCTURE_AND_TERMINAL_READY_LIVE_GATES_STILL_APPLY" else 2

if __name__ == "__main__": raise SystemExit(main())
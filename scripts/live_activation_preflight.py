#!/usr/bin/env python3
"""DestinedFX LIVE infrastructure preflight and explicit lease-arming tool.

The default preflight is HTTPS GET-only and never changes LIVE state. The optional
--arm-live path uses the existing one-time Bridge challenge, Management password
step-up, exact typed confirmation, and 15-minute authorization endpoint. It does
not submit or retry orders. Broker credentials are never requested; only the
user's existing app-pairing bearer and Management password reauthentication are
used for this account-specific authorization. The optional PEM output is PUBLIC.
"""
from __future__ import annotations
import argparse, getpass, hashlib, hmac, json, os, re, stat, tempfile, urllib.error, urllib.parse, urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable
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
        news_ready = config.get("news_provider_configured") is True
        checks.append({"name":"news_calendar_provider","status":"PASS" if news_ready else "BLOCKED","code":"NEWS_PROVIDER_CONFIGURED" if news_ready else "NEWS_PROVIDER_NOT_CONFIGURED","provider_kind":config.get("news_provider_kind","unconfigured")})
        linux_mode = config.get("linux_host_mode") is True
        isolation_status = config.get("multi_user_terminal_isolation")
        isolation_ready = isolation_status == "VERIFIED"
        persistent_live_host = persistent and (not linux_mode or isolation_ready)
        checks.append({
            "name":"persistent_live_host_type",
            "status":"PASS" if persistent_live_host else "BLOCKED",
            "code":"PERSISTENT_ISOLATED_LINUX_HOST" if linux_mode and persistent_live_host else "PERSISTENT_WINDOWS_OR_MANAGED_HOST" if persistent_live_host else "EPHEMERAL_HOST_OR_UNVERIFIED_LINUX_ISOLATION",
        })
        checks.append({"name":"multi_user_terminal_isolation","status":"PASS" if isolation_ready else "BLOCKED","code":"PER_USER_ISOLATION_VERIFIED" if isolation_ready else "MULTI_USER_OS_ISOLATION_NOT_VERIFIED"})
        active_slots = config.get("isolated_slots")
        max_slots = config.get("max_isolated_slots")
        dual_capacity = (
            isinstance(active_slots, int) and not isinstance(active_slots, bool) and active_slots >= 2
            and isinstance(max_slots, int) and not isinstance(max_slots, bool) and max_slots >= 2
        )
        checks.append({
            "name":"live_demo_and_live_terminal_capacity",
            "status":"PASS" if isolation_ready and dual_capacity else "BLOCKED",
            "code":"TWO_ISOLATED_TERMINALS_READY" if isolation_ready and dual_capacity else "LIVE_REQUIRES_TWO_VERIFIED_ISOLATED_TERMINALS",
            "active_slots":active_slots if isinstance(active_slots, int) and not isinstance(active_slots, bool) else 0,
            "max_slots":max_slots if isinstance(max_slots, int) and not isinstance(max_slots, bool) else 0,
        })
        bridge_fp = config.get("management_step_up_key_fingerprint_sha256")
        trust = bool(remote_fp and bridge_fp == remote_fp)
        checks.append({"name":"management_bridge_trust","status":"PASS" if trust else "BLOCKED","code":"PUBLIC_KEY_FINGERPRINT_MATCH" if trust else "BRIDGE_KEY_MISSING_OR_MISMATCHED","bridge_fingerprint_sha256":bridge_fp if isinstance(bridge_fp, str) else ""})
        issuer_match = bool(issuer and audience and config.get("management_step_up_issuer") == issuer and config.get("management_step_up_audience") == audience)
        checks.append({"name":"management_bridge_issuer_audience","status":"PASS" if issuer_match else "BLOCKED","code":"ISSUER_AUDIENCE_MATCH" if issuer_match else "ISSUER_AUDIENCE_MISMATCH"})
    except PreflightError as e:
        for name in ("bridge_config", "persistent_host", "durable_storage", "news_calendar_provider", "persistent_live_host_type", "multi_user_terminal_isolation", "live_demo_and_live_terminal_capacity", "management_bridge_trust", "management_bridge_issuer_audience"):
            checks.append({"name":name,"status":"BLOCKED","code":str(e) if name == "bridge_config" else "BRIDGE_CONFIG_UNAVAILABLE"})
    try:
        code, data = get_json(bridge + "/health/ready")
        host_runtime_ready = (
            code == 200 and data.get("status") == "ready"
        ) or (code == 503 and error_code(data) == "NO_VERIFIED_TERMINAL_PAIRING")
        checks.append({
            "name":"bridge_host_runtime",
            "status":"PASS" if host_runtime_ready else "BLOCKED",
            "code":"HOST_RUNTIME_READY" if host_runtime_ready else "HOST_RUNTIME_NOT_READY",
        })
        terminal_verified = (
            code == 200 and data.get("status") == "ready"
            and data.get("verified_terminal_pairing") == "healthy"
        )
        checks.append({
            "name":"verified_mt5_terminal",
            "status":"PASS" if terminal_verified else "PENDING" if host_runtime_ready else "BLOCKED",
            "code":"VERIFIED_TERMINAL_PAIRING" if terminal_verified else
                  error_code(data) if code >= 400 else
                  "HOST_READY_ACCOUNT_PAIRING_NOT_VERIFIED" if host_runtime_ready else "TERMINAL_NOT_READY",
        })
    except PreflightError: checks.append({"name":"verified_mt5_terminal","status":"PENDING","code":"BRIDGE_READINESS_UNREACHABLE"})
    chosen = tuple(dict.fromkeys(b.upper() for b in brokers))
    valid = bool(chosen) and all(b in BROKERS for b in chosen)
    checks.append({"name":"broker_code_paths","status":"PASS" if valid else "BLOCKED","code":"SOURCE_SUPPORT_ONLY_NOT_BROKER_ACCEPTANCE" if valid else "INVALID_BROKER_LIST","brokers":list(chosen)})
    blocked = any(c["status"] == "BLOCKED" for c in checks)
    pending = any(c["name"] == "verified_mt5_terminal" and c["status"] != "PASS" for c in checks)
    state = "BLOCKED" if blocked else "INFRASTRUCTURE_READY_ACCOUNT_ACCEPTANCE_PENDING" if pending else "INFRASTRUCTURE_AND_TERMINAL_READY_LIVE_GATES_STILL_APPLY"
    return {"schema_version":1,"overall_status":state,"checks":checks,
            "non_actions":["No LIVE switch changed","No credentials read","No broker evidence fabricated","No order or trading command submitted"],
            "still_required":["Same-user, same-broker, separately paired DEMO and LIVE profiles","Genuine 5-7 day DEMO PASS with order lifecycle, reconciliation, restart and risk evidence","Root-attested Linux per-pairing Unix identities, private Wine prefixes, terminal trees, displays and Xauthority; Windows portable directories under one shared identity do not qualify for multi-user LIVE","Two simultaneously active isolated MT5 terminals for the DEMO and LIVE profiles","Fresh LIVE MT5 identity, MASTER trade permission and authenticated EA heartbeat","Fresh risk telemetry within limits and clear news verdict","Password reauthentication and exact typed LIVE confirmation","Account-bound 15-minute LIVE authorization"]}



class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Refuse redirects so authenticated requests never forward bearer tokens."""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def authenticated_json(url: str, method: str, payload: dict[str, Any] | None = None,
                       bearer: str | None = None, timeout: float = 8.0) -> tuple[int, dict[str, Any]]:
    """Bounded HTTPS JSON client used only by the explicit LIVE lease flow."""
    try:
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
            raise PreflightError("AUTH_ENDPOINT_MUST_BE_HTTPS")
        if bearer is not None and (not bearer.strip() or any(ch in bearer for ch in "\r\n")):
            raise PreflightError("AUTH_TOKEN_INVALID")
        headers = {"Accept":"application/json","User-Agent":"DestinedFX-LIVE-Arm/1.0"}
        if bearer is not None:
            headers["Authorization"] = "Bearer " + bearer
        data = None
        if payload is not None:
            data = json.dumps(payload,separators=(",",":"),allow_nan=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url,data=data,headers=headers,method=method)
        opener = urllib.request.build_opener(NoRedirectHandler())
        try:
            response = opener.open(request,timeout=timeout)
        except urllib.error.HTTPError as exc:
            status = int(exc.code)
            raw = exc.read(MAX_BYTES+1)
        except (OSError,TimeoutError,urllib.error.URLError):
            raise PreflightError("AUTH_ENDPOINT_UNREACHABLE") from None
        else:
            with response:
                status = int(response.status)
                raw = response.read(MAX_BYTES+1)
    except PreflightError:
        raise
    except (ValueError,TypeError,UnicodeError):
        raise PreflightError("AUTH_REQUEST_INVALID") from None
    if status in (301,302,303,307,308):
        raise PreflightError("AUTH_REDIRECT_REFUSED")
    if len(raw)>MAX_BYTES:
        raise PreflightError("AUTH_RESPONSE_TOO_LARGE")
    try:
        value = json.loads(raw.decode("utf-8")) if raw else {}
    except (UnicodeError,json.JSONDecodeError):
        raise PreflightError("AUTH_RESPONSE_NOT_JSON") from None
    if not isinstance(value,dict):
        raise PreflightError("AUTH_RESPONSE_SCHEMA_INVALID")
    if status<200 or status>=300:
        raise PreflightError(error_code(value))
    return status,value


def arm_live(management_url: str, bridge_url: str, *,
             credential_provider: Callable[[], tuple[str, str, str]],
             confirm_typed_phrase: Callable[[str], bool]) -> dict[str, Any]:
    """Create only the existing 15-minute LIVE lease after all server-side gates pass.

    No trade/order endpoint is called. Tokens, passwords and signed assertions are
    kept in memory and are not returned or printed.
    """
    report = run(management_url,bridge_url)
    if report.get("overall_status") != "INFRASTRUCTURE_AND_TERMINAL_READY_LIVE_GATES_STILL_APPLY":
        raise PreflightError("LIVE_INFRASTRUCTURE_PREFLIGHT_BLOCKED")
    try:
        app_token, management_email, management_password = credential_provider()
    except Exception:
        raise PreflightError("LIVE_AUTH_INPUT_UNAVAILABLE") from None
    if not app_token or not management_email or not management_password:
        raise PreflightError("LIVE_AUTH_INPUT_MISSING")
    management = base_url(management_url,"MANAGEMENT")
    bridge = base_url(bridge_url,"BRIDGE")
    management_session = ""
    authorized_result: dict[str,Any] | None = None
    logout_confirmed = False
    try:
        _,login_data = authenticated_json(
            management+"/api/v1/auth/login","POST",
            {"email":management_email,"password":management_password},
        )
        management_session = str(login_data.get("access_token") or "")
        if len(management_session)<20:
            raise PreflightError("MANAGEMENT_LOGIN_RESPONSE_INVALID")

        _,challenge = authenticated_json(
            bridge+"/v1/app/live-challenge","POST",{},bearer=app_token,
        )
        pairing_id = str(challenge.get("pairing_id") or "")
        challenge_id = str(challenge.get("challenge_id") or "")
        phrase = str(challenge.get("typed_confirmation") or "")
        broker = str(challenge.get("broker_brand") or "").strip().upper()
        login = str(challenge.get("login") or "")
        server = str(challenge.get("server") or "")
        mode = str(challenge.get("account_mode") or "").strip().upper()
        generation = str(challenge.get("credential_generation") or "")
        expected_phrase = f"ARM LIVE {broker} {login}"
        if (not pairing_id or not challenge_id or broker not in BROKERS
                or not re.fullmatch(r"[0-9]{1,20}",login) or not server.strip()
                or mode!="LIVE" or not generation or phrase!=expected_phrase):
            raise PreflightError("LIVE_CHALLENGE_IDENTITY_INVALID")
        try:
            confirmed = confirm_typed_phrase(expected_phrase) is True
        except Exception:
            confirmed = False
        if not confirmed:
            raise PreflightError("LIVE_TYPED_CONFIRMATION_MISMATCH")

        _,step_up = authenticated_json(
            management+"/api/v1/auth/live-step-up","POST",
            {
                "password":management_password,
                "pairing_id":pairing_id,
                "challenge_id":challenge_id,
                "typed_confirmation":expected_phrase,
                "broker_brand":broker,
                "login":login,
                "server":server,
                "account_mode":mode,
                "credential_generation":generation,
            },
            bearer=management_session,
        )
        assertion = str(step_up.get("assertion") or "")
        if len(assertion)<100:
            raise PreflightError("LIVE_STEP_UP_ASSERTION_INVALID")

        _,authorization = authenticated_json(
            bridge+"/v1/app/live-authorization","POST",
            {
                "challenge_id":challenge_id,
                "typed_confirmation":expected_phrase,
                "step_up_assertion":assertion,
            },
            bearer=app_token,
        )
        if authorization.get("authorized") is not True or authorization.get("duration_seconds")!=900:
            raise PreflightError("LIVE_AUTHORIZATION_NOT_CONFIRMED")
        expires = str(authorization.get("expires_at") or "")
        try:
            expiry = datetime.fromisoformat(expires.replace("Z","+00:00"))
        except ValueError:
            raise PreflightError("LIVE_AUTHORIZATION_EXPIRY_INVALID") from None
        if expiry.tzinfo is None or expiry<=datetime.now(timezone.utc):
            raise PreflightError("LIVE_AUTHORIZATION_EXPIRY_INVALID")
        authorized_result = {
            "authorized":True,"expires_at":expires,"duration_seconds":900,
            "broker_brand":broker,"login":login,
        }
    finally:
        if management_session:
            try:
                authenticated_json(management+"/api/v1/auth/logout","POST",{},bearer=management_session)
                logout_confirmed = True
            except PreflightError:
                logout_confirmed = False

    if authorized_result is None:
        raise PreflightError("LIVE_AUTHORIZATION_NOT_CONFIRMED")
    authorized_result["management_session_logout_confirmed"] = logout_confirmed
    return authorized_result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--management-url",required=True,help="Management API HTTPS origin")
    parser.add_argument("--bridge-url",required=True,help="Persistent Linux production Bridge HTTPS origin (the legacy Windows worker remains multi-user LIVE blocked)")
    parser.add_argument("--public-key-file",type=Path,help="Optional local public PEM to compare with the backend")
    parser.add_argument("--write-public-key",type=Path,help="Optional output path for backend PUBLIC PEM")
    parser.add_argument("--overwrite-public-key",action="store_true")
    parser.add_argument("--broker",action="append",choices=BROKERS,help="Repeat for selected brands; default is all four")
    parser.add_argument("--arm-live",action="store_true",
        help="After a fully green infrastructure preflight, perform password reauthentication and exact typed LIVE confirmation. Never submits an order.")
    args = parser.parse_args(argv)
    try:
        report = run(args.management_url,args.bridge_url,key_file=args.public_key_file,
                     write_key=args.write_public_key,overwrite=args.overwrite_public_key,
                     brokers=tuple(args.broker or BROKERS))
    except PreflightError as exc:
        print(json.dumps({"overall_status":"BLOCKED","fatal_code":str(exc)},indent=2))
        return 2
    print(json.dumps(report,indent=2,sort_keys=True))
    if report["overall_status"]!="INFRASTRUCTURE_AND_TERMINAL_READY_LIVE_GATES_STILL_APPLY":
        return 2
    if not args.arm_live:
        return 0

    print("This action creates only the existing 15-minute account-bound LIVE authorization lease. It does not place an order.")
    def credentials() -> tuple[str, str, str]:
        app_token = os.environ.get("DESTINEDFX_APP_BEARER_TOKEN","").strip()
        if not app_token:
            app_token = getpass.getpass("LIVE profile app-pairing bearer token (hidden): ").strip()
        email = input("DestinedFX management account email: ").strip()
        password = getpass.getpass("DestinedFX management password (hidden): ")
        return app_token,email,password
    def confirm(phrase: str) -> bool:
        print("Type this exact account-specific phrase:")
        print(phrase)
        return hmac.compare_digest(input("> ").strip(),phrase)
    try:
        result = arm_live(args.management_url,args.bridge_url,credential_provider=credentials,
                          confirm_typed_phrase=confirm)
    except PreflightError as exc:
        print(json.dumps({"live_authorization":"BLOCKED","code":str(exc)},indent=2))
        return 2
    print(json.dumps({"live_authorization":result},indent=2,sort_keys=True))
    if not result["management_session_logout_confirmed"]:
        print("WARNING: The LIVE lease was confirmed but Management session logout was not confirmed. Treat the session as potentially active until expiry.")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

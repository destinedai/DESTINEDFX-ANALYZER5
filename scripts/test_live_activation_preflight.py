from pathlib import Path
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from scripts import live_activation_preflight as preflight

KEY = Ed25519PrivateKey.generate().public_key().public_bytes(
    serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
FP = preflight.fingerprint(KEY)

def fixture_responses():
    return {
        "https://management.owner.ts.net/ready": (200, {"status":"ready"}),
        "https://management.owner.ts.net/api/v1/auth/bridge-verification-key": (200, {
            "algorithm":"EdDSA", "public_key_pem":KEY,
            "issuer":preflight.ISSUER, "audience":preflight.AUDIENCE}),
        "https://bridge.owner.ts.net/health/config": (200, {
            "status":"configured", "host_profile":"PERSISTENT", "database":"ok",
            "managed_execution":"configured", "news_provider_configured":True,
            "news_provider_kind":"showmytrades-public-calendar", "multi_user_terminal_isolation":"VERIFIED",
            "isolated_slots":2, "max_isolated_slots":2,
            "linux_host_mode":False, "management_step_up_key_fingerprint_sha256":FP,
            "management_step_up_issuer":preflight.ISSUER, "management_step_up_audience":preflight.AUDIENCE}),
        "https://bridge.owner.ts.net/health/ready": (200, {"status":"ready"}),
    }

def test_preflight_accepts_matching_persistent_bridge(monkeypatch):
    data=fixture_responses(); monkeypatch.setattr(preflight,"get_json",lambda url:data[url])
    result=preflight.run("https://management.owner.ts.net","https://bridge.owner.ts.net")
    assert result["overall_status"] == "INFRASTRUCTURE_AND_TERMINAL_READY_LIVE_GATES_STILL_APPLY"
    assert all(row["status"] == "PASS" for row in result["checks"])
    assert result["checks"][-1]["brokers"] == ["FBS","EXNESS","HFM","DERIV"]

def test_preflight_blocks_key_mismatch(monkeypatch):
    data=fixture_responses(); data["https://bridge.owner.ts.net/health/config"][1]["management_step_up_key_fingerprint_sha256"]="0"*64
    monkeypatch.setattr(preflight,"get_json",lambda url:data[url])
    result=preflight.run("https://management.owner.ts.net","https://bridge.owner.ts.net")
    assert result["overall_status"] == "BLOCKED"
    assert next(x for x in result["checks"] if x["name"]=="management_bridge_trust")["code"] == "BRIDGE_KEY_MISSING_OR_MISMATCHED"

def test_preflight_blocks_ephemeral_host(monkeypatch):
    data=fixture_responses(); data["https://bridge.owner.ts.net/health/config"][1]["host_profile"]="EPHEMERAL"
    monkeypatch.setattr(preflight,"get_json",lambda url:data[url])
    result=preflight.run("https://management.owner.ts.net","https://bridge.owner.ts.net")
    assert result["overall_status"] == "BLOCKED"

def test_preflight_blocks_when_news_provider_is_missing(monkeypatch):
    data=fixture_responses()
    data["https://bridge.owner.ts.net/health/config"][1]["news_provider_configured"]=False
    data["https://bridge.owner.ts.net/health/config"][1]["news_provider_kind"]="unconfigured"
    monkeypatch.setattr(preflight,"get_json",lambda url:data[url])
    result=preflight.run("https://management.owner.ts.net","https://bridge.owner.ts.net")
    row=next(x for x in result["checks"] if x["name"]=="news_calendar_provider")
    assert row["status"] == "BLOCKED"
    assert row["code"] == "NEWS_PROVIDER_NOT_CONFIGURED"
    assert result["overall_status"] == "BLOCKED"


def test_preflight_blocks_windows_host_without_multi_user_os_isolation(monkeypatch):
    data=fixture_responses()
    data["https://bridge.owner.ts.net/health/config"][1]["multi_user_terminal_isolation"]="NOT_VERIFIED"
    monkeypatch.setattr(preflight,"get_json",lambda url:data[url])
    result=preflight.run("https://management.owner.ts.net","https://bridge.owner.ts.net")
    row=next(x for x in result["checks"] if x["name"]=="multi_user_terminal_isolation")
    assert row["status"] == "BLOCKED"
    assert row["code"] == "MULTI_USER_OS_ISOLATION_NOT_VERIFIED"
    assert result["overall_status"] == "BLOCKED"


def test_preflight_accepts_persistent_root_attested_linux_host(monkeypatch):
    data=fixture_responses()
    data["https://bridge.owner.ts.net/health/config"][1]["linux_host_mode"]=True
    monkeypatch.setattr(preflight,"get_json",lambda url:data[url])
    result=preflight.run("https://management.owner.ts.net","https://bridge.owner.ts.net")
    row=next(x for x in result["checks"] if x["name"]=="persistent_live_host_type")
    assert row["status"] == "PASS"
    assert row["code"] == "PERSISTENT_ISOLATED_LINUX_HOST"
    assert result["overall_status"] == "INFRASTRUCTURE_AND_TERMINAL_READY_LIVE_GATES_STILL_APPLY"


def test_preflight_blocks_ephemeral_linux_host_even_with_root_attestation(monkeypatch):
    data=fixture_responses()
    data["https://bridge.owner.ts.net/health/config"][1]["linux_host_mode"]=True
    data["https://bridge.owner.ts.net/health/config"][1]["host_profile"]="EPHEMERAL"
    monkeypatch.setattr(preflight,"get_json",lambda url:data[url])
    result=preflight.run("https://management.owner.ts.net","https://bridge.owner.ts.net")
    row=next(x for x in result["checks"] if x["name"]=="persistent_live_host_type")
    assert row["status"] == "BLOCKED"
    assert row["code"] == "EPHEMERAL_HOST_OR_UNVERIFIED_LINUX_ISOLATION"


def test_missing_terminal_readiness_is_pending_not_a_false_pass(monkeypatch):
    data=fixture_responses(); data["https://bridge.owner.ts.net/health/ready"]=(503,{"detail":{"code":"NO_VERIFIED_TERMINAL_PAIRING"}})
    monkeypatch.setattr(preflight,"get_json",lambda url:data[url])
    result=preflight.run("https://management.owner.ts.net","https://bridge.owner.ts.net")
    row=next(x for x in result["checks"] if x["name"]=="verified_mt5_terminal")
    assert row["status"] == "PENDING" and row["code"] == "NO_VERIFIED_TERMINAL_PAIRING"
    assert result["overall_status"] == "INFRASTRUCTURE_READY_ACCOUNT_ACCEPTANCE_PENDING"

def test_urls_require_https_and_no_credentials():
    with pytest.raises(preflight.PreflightError): preflight.base_url("http://bridge.example","BRIDGE")
    with pytest.raises(preflight.PreflightError): preflight.base_url("https://user:secret@bridge.example","BRIDGE")

def test_public_key_writer_never_overwrites_without_flag(tmp_path: Path):
    path=tmp_path/"bridge-public.pem"; preflight.write_public_key(path,KEY)
    assert preflight.fingerprint(path.read_bytes()) == FP
    with pytest.raises(preflight.PreflightError): preflight.write_public_key(path,KEY)


def test_preflight_blocks_when_live_cannot_run_two_verified_terminals(monkeypatch):
    data=fixture_responses()
    data["https://bridge.owner.ts.net/health/config"][1]["isolated_slots"]=1
    data["https://bridge.owner.ts.net/health/config"][1]["max_isolated_slots"]=1
    monkeypatch.setattr(preflight,"get_json",lambda url:data[url])
    result=preflight.run("https://management.owner.ts.net","https://bridge.owner.ts.net")
    row=next(x for x in result["checks"] if x["name"]=="live_demo_and_live_terminal_capacity")
    assert row["status"]=="BLOCKED"
    assert row["code"]=="LIVE_REQUIRES_TWO_VERIFIED_ISOLATED_TERMINALS"


def test_arm_live_refuses_before_requesting_any_credential_when_preflight_is_blocked(monkeypatch):
    monkeypatch.setattr(preflight,"run",lambda *a,**k:{"overall_status":"BLOCKED","checks":[]})
    requested=[]
    def credentials():
        requested.append(True)
        return "test-app-token","owner@example.net","not-real"
    with pytest.raises(preflight.PreflightError,match="LIVE_INFRASTRUCTURE_PREFLIGHT_BLOCKED"):
        preflight.arm_live(
            "https://management.owner.ts.net","https://bridge.owner.ts.net",
            credential_provider=credentials,
            confirm_typed_phrase=lambda phrase:True,
        )
    assert requested==[]


def test_arm_live_uses_existing_challenge_step_up_and_authorization_only(monkeypatch):
    monkeypatch.setattr(preflight,"run",lambda *a,**k:{
        "overall_status":"INFRASTRUCTURE_AND_TERMINAL_READY_LIVE_GATES_STILL_APPLY","checks":[]})
    calls=[]
    responses=[
        (200,{"access_token":"management-session-token-long-enough"}),
        (200,{"pairing_id":"pair-live","challenge_id":"challenge-live",
              "typed_confirmation":"ARM LIVE FBS 700001","broker_brand":"FBS",
              "login":"700001","server":"Broker-Live","account_mode":"LIVE",
              "credential_generation":"credential-generation-live"}),
        (200,{"assertion":"A"*180}),
        (200,{"authorized":True,"duration_seconds":900,"expires_at":"2099-01-01T00:00:00Z"}),
        (204,{}),
    ]
    def fake_request(url,method,payload=None,bearer=None,timeout=8.0):
        calls.append((url,method,payload,bearer))
        return responses.pop(0)
    monkeypatch.setattr(preflight,"authenticated_json",fake_request)
    result=preflight.arm_live(
        "https://management.owner.ts.net","https://bridge.owner.ts.net",
        credential_provider=lambda:("app-pair-token-test","owner@example.net","management-password-test"),
        confirm_typed_phrase=lambda phrase:phrase=="ARM LIVE FBS 700001",
    )
    assert result["authorized"] is True and result["duration_seconds"]==900
    assert result["management_session_logout_confirmed"] is True
    assert [call[1] for call in calls]==["POST"]*5
    assert calls[0][0].endswith("/api/v1/auth/login")
    assert calls[1][0].endswith("/v1/app/live-challenge")
    assert calls[2][0].endswith("/api/v1/auth/live-step-up")
    assert calls[3][0].endswith("/v1/app/live-authorization")
    assert calls[4][0].endswith("/api/v1/auth/logout")
    assert calls[2][2]["credential_generation"]=="credential-generation-live"
    assert calls[3][2]["typed_confirmation"]=="ARM LIVE FBS 700001"
    assert all("/commands" not in call[0] and "/orders" not in call[0] for call in calls)
    assert not ({"management_password","app_token","assertion"} & set(result))


def test_arm_live_rejects_wrong_typed_confirmation_and_logs_out(monkeypatch):
    monkeypatch.setattr(preflight,"run",lambda *a,**k:{
        "overall_status":"INFRASTRUCTURE_AND_TERMINAL_READY_LIVE_GATES_STILL_APPLY","checks":[]})
    calls=[]
    responses=[
        (200,{"access_token":"management-session-token-long-enough"}),
        (200,{"pairing_id":"pair-live","challenge_id":"challenge-live",
              "typed_confirmation":"ARM LIVE FBS 700001","broker_brand":"FBS",
              "login":"700001","server":"Broker-Live","account_mode":"LIVE",
              "credential_generation":"credential-generation-live"}),
        (204,{}),
    ]
    def fake_request(url,method,payload=None,bearer=None,timeout=8.0):
        calls.append((url,method,payload,bearer))
        return responses.pop(0)
    monkeypatch.setattr(preflight,"authenticated_json",fake_request)
    with pytest.raises(preflight.PreflightError,match="LIVE_TYPED_CONFIRMATION_MISMATCH"):
        preflight.arm_live(
            "https://management.owner.ts.net","https://bridge.owner.ts.net",
            credential_provider=lambda:("app-pair-token-test","owner@example.net","management-password-test"),
            confirm_typed_phrase=lambda phrase:False,
        )
    assert len(calls)==3 and calls[-1][0].endswith("/api/v1/auth/logout")


def test_arm_live_challenge_must_be_exact_live_identity(monkeypatch):
    monkeypatch.setattr(preflight,"run",lambda *a,**k:{
        "overall_status":"INFRASTRUCTURE_AND_TERMINAL_READY_LIVE_GATES_STILL_APPLY","checks":[]})
    calls=[]
    responses=[
        (200,{"access_token":"management-session-token-long-enough"}),
        (200,{"pairing_id":"pair-live","challenge_id":"challenge-live",
              "typed_confirmation":"ARM LIVE FBS 700001","broker_brand":"FBS",
              "login":"700001","server":"Broker-Live","account_mode":"DEMO",
              "credential_generation":"credential-generation-live"}),
        (204,{}),
    ]
    def fake_request(url,method,payload=None,bearer=None,timeout=8.0):
        calls.append((url,method,payload,bearer))
        return responses.pop(0)
    monkeypatch.setattr(preflight,"authenticated_json",fake_request)
    with pytest.raises(preflight.PreflightError,match="LIVE_CHALLENGE_IDENTITY_INVALID"):
        preflight.arm_live(
            "https://management.owner.ts.net","https://bridge.owner.ts.net",
            credential_provider=lambda:("app-pair-token-test","owner@example.net","management-password-test"),
            confirm_typed_phrase=lambda phrase:True,
        )
    assert len(calls)==3 and calls[-1][0].endswith("/api/v1/auth/logout")


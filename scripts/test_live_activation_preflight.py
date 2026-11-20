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
        "https://management.example/ready": (200, {"status":"ready"}),
        "https://management.example/api/v1/auth/bridge-verification-key": (200, {
            "algorithm":"EdDSA", "public_key_pem":KEY,
            "issuer":preflight.ISSUER, "audience":preflight.AUDIENCE}),
        "https://bridge.example.ts.net/health/config": (200, {
            "status":"configured", "host_profile":"PERSISTENT", "database":"ok",
            "managed_execution":"configured", "management_step_up_key_fingerprint_sha256":FP,
            "management_step_up_issuer":preflight.ISSUER, "management_step_up_audience":preflight.AUDIENCE}),
        "https://bridge.example.ts.net/health/ready": (200, {"status":"ready"}),
    }

def test_preflight_accepts_matching_persistent_bridge(monkeypatch):
    data=fixture_responses(); monkeypatch.setattr(preflight,"get_json",lambda url:data[url])
    result=preflight.run("https://management.example","https://bridge.example.ts.net")
    assert result["overall_status"] == "INFRASTRUCTURE_AND_TERMINAL_READY_LIVE_GATES_STILL_APPLY"
    assert all(row["status"] == "PASS" for row in result["checks"])
    assert result["checks"][-1]["brokers"] == ["FBS","EXNESS","HFM","DERIV"]

def test_preflight_blocks_key_mismatch(monkeypatch):
    data=fixture_responses(); data["https://bridge.example.ts.net/health/config"][1]["management_step_up_key_fingerprint_sha256"]="0"*64
    monkeypatch.setattr(preflight,"get_json",lambda url:data[url])
    result=preflight.run("https://management.example","https://bridge.example.ts.net")
    assert result["overall_status"] == "BLOCKED"
    assert next(x for x in result["checks"] if x["name"]=="management_bridge_trust")["code"] == "BRIDGE_KEY_MISSING_OR_MISMATCHED"

def test_preflight_blocks_ephemeral_host(monkeypatch):
    data=fixture_responses(); data["https://bridge.example.ts.net/health/config"][1]["host_profile"]="EPHEMERAL"
    monkeypatch.setattr(preflight,"get_json",lambda url:data[url])
    result=preflight.run("https://management.example","https://bridge.example.ts.net")
    assert result["overall_status"] == "BLOCKED"

def test_missing_terminal_readiness_is_pending_not_a_false_pass(monkeypatch):
    data=fixture_responses(); data["https://bridge.example.ts.net/health/ready"]=(503,{"detail":{"code":"NO_VERIFIED_TERMINAL_PAIRING"}})
    monkeypatch.setattr(preflight,"get_json",lambda url:data[url])
    result=preflight.run("https://management.example","https://bridge.example.ts.net")
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
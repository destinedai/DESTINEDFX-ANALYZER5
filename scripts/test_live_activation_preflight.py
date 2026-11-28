from scripts import live_activation_preflight as preflight


def fixture_responses():
    return {
        "https://bridge.owner.ts.net/health/config": (200, {
            "status": "configured",
            "host_profile": "EPHEMERAL",
            "database": "ok",
            "managed_execution": "configured",
            "multi_user_terminal_isolation": "VERIFIED",
            "isolated_slots": 0,
            "max_isolated_slots": 1,
            "linux_host_mode": True,
        }),
        "https://bridge.owner.ts.net/health/ready": (200, {
            "status": "ready",
            "verified_terminal_pairing": "healthy",
        }),
    }


def test_preflight_accepts_ephemeral_single_slot_without_backup_or_demo_pair(monkeypatch):
    data = fixture_responses()
    monkeypatch.setattr(preflight, "get_json", lambda url: data[url])
    result = preflight.run("", "https://bridge.owner.ts.net")
    assert result["overall_status"] == "READY_FOR_REAL_CONFIRMATION"
    assert all(row["status"] == "PASS" for row in result["checks"])
    assert result["host_profile"] == "EPHEMERAL"
    assert any("CONFIRM REAL TRADE" in item for item in result["required_for_REAL"])


def test_preflight_allows_login_before_real_terminal_is_online(monkeypatch):
    data = fixture_responses()
    data["https://bridge.owner.ts.net/health/ready"] = (
        503, {"detail": {"code": "NO_VERIFIED_TERMINAL_PAIRING"}}
    )
    monkeypatch.setattr(preflight, "get_json", lambda url: data[url])
    result = preflight.run("", "https://bridge.owner.ts.net")
    assert result["overall_status"] == "READY_FOR_REAL_LOGIN"
    row = next(row for row in result["checks"] if row["name"] == "real_mt5_terminal")
    assert row["status"] == "PENDING"


def test_preflight_blocks_when_per_user_isolation_is_not_verified(monkeypatch):
    data = fixture_responses()
    data["https://bridge.owner.ts.net/health/config"][1]["multi_user_terminal_isolation"] = "NOT_VERIFIED"
    monkeypatch.setattr(preflight, "get_json", lambda url: data[url])
    result = preflight.run("", "https://bridge.owner.ts.net")
    row = next(row for row in result["checks"] if row["name"] == "per_user_terminal_isolation")
    assert row["status"] == "BLOCKED"
    assert result["overall_status"] == "BLOCKED"


def test_preflight_needs_one_slot_not_two(monkeypatch):
    data = fixture_responses()
    config = data["https://bridge.owner.ts.net/health/config"][1]
    config["max_isolated_slots"] = 1
    monkeypatch.setattr(preflight, "get_json", lambda url: data[url])
    assert preflight.run("", "https://bridge.owner.ts.net")["overall_status"] == "READY_FOR_REAL_CONFIRMATION"
    config["max_isolated_slots"] = 0
    assert preflight.run("", "https://bridge.owner.ts.net")["overall_status"] == "BLOCKED"


def test_arm_live_uses_only_app_bearer_and_exact_confirmation(monkeypatch):
    calls = []
    responses = [
        (200, {
            "pairing_id": "pair-live", "challenge_id": "challenge-live",
            "typed_confirmation": "CONFIRM REAL TRADE", "broker_brand": "FBS",
            "login": "700001", "server": "Broker-Live", "account_mode": "LIVE",
        }),
        (200, {"authorized": True, "duration_seconds": None, "expires_at": "2099-01-01T00:00:00Z"}),
    ]
    monkeypatch.setattr(preflight, "authenticated_json",
        lambda url, method, payload=None, bearer=None, timeout=8.0:
            (calls.append((url, method, payload, bearer)) or responses.pop(0)))
    result = preflight.arm_live(
        "https://bridge.owner.ts.net",
        credential_provider=lambda: "app-pair-token-test",
        confirm_typed_phrase=lambda phrase: phrase == "CONFIRM REAL TRADE",
    )
    assert result["authorized"] is True
    assert result["duration_seconds"] is None
    assert [call[0].rsplit("/", 2)[-2:] for call in calls] == [
        ["v1", "app"], ["v1", "app"]
    ] or len(calls) == 2
    assert calls[0][3] == calls[1][3] == "app-pair-token-test"
    assert calls[1][2] == {
        "challenge_id": "challenge-live", "typed_confirmation": "CONFIRM REAL TRADE"
    }


def test_arm_live_rejects_wrong_phrase_without_authorization_call(monkeypatch):
    calls = []
    def fake_request(url, method, payload=None, bearer=None, timeout=8.0):
        calls.append(url)
        return 200, {
            "pairing_id": "pair-live", "challenge_id": "challenge-live",
            "typed_confirmation": "CONFIRM REAL TRADE", "broker_brand": "FBS",
            "login": "700001", "server": "Broker-Live", "account_mode": "LIVE",
        }
    monkeypatch.setattr(preflight, "authenticated_json", fake_request)
    import pytest
    with pytest.raises(preflight.PreflightError, match="LIVE_TYPED_CONFIRMATION_MISMATCH"):
        preflight.arm_live("https://bridge.owner.ts.net",
            credential_provider=lambda: "app-pair-token-test",
            confirm_typed_phrase=lambda phrase: False)
    assert len(calls) == 1


def test_arm_live_rejects_non_real_terminal_mode(monkeypatch):
    calls = []
    def fake_request(url, method, payload=None, bearer=None, timeout=8.0):
        calls.append(url)
        return 200, {
            "pairing_id": "pair-live", "challenge_id": "challenge-live",
            "typed_confirmation": "CONFIRM REAL TRADE", "broker_brand": "FBS",
            "login": "700001", "server": "Broker-Live", "account_mode": "DEMO",
        }
    monkeypatch.setattr(preflight, "authenticated_json", fake_request)
    import pytest
    with pytest.raises(preflight.PreflightError, match="LIVE_CHALLENGE_IDENTITY_INVALID"):
        preflight.arm_live("https://bridge.owner.ts.net",
            credential_provider=lambda: "app-pair-token-test",
            confirm_typed_phrase=lambda phrase: True)
    assert len(calls) == 1


def test_urls_require_https_and_no_credentials():
    import pytest
    with pytest.raises(preflight.PreflightError):
        preflight.base_url("http://bridge.example", "BRIDGE")
    with pytest.raises(preflight.PreflightError):
        preflight.base_url("https://user:secret@bridge.example", "BRIDGE")

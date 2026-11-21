def register(client, email="alice@example.com", owner_setup_token=None):
    body = {"email": email, "password": "Correct horse battery staple 92!"}
    if owner_setup_token is not None:
        body["owner_setup_token"] = owner_setup_token
    return client.post("/api/v1/auth/register", json=body)

def login(client, email="alice@example.com"):
    return client.post("/api/v1/auth/login", json={"email": email, "password": "Correct horse battery staple 92!"})


def test_owner_registration_requires_host_issued_token_and_is_single_admin(client, monkeypatch):
    from app import main
    from pydantic import SecretStr

    monkeypatch.setattr(main.settings, "admin_emails", "owner@example.com,second-owner@example.com")
    monkeypatch.setattr(main.settings, "owner_setup_token", SecretStr("owner-setup-" + "x" * 64))

    assert register(client, "owner@example.com").status_code == 403
    assert register(client, "owner@example.com", "wrong-" + "x" * 64).status_code == 403

    response = register(client, "owner@example.com", "owner-setup-" + "x" * 64)
    assert response.status_code == 201
    assert response.json()["email"] == "owner@example.com"

    assert register(client, "second-owner@example.com", "owner-setup-" + "x" * 64).status_code == 409
    assert register(client, "member@example.com").status_code == 201
    token = login(client, "owner@example.com").json()["access_token"]
    assert client.get("/api/v1/admin/audit-events", headers={"Authorization": "Bearer " + token}).status_code == 200
    member_token = login(client, "member@example.com").json()["access_token"]
    assert client.get("/api/v1/admin/audit-events", headers={"Authorization": "Bearer " + member_token}).status_code == 403


def test_auth_preferences_isolation_and_revoke(client):
    assert register(client).status_code == 201
    assert register(client, "bob@example.com").status_code == 201
    a, b = login(client).json(), login(client, "bob@example.com").json()
    ah = {"Authorization": "Bearer " + a["access_token"]}
    bh = {"Authorization": "Bearer " + b["access_token"]}
    assert client.put("/api/v1/preferences/theme", json={"value": {"mode": "dark"}}, headers=ah).status_code == 200
    assert client.get("/api/v1/preferences", headers=bh).json() == {}
    assert client.get("/api/v1/auth/me", headers=ah).json()["email"] == "alice@example.com"
    assert client.post("/api/v1/auth/logout", headers=ah).status_code == 204
    assert client.get("/api/v1/auth/me", headers=ah).status_code == 401

def test_device_notifications_and_public_allowlist(client):
    register(client)
    token = login(client).json()["access_token"]
    h = {"Authorization": "Bearer " + token}
    assert client.put("/api/v1/devices", json={"device_id": "phone-1", "platform": "android"}, headers=h).status_code == 200
    assert client.put("/api/v1/notification-preferences", json={"value": {"push": False}}, headers=h).json() == {"push": False}
    cfg = client.get("/api/v1/app-config").json()
    assert set(cfg) == {"minimum_supported_version", "maintenance"}
    assert "https://example.invalid" not in str(cfg)

def test_validation_correlation_and_health(client):
    r = client.post("/api/v1/auth/register", json={"email": "bad", "password": "short"})
    assert r.status_code == 422
    assert r.headers["X-Correlation-ID"]
    assert client.get("/health").json() == {"status": "ok"}
    assert client.get("/api/v1/admin/audit-events").status_code == 401
    assert client.get("/api/v1/service-status").json()["database"] == "connected"

def test_service_status_reports_database_failure(client, monkeypatch):
    from app import main
    from sqlalchemy.orm import Session
    original_execute = Session.execute
    def unavailable(self, *args, **kwargs):
        raise RuntimeError("database unavailable")
    monkeypatch.setattr(Session, "execute", unavailable)
    response = client.get("/api/v1/service-status")
    assert response.status_code == 503
    assert response.json()["status"] == "degraded"
    assert response.json()["database"] == "unavailable"
    monkeypatch.setattr(Session, "execute", original_execute)


def test_live_step_up_requires_reauthentication_and_returns_signed_assertion(client, monkeypatch):
    from app import main
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    key = Ed25519PrivateKey.generate()
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode()
    monkeypatch.setattr(main.settings, "step_up_private_key_pem", type(main.settings.step_up_private_key_pem)(pem))
    register(client)
    token = login(client).json()["access_token"]
    headers = {"Authorization": "Bearer " + token}
    body = {
        "password": "Correct horse battery staple 92!",
        "pairing_id": "pairing-1",
        "challenge_id": "challenge-1",
        "typed_confirmation": "ARM LIVE FBS 700001",
        "broker_brand": "FBS",
        "login": "700001",
        "server": "FBS-Live",
        "account_mode": "LIVE",
        "credential_generation": "generation-1",
    }
    response = client.post("/api/v1/auth/live-step-up", json=body, headers=headers)
    assert response.status_code == 200
    assert len(response.json()["assertion"]) >= 100
    body["password"] = "wrong password"
    assert client.post("/api/v1/auth/live-step-up", json=body, headers=headers).status_code == 401



def test_bridge_verification_key_endpoint_publishes_only_ed25519_public_key(monkeypatch, client):
    from app import main
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

    key = Ed25519PrivateKey.generate()
    private_pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ).decode()
    monkeypatch.setattr(
        main.settings,
        "step_up_private_key_pem",
        type(main.settings.step_up_private_key_pem)(private_pem),
    )
    response = client.get("/api/v1/auth/bridge-verification-key")
    assert response.status_code == 200
    payload = response.json()
    assert payload["algorithm"] == "EdDSA"
    assert payload["issuer"] == main.settings.step_up_issuer
    assert payload["audience"] == main.settings.step_up_audience
    assert "PRIVATE KEY" not in payload["public_key_pem"]
    exported = serialization.load_pem_public_key(payload["public_key_pem"].encode())
    assert exported.public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    ) == key.public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )


def test_bridge_verification_key_endpoint_fails_closed_without_signing_key(monkeypatch, client):
    from app import main
    monkeypatch.setattr(
        main.settings,
        "step_up_private_key_pem",
        type(main.settings.step_up_private_key_pem)(""),
    )
    response = client.get("/api/v1/auth/bridge-verification-key")
    assert response.status_code == 503

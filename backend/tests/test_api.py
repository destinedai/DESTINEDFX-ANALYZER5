def register(client, email="alice@example.com"):
    return client.post("/api/v1/auth/register", json={"email": email, "password": "Correct horse battery staple 92!"})

def login(client, email="alice@example.com"):
    return client.post("/api/v1/auth/login", json={"email": email, "password": "Correct horse battery staple 92!"})

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

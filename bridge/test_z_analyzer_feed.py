from __future__ import annotations

import hashlib
import time

from fastapi.testclient import TestClient

import app as bridge


def _reset_feed():
    with bridge._ANALYZER_FEED_LOCK:
        bridge._ANALYZER_FEED_STATE.update({
            "heartbeat_at_ms": 0,
            "terminal_connected": False,
            "account_identity_sha256": "",
            "symbols": [],
            "broker_quotes": [],
            "broker_candles": [],
        })
        bridge._ANALYZER_MARKET_SELECTION.update({"symbol": "", "timeframe": "M15"})


def test_analyzer_feed_is_independent_of_bot_pairing_and_returns_terminal_prices():
    _reset_feed()
    now_ms = int(time.time() * 1000)
    client = TestClient(bridge.app, base_url="https://bridge.test",
                        client=("127.0.0.1", 49152))

    selected = client.post("/v1/analyzer/market-selection", json={
        "symbol": "XAUUSD.a", "timeframe": "M15",
    })
    assert selected.status_code == 200
    assert selected.json()["accepted"] is True

    pushed = client.post("/v1/analyzer/market-heartbeat", json={
        "terminal_connected": True,
        "account_login": "12345678",
        "account_server": "Broker-Demo",
        "account_mode": "DEMO",
        "symbols": ["XAUUSD.a", "EURUSD", "Volatility 75 Index"],
        "broker_quotes": [
            {"symbol": "XAUUSD.a", "bid": 3800.12, "ask": 3800.42,
             "digits": 2, "timestamp_ms": now_ms},
            {"symbol": "EURUSD", "bid": 1.08501, "ask": 1.08514,
             "digits": 5, "timestamp_ms": now_ms},
        ],
        "broker_candles": [
            {"symbol": "XAUUSD.a", "timeframe": "M15",
             "timestamp_ms": now_ms - 900_000, "open": 3799.0,
             "high": 3801.0, "low": 3798.0, "close": 3800.0,
             "volume": 120.0, "complete": True},
        ],
    })
    assert pushed.status_code == 200, pushed.text
    assert pushed.json()["accepted"] is True

    state = client.get("/v1/analyzer/market-state")
    assert state.status_code == 200
    payload = state.json()
    assert payload["connected"] is True
    assert payload["account_identity_sha256"] == hashlib.sha256(b"12345678|Broker-Demo|DEMO").hexdigest()
    assert "account_login" not in payload and "account_server" not in payload
    assert payload["selected_symbol"] == "XAUUSD.a"
    assert payload["selected_timeframe"] == "M15"
    assert "Volatility 75 Index" in payload["symbols"]
    gold = next(item for item in payload["broker_quotes"] if item["symbol"] == "XAUUSD.a")
    assert gold["bid"] == 3800.12
    assert gold["ask"] == 3800.42
    assert gold["source"] == "MT5_TERMINAL"
    assert payload["broker_candles"][0]["source"] == "MT5_TERMINAL"


def test_analyzer_feed_rejects_a_connected_publisher_without_verified_account_identity():
    _reset_feed()
    now_ms = int(time.time() * 1000)
    client = TestClient(bridge.app, base_url="https://bridge.test",
                        client=("127.0.0.1", 49152))
    response = client.post("/v1/analyzer/market-heartbeat", json={
        "terminal_connected": True,
        "symbols": ["EURUSD"],
        "broker_quotes": [{"symbol": "EURUSD", "bid": 1.1, "ask": 1.2,
                           "digits": 5, "timestamp_ms": now_ms}],
        "broker_candles": [],
    })
    assert response.status_code == 422


def test_analyzer_feed_rejects_public_market_data_publishers():
    _reset_feed()
    now_ms = int(time.time() * 1000)
    client = TestClient(bridge.app, base_url="https://bridge.test",
                        client=("203.0.113.10", 49152))
    response = client.post("/v1/analyzer/market-heartbeat", json={
        "terminal_connected": True,
        "account_login": "12345678",
        "account_server": "Broker-Demo",
        "account_mode": "DEMO",
        "symbols": ["EURUSD"],
        "broker_quotes": [{"symbol": "EURUSD", "bid": 1.1, "ask": 1.2,
                           "digits": 5, "timestamp_ms": now_ms}],
        "broker_candles": [],
    })
    assert response.status_code == 403
    assert response.json()["detail"]["code"] == "ANALYZER_FEED_LOCAL_PUBLISHER_ONLY"


def test_analyzer_feed_expires_after_terminal_stops_heartbeating():
    _reset_feed()
    with bridge._ANALYZER_FEED_LOCK:
        bridge._ANALYZER_FEED_STATE.update({
            "heartbeat_at_ms": int(time.time() * 1000) - 20_000,
            "terminal_connected": True,
        "account_login": "12345678",
        "account_server": "Broker-Demo",
        "account_mode": "DEMO",
            "symbols": ["EURUSD"],
            "broker_quotes": [{"symbol": "EURUSD", "bid": 1.1, "ask": 1.2,
                               "digits": 5, "timestamp_ms": int(time.time() * 1000)}],
            "broker_candles": [],
        })
    client = TestClient(bridge.app, base_url="https://bridge.test",
                        client=("127.0.0.1", 49152))
    payload = client.get("/v1/analyzer/market-state").json()
    assert payload["connected"] is False
    assert payload["symbols"] == []
    assert payload["broker_quotes"] == []
    assert payload["error_message"] == "MT5 not connected"



def test_local_analyzer_publisher_uses_host_scope_not_bot_or_cloudflare_pairing(monkeypatch):
    _reset_feed()
    monkeypatch.setattr(
        bridge, "cloudflare_access_settings",
        lambda: ("https://destinedfx.cloudflareaccess.com", "analyzer-audience"),
    )
    monkeypatch.setattr(bridge, "PUBLIC_EXPOSURE", True)
    monkeypatch.setattr(bridge, "EXPECTED_PUBLIC_HOST", "bridge.destinedfx.example")
    local = TestClient(bridge.app, base_url="http://127.0.0.1:8000",
                       client=("127.0.0.1", 49152))
    now_ms = int(time.time() * 1000)
    response = local.post("/v1/analyzer/market-heartbeat", json={
        "terminal_connected": True,
        "account_login": "12345678",
        "account_server": "Broker-Demo",
        "account_mode": "DEMO",
        "symbols": ["EURUSD"],
        "broker_quotes": [{"symbol": "EURUSD", "bid": 1.1, "ask": 1.1002,
                           "digits": 5, "timestamp_ms": now_ms}],
        "broker_candles": [],
    })
    assert response.status_code == 200, response.text

    remote = TestClient(bridge.app, base_url="https://bridge.destinedfx.example",
                        client=("203.0.113.20", 49152))
    blocked = remote.get("/v1/analyzer/market-state")
    assert blocked.status_code == 401
    assert blocked.json()["detail"]["code"] == "ACCESS_ASSERTION_REQUIRED"

import importlib.util
import json
import pathlib
import sys
import time

import pytest


MODULE_PATH = pathlib.Path(__file__).with_name("deriv_demo_smoke.py")
spec = importlib.util.spec_from_file_location("deriv_demo_smoke", MODULE_PATH)
smoke = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = smoke
spec.loader.exec_module(smoke)


class FakeSocket:
    def __init__(self, messages):
        self.messages = list(messages)
        self.sent = []
        self.timeouts = []
        self.closed = False

    def settimeout(self, timeout):
        self.timeouts.append(timeout)

    def send(self, payload):
        self.sent.append(json.loads(payload))

    def recv(self):
        if not self.messages:
            raise TimeoutError("fixture queue empty")
        message = self.messages.pop(0)
        if isinstance(message, BaseException):
            raise message
        return json.dumps(message)

    def close(self):
        self.closed = True


def account(account_id, account_type="demo"):
    return {
        "account_id": account_id,
        "account_type": account_type,
        "status": "active",
        "currency": "USD",
        "balance": 100.0,
    }


def run_main(monkeypatch, accounts, otp_url="wss://api.derivws.com/trading/v1/options/ws/demo?otp=fixture", sockets=None):
    calls = []

    def fake_request(path, app_id, token, method="GET"):
        calls.append((path, method))
        if path.endswith("/accounts"):
            return {"data": accounts}
        return {"data": {"url": otp_url}}

    monkeypatch.setattr(smoke, "request_json", fake_request)
    monkeypatch.setattr(smoke, "open_authenticated_socket", lambda _url: sockets.pop(0))
    monkeypatch.setattr(sys, "argv", ["deriv_demo_smoke.py", "--app-id", "12345", "--token", "local-only"])
    return calls


def test_no_active_demo_account_fails_closed(monkeypatch):
    run_main(monkeypatch, [account("VRTC123", "real")], sockets=[])
    with pytest.raises(SystemExit, match="No active DEMO"):
        smoke.main()


def test_two_demo_accounts_require_explicit_selection(monkeypatch):
    run_main(monkeypatch, [account("VRTC123"), account("VRTC456")], sockets=[])
    with pytest.raises(SystemExit, match="More than one DEMO"):
        smoke.main()


@pytest.mark.parametrize(
    "url",
    [
        "https://api.derivws.com/trading/v1/options/ws/demo?otp=x",
        "wss://evil.example/trading/v1/options/ws/demo?otp=x",
        "wss://api.derivws.com/trading/v1/options/ws/real?otp=x",
        "wss://api.derivws.com/trading/v1/options/ws/demo",
        "wss://api.derivws.com/trading/v1/options/ws/demo?otp=",
    ],
)
def test_invalid_or_wrong_host_otp_url_is_rejected(url):
    assert smoke.validate_authenticated_ws_url(url, "demo") is False


def test_unrelated_first_message_is_ignored_and_integer_ids_are_sent(monkeypatch):
    balance_socket = FakeSocket([
        {"msg_type": "tick", "req_id": 999, "tick": {"symbol": "R_100"}},
        {"msg_type": "balance", "req_id": 1001, "balance": {"balance": 100, "currency": "USD"}},
        {"msg_type": "portfolio", "req_id": 1002, "portfolio": {"contracts": []}},
    ])
    tick_socket = FakeSocket([
        {"msg_type": "tick", "req_id": 1003, "tick": {"symbol": "R_100", "quote": 1.0, "epoch": time.time() + 1}, "subscription": {"id": "sub-1"}},
    ])
    ids = iter([1001, 1002, 1003, 1004])
    monkeypatch.setattr(smoke, "new_request_id", lambda: next(ids))
    run_main(monkeypatch, [account("VRTC123")], sockets=[balance_socket, tick_socket])

    assert smoke.main() == 0
    assert balance_socket.sent == [
        {"balance": 1, "subscribe": 0, "req_id": 1001},
        {"portfolio": 1, "req_id": 1002},
    ]
    assert tick_socket.sent[0] == {"ticks": "R_100", "subscribe": 1, "req_id": 1003}
    assert tick_socket.sent[1] == {"forget": "sub-1", "req_id": 1004}
    assert tick_socket.closed
    assert all(isinstance(payload["req_id"], int) for payload in balance_socket.sent + tick_socket.sent)


def test_error_reply_is_redacted_and_fails_closed():
    socket = FakeSocket([{"msg_type": "balance", "req_id": 7, "error": {"message": "PAT secret"}}])
    with pytest.raises(RuntimeError, match="rejected the balance request") as failure:
        smoke.recv_matching(socket, 7, "balance")
    assert "PAT secret" not in str(failure.value)


def test_success_requires_matching_type_and_request_id():
    socket = FakeSocket([
        {"msg_type": "portfolio", "req_id": 7, "portfolio": {}},
        {"msg_type": "balance", "req_id": 8, "balance": {}},
    ])
    with pytest.raises(RuntimeError, match="unexpected response for balance"):
        smoke.recv_matching(socket, 7, "balance")


def test_invalid_otp_url_stops_before_socket(monkeypatch):
    sockets = []
    run_main(
        monkeypatch,
        [account("VRTC123")],
        otp_url="wss://evil.example/trading/v1/options/ws/demo?otp=fixture",
        sockets=sockets,
    )
    with pytest.raises(SystemExit, match="invalid DEMO authenticated"):
        smoke.main()
    assert sockets == []


@pytest.mark.parametrize("tick", [
    {"symbol": "R_50", "quote": 1.0, "epoch": time.time()},
    {"symbol": "R_100", "quote": 0, "epoch": time.time()},
    {"symbol": "R_100", "quote": -1.0, "epoch": time.time()},
    {"symbol": "R_100", "quote": float("nan"), "epoch": time.time()},
    {"symbol": "R_100", "quote": float("inf"), "epoch": time.time()},
    {"symbol": "R_100", "quote": 1.0, "epoch": time.time() - 30},
    {"symbol": "R_100", "quote": 1.0, "epoch": time.time() + 60},
    {"symbol": "R_100", "quote": 1.0},
])
def test_invalid_public_tick_fails_and_socket_closes(monkeypatch, tick):
    public = FakeSocket([{"msg_type": "tick", "req_id": 1003, "tick": tick, "subscription": {"id": "sub-invalid"}}])
    ids = iter([1001, 1002, 1003, 1004])
    monkeypatch.setattr(smoke, "new_request_id", lambda: next(ids))
    run_main(monkeypatch, [account("VRTC123")], sockets=[
        FakeSocket([{"msg_type": "balance", "req_id": 1001, "balance": {"balance": 100, "currency": "USD"}}, {"msg_type": "portfolio", "req_id": 1002, "portfolio": {"contracts": []}}]), public
    ])
    with pytest.raises(RuntimeError, match="fresh, valid R_100"):
        smoke.main()
    assert public.sent[-1]["forget"] == "sub-invalid"
    assert public.closed


def test_source_has_no_trade_endpoint_actions():
    source = MODULE_PATH.read_text(encoding="utf-8")
    for endpoint in ("/proposal", "/buy", "/sell", "/cancel", "/orders"):
        assert endpoint not in source
    for action in ('"proposal"', '"buy"', '"sell"', '"cancel"', '"orders"'):
        assert action not in source


def test_verification_does_not_change_selected_chart_source():
    ui = (MODULE_PATH.parent.parent / "app/src/main/java/com/example/ui/SimplifiedBotWorkstation.kt").read_text(encoding="utf-8")
    market = (MODULE_PATH.parent.parent / "app/src/main/java/com/example/data/market/MarketDataRepositoryImpl.kt").read_text(encoding="utf-8")
    start = ui.index("LOGIN · VERIFY DEMO READ ONLY")
    verification_region = ui[ui.rfind("Button(", 0, start):ui.index("val matchingDerivAccounts", start)]
    assert "marketDataRepository.refreshSymbol" not in verification_region
    assert "selectedApiSource" not in verification_region
    assert 'override val selectedApiSource = MutableStateFlow("TRADINGVIEW")' in market

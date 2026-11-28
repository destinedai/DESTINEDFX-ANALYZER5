"""Contract tests for explicitly synthetic MT5 DEMO/LIVE fixtures."""

import json
import sys
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))

import app
from mt5_broker_contract_simulator import (  # noqa: E402
    ACCOUNT_FIELDS,
    SCENARIOS,
    SimulatedMT5Terminal,
    account_mode_from_mt5,
)


def test_both_synthetic_account_modes_match_bridge_heartbeat_contract():
    assert account_mode_from_mt5(0) == "DEMO"
    assert account_mode_from_mt5(2) == "LIVE"
    assert account_mode_from_mt5(1) == "CONTEST"
    assert account_mode_from_mt5(99) == "UNKNOWN"

    for mode, scenario in SCENARIOS.items():
        terminal = SimulatedMT5Terminal(scenario)
        raw = terminal.heartbeat()
        # Validate the same Pydantic model used by POST /v1/terminal/heartbeat.
        validated = app.Heartbeat.model_validate(json.loads(json.dumps(raw)))
        account = validated.account.model_dump()

        assert tuple(account) == ACCOUNT_FIELDS
        assert account["account_mode"] == mode
        assert account["login"] == str(scenario.account_info["login"])
        assert account["server"] == scenario.account_info["server"]
        assert account["company"] == scenario.account_info["company"]
        assert account["currency"] == scenario.account_info["currency"]
        assert account["balance"] == scenario.account_info["balance"]
        assert account["equity"] == scenario.account_info["equity"]
        assert account["margin"] == scenario.account_info["margin"]
        assert account["free_margin"] == scenario.account_info["margin_free"]
        assert account["margin_level"] == scenario.account_info["margin_level"]
        assert account["leverage"] == scenario.account_info["leverage"]
        assert validated.terminal_connected is True
        assert validated.trade_allowed is True
        assert len(validated.broker_quotes) == 1
        assert validated.open_positions == []
        assert validated.active_orders == []
        assert validated.closed_deals == []


def test_demo_and_live_order_responses_are_labeled_simulations_and_idempotent():
    demo = SimulatedMT5Terminal(SCENARIOS["DEMO"])
    demo_result = demo.simulate_market_order(
        request_id="demo-fixture-1",
        side="BUY",
        symbol="EURUSD",
        volume=0.01,
    )
    assert demo_result["status"] == "SIMULATED_FILL"
    assert demo_result["simulation_only"] is True
    assert demo_result["broker_order_sent"] is False
    assert demo.simulate_market_order(
        request_id="demo-fixture-1",
        side="BUY",
        symbol="EURUSD",
        volume=0.01,
    ) == demo_result

    live = SimulatedMT5Terminal(SCENARIOS["LIVE"])
    blocked = live.simulate_market_order(
        request_id="live-fixture-blocked",
        side="BUY",
        symbol="EURUSD",
        volume=0.01,
    )
    assert blocked["accepted"] is False
    assert blocked["reason"] == "LIVE_AUTHORIZATION_REQUIRED"
    assert blocked["broker_order_sent"] is False

    # This is deliberately a synthetic lease fixture, not a production LIVE lease.
    live_result = live.simulate_market_order(
        request_id="live-fixture-authorized",
        side="BUY",
        symbol="EURUSD",
        volume=0.01,
        live_authorized_fixture=True,
    )
    assert live_result["status"] == "SIMULATED_FILL"
    assert live_result["account_mode"] == "LIVE"
    assert live_result["authorization_evidence"] == "SIMULATED_LIVE_AUTHORIZATION_FIXTURE"
    assert live_result["simulation_only"] is True
    assert live_result["broker_order_sent"] is False


def test_simulation_output_cannot_claim_a_real_broker_or_terminal_connection():
    from mt5_broker_contract_simulator import run_scenarios

    output = run_scenarios()
    assert output["simulation_only"] is True
    assert output["broker_reached"] is False
    assert output["real_terminal_connected"] is False
    for scenario in output["scenarios"].values():
        assert scenario["simulated_order_result"]["simulation_only"] is True
        assert scenario["simulated_order_result"]["broker_order_sent"] is False

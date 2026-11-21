#!/usr/bin/env python3
"""Offline MT5 account-contract simulator for DestinedFX.

This module NEVER imports MetaTrader5, opens a socket, calls a broker, or sends a
real order. It emits explicitly synthetic fixtures so Bridge/Python consumers
can be checked against the fields produced by the MT5 EA heartbeat.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any


ACCOUNT_TRADE_MODE_DEMO = 0
ACCOUNT_TRADE_MODE_CONTEST = 1
ACCOUNT_TRADE_MODE_REAL = 2

ACCOUNT_FIELDS = (
    "login",
    "server",
    "company",
    "currency",
    "account_mode",
    "balance",
    "equity",
    "margin",
    "free_margin",
    "margin_level",
    "leverage",
)


def account_mode_from_mt5(trade_mode: int) -> str:
    """Translate the documented MT5 ENUM_ACCOUNT_TRADE_MODE values."""
    return {
        ACCOUNT_TRADE_MODE_DEMO: "DEMO",
        ACCOUNT_TRADE_MODE_CONTEST: "CONTEST",
        ACCOUNT_TRADE_MODE_REAL: "LIVE",
    }.get(int(trade_mode), "UNKNOWN")


@dataclass(frozen=True)
class Scenario:
    name: str
    account_info: dict[str, Any]
    terminal_info: dict[str, Any]
    quote: dict[str, Any]


SCENARIOS = {
    "DEMO": Scenario(
        name="SYNTHETIC-DEMO",
        account_info={
            "login": "SIM-DEMO-001",
            "trade_mode": ACCOUNT_TRADE_MODE_DEMO,
            "leverage": 100,
            "trade_allowed": True,
            "trade_expert": True,
            "balance": 10000.00,
            "equity": 10025.00,
            "margin": 100.00,
            "margin_free": 9925.00,
            "margin_level": 10025.00,
            "server": "ExampleBroker-Demo-SIM",
            "currency": "USD",
            "company": "Example Broker (SIMULATED)",
        },
        terminal_info={"connected": True, "trade_allowed": True, "company": "Example Broker (SIMULATED)"},
        quote={"symbol": "EURUSD", "bid": 1.08234, "ask": 1.08246, "digits": 5},
    ),
    "LIVE": Scenario(
        name="SYNTHETIC-LIVE",
        account_info={
            "login": "SIM-LIVE-001",
            "trade_mode": ACCOUNT_TRADE_MODE_REAL,
            "leverage": 200,
            "trade_allowed": True,
            "trade_expert": True,
            "balance": 25000.00,
            "equity": 24950.00,
            "margin": 300.00,
            "margin_free": 24650.00,
            "margin_level": 8316.6666666667,
            "server": "ExampleBroker-Live-SIM",
            "currency": "USD",
            "company": "Example Broker (SIMULATED)",
        },
        terminal_info={"connected": True, "trade_allowed": True, "company": "Example Broker (SIMULATED)"},
        quote={"symbol": "EURUSD", "bid": 1.08234, "ask": 1.08246, "digits": 5},
    ),
}


@dataclass
class SimulatedMT5Terminal:
    """Deterministic stand-in for MT5 terminal/account/order responses."""

    scenario: Scenario
    _orders: dict[str, dict[str, Any]] = field(default_factory=dict)
    _next_ticket: int = 900000

    @property
    def mode(self) -> str:
        return account_mode_from_mt5(self.scenario.account_info["trade_mode"])

    def account_payload(self) -> dict[str, Any]:
        info = self.scenario.account_info
        terminal = self.scenario.terminal_info
        company = str(info.get("company") or terminal.get("company") or "")
        return {
            "login": str(info["login"]),
            "server": str(info["server"]),
            "company": company,
            "currency": str(info["currency"]),
            "account_mode": self.mode,
            "balance": float(info["balance"]),
            "equity": float(info["equity"]),
            "margin": float(info["margin"]),
            "free_margin": float(info["margin_free"]),
            "margin_level": float(info["margin_level"]),
            "leverage": int(info["leverage"]),
        }

    def trade_allowed(self) -> bool:
        info = self.scenario.account_info
        terminal = self.scenario.terminal_info
        return bool(
            self.mode in ("DEMO", "LIVE")
            and terminal.get("connected", False)
            and terminal.get("trade_allowed", False)
            and info.get("trade_allowed", False)
            and info.get("trade_expert", False)
        )

    def heartbeat(self) -> dict[str, Any]:
        """Build the same account/heartbeat envelope expected from the EA."""
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        quote = dict(self.scenario.quote)
        quote["timestamp_ms"] = now_ms
        return {
            "state": "READY",
            "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "terminal_connected": bool(self.scenario.terminal_info.get("connected", False)),
            "trade_allowed": self.trade_allowed(),
            "account": self.account_payload(),
            "configuration": {
                "strategy": "SIMULATED-CONTRACT-TEST",
                "risk_percent": 0.25,
                "max_daily_loss_percent": 2.0,
                "max_concurrent_positions": 2,
                "daily_risk_status": "SIMULATED",
            },
            "last_signal": None,
            "last_error": "",
            "open_positions": [],
            "active_orders": [],
            "closed_deals": [],
            "news_verdict": {
                "status": "UNKNOWN",
                "detail": "SIMULATION ONLY: this is not a broker news verdict",
                "source": "local-fixture",
                "retrieved_at_ms": now_ms,
                "coverage_from_ms": now_ms - 60000,
                "coverage_to_ms": now_ms + 60000,
                "affected_currencies": [],
            },
            "cancelled_order_tickets": [],
            "broker_quotes": [quote],
        }

    def simulate_market_order(
        self,
        *,
        request_id: str,
        side: str,
        symbol: str,
        volume: float,
        live_authorized_fixture: bool = False,
    ) -> dict[str, Any]:
        """Return a synthetic MT5-like order result; never transmits an order."""
        if request_id in self._orders:
            return dict(self._orders[request_id])
        if not self.trade_allowed():
            return {
                "simulation_only": True,
                "accepted": False,
                "status": "SIMULATED_REJECTED",
                "reason": "TERMINAL_OR_ACCOUNT_TRADE_PERMISSION_FALSE",
                "broker_order_sent": False,
            }
        if self.mode == "LIVE" and not live_authorized_fixture:
            return {
                "simulation_only": True,
                "accepted": False,
                "status": "SIMULATED_REJECTED",
                "reason": "LIVE_AUTHORIZATION_REQUIRED",
                "broker_order_sent": False,
            }
        if side not in ("BUY", "SELL") or not symbol or volume <= 0:
            return {
                "simulation_only": True,
                "accepted": False,
                "status": "SIMULATED_REJECTED",
                "reason": "INVALID_ORDER_FIXTURE",
                "broker_order_sent": False,
            }

        self._next_ticket += 1
        quote = self.scenario.quote
        price = float(quote["ask"] if side == "BUY" else quote["bid"])
        result = {
            "simulation_only": True,
            "accepted": True,
            "status": "SIMULATED_FILL",
            "retcode": 10009,
            "retcode_name": "TRADE_RETCODE_DONE (fixture)",
            "order": f"SIM-ORDER-{self._next_ticket}",
            "deal": f"SIM-DEAL-{self._next_ticket}",
            "symbol": symbol,
            "side": side,
            "volume": float(volume),
            "price": price,
            "account_mode": self.mode,
            "authorization_evidence": (
                "SIMULATED_LIVE_AUTHORIZATION_FIXTURE"
                if self.mode == "LIVE"
                else "NOT_REQUIRED_FOR_DEMO_FIXTURE"
            ),
            "broker_order_sent": False,
        }
        self._orders[request_id] = result
        return dict(result)


def run_scenarios() -> dict[str, Any]:
    output: dict[str, Any] = {
        "simulation_only": True,
        "broker_reached": False,
        "real_terminal_connected": False,
        "note": (
            "Synthetic MT5-shaped fixtures only. No MetaTrader5 package, broker "
            "socket, user account, credential, or real order was used."
        ),
        "scenarios": {},
    }
    for mode, scenario in SCENARIOS.items():
        terminal = SimulatedMT5Terminal(scenario)
        live_authorized = mode == "LIVE"
        order = terminal.simulate_market_order(
            request_id=f"fixture-{mode.lower()}-001",
            side="BUY",
            symbol="EURUSD",
            volume=0.01,
            live_authorized_fixture=live_authorized,
        )
        output["scenarios"][mode] = {
            "fixture_name": scenario.name,
            "account": terminal.account_payload(),
            "trade_allowed": terminal.trade_allowed(),
            "heartbeat": terminal.heartbeat(),
            "simulated_order_result": order,
        }
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pretty", action="store_true", help="Pretty-print the synthetic contract fixtures")
    args = parser.parse_args()
    print(json.dumps(run_scenarios(), indent=2 if args.pretty else None, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

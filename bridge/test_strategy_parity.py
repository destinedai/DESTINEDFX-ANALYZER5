import math
from pathlib import Path

from quantitative import evaluate_strategy
from test_quantitative import NOW, _strategy_rows, validate


GOLDENS = {
    "smc-confluence-1.0.0": {
        "state": "CANDIDATE", "direction": "LONG", "entry": 107.4,
        "stop_loss": 105.54464285714286, "take_profit": 111.11071428571425,
        "evidence": ("BOS_UP_CLOSED_CANDLE", "CLOSE_ABOVE_EMA20", "MINIMUM_2R_TARGET_PROPOSAL"),
    },
    "ict-silver-bullet": {
        "state": "CANDIDATE", "direction": "LONG", "entry": 106.5,
        "stop_loss": 105.22142857142858, "take_profit": 109.05714285714285,
        "evidence": ("LIQUIDITY_SWEEP_LOW", "DISPLACEMENT_CLOSE_UP", "BULLISH_FVG",
                     "FVG_CONSEQUENT_ENCROACHMENT", "SILVER_BULLET_NEW_YORK_AM",
                     "MINIMUM_2R_TARGET_PROPOSAL"),
    },
}


def test_golden_closed_candles_match_canonical_strategy_outputs():
    disagreements = []
    for strategy, expected in GOLDENS.items():
        actual = evaluate_strategy(validate(_strategy_rows(strategy)), strategy_id=strategy,
                                   symbol="EUR/USD", timeframe="M15")
        for field in ("state", "direction"):
            if getattr(actual, field) != expected[field]:
                disagreements.append(f"{strategy}.{field}: expected {expected[field]!r}, got {getattr(actual, field)!r}")
        for field in ("entry", "stop_loss", "take_profit"):
            if not math.isclose(getattr(actual, field), expected[field], rel_tol=0, abs_tol=1e-9):
                disagreements.append(f"{strategy}.{field}: expected {expected[field]!r}, got {getattr(actual, field)!r}")
        if tuple(actual.evidence) != expected["evidence"]:
            disagreements.append(f"{strategy}.evidence: expected {expected['evidence']!r}, got {actual.evidence!r}")
    assert not disagreements, "Golden-candle parity disagreements:\n" + "\n".join(disagreements)


def test_android_bundled_python_engine_is_byte_identical_to_canonical():
    root = Path(__file__).resolve().parents[1]
    assert (root / "app/src/main/python/quantitative.py").read_bytes() == (root / "bridge/quantitative.py").read_bytes()

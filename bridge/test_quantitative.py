import json

from quantitative import STRATEGY_ID, DataIntegrityError, OHLCV, analyze_json, evaluate_smc, validate_ohlcv
import pytest

NOW = 1_700_000_000_000
INTERVAL = 60_000


def candles(direction=None, count=30):
    rows = []
    for index in range(count):
        close = 100.0 + index * 0.2
        rows.append(OHLCV(
            timestamp_ms=NOW - (count - index) * INTERVAL,
            open=close - 0.05,
            high=close + 0.30,
            low=close - 0.30,
            close=close,
            volume=100.0,
            complete=True,
            source="fixture-provider",
            retrieved_at_ms=NOW,
        ))
    if direction == "LONG":
        previous = rows[-2]
        rows[-1] = OHLCV(rows[-1].timestamp_ms, previous.high + 0.2, previous.high + 0.6, previous.high + 0.1, previous.high + 0.5, 100.0, True, "fixture-provider", NOW)
    elif direction == "SHORT":
        previous = rows[-2]
        rows[-1] = OHLCV(rows[-1].timestamp_ms, previous.low - 0.2, previous.low - 0.1, previous.low - 0.6, previous.low - 0.5, 100.0, True, "fixture-provider", NOW)
    return rows


def validate(rows, **kwargs):
    return validate_ohlcv(rows, now_ms=NOW, source="fixture-provider", expected_interval_ms=INTERVAL, **kwargs)


def test_identical_closed_candles_are_deterministic_and_non_executable():
    first = evaluate_smc(validate(candles("LONG")), symbol="EUR/USD", timeframe="M15")
    second = evaluate_smc(validate(candles("LONG")), symbol="EUR/USD", timeframe="M15")
    assert first == second
    assert first.state == "CANDIDATE"
    assert first.direction == "LONG"
    assert first.reward_to_risk == 2.0
    assert first.executable is False
    assert "BOS_UP_CLOSED_CANDLE" in first.evidence


def test_no_confirmed_event_returns_waiting():
    result = evaluate_smc(validate(candles()), symbol="EUR/USD", timeframe="M15")
    assert result.state == "WAITING"
    assert result.direction == "WAIT"
    assert result.entry is None
    assert result.executable is False


@pytest.mark.parametrize("bad", [
    "INCOMPLETE_CANDLE",
    "UNORDERED_OR_DUPLICATE_CANDLES",
    "NON_FINITE_OHLCV",
    "IMPOSSIBLE_OHLCV",
    "FUTURE_OR_INVALID_TIMESTAMP",
    "INVALID_RETRIEVAL_TIMESTAMP",
    "STALE_CANDLES",
    "CANDLE_GAP_OR_WRONG_INTERVAL",
])
def test_invalid_provider_data_is_rejected_before_analysis(bad):
    rows = candles()
    if bad == "INCOMPLETE_CANDLE":
        rows[-1] = OHLCV(**{**rows[-1].__dict__, "complete": False})
    elif bad == "UNORDERED_OR_DUPLICATE_CANDLES":
        rows[-1] = OHLCV(**{**rows[-1].__dict__, "timestamp_ms": rows[-2].timestamp_ms})
    elif bad == "NON_FINITE_OHLCV":
        rows[-1] = OHLCV(**{**rows[-1].__dict__, "close": float("nan")})
    elif bad == "IMPOSSIBLE_OHLCV":
        rows[-1] = OHLCV(**{**rows[-1].__dict__, "low": -1.0})
    elif bad == "FUTURE_OR_INVALID_TIMESTAMP":
        rows[-1] = OHLCV(**{**rows[-1].__dict__, "timestamp_ms": NOW + 1})
    elif bad == "INVALID_RETRIEVAL_TIMESTAMP":
        rows[-1] = OHLCV(**{**rows[-1].__dict__, "retrieved_at_ms": NOW + 1})
    elif bad == "STALE_CANDLES":
        rows = [OHLCV(**{**row.__dict__, "timestamp_ms": row.timestamp_ms - 600_000, "retrieved_at_ms": NOW}) for row in rows]
    elif bad == "CANDLE_GAP_OR_WRONG_INTERVAL":
        rows[15:] = [OHLCV(**{**row.__dict__, "timestamp_ms": row.timestamp_ms + INTERVAL}) for row in rows[15:]]
    with pytest.raises(DataIntegrityError, match=bad):
        validate(rows)


def test_missing_source_and_empty_series_fail_closed():
    with pytest.raises(DataIntegrityError, match="NO_CANDLES"):
        validate_ohlcv([], now_ms=NOW, source="fixture-provider")
    with pytest.raises(DataIntegrityError, match="MISSING_SOURCE"):
        validate_ohlcv(candles(), now_ms=NOW, source=" ")


def test_short_series_cannot_create_signal():
    result = evaluate_smc(validate(candles(count=10)), symbol="EUR/USD", timeframe="M15")
    assert result.state == "WAITING"
    assert result.reason == "INSUFFICIENT_CLOSED_CANDLES"

from quantitative import STRATEGY_RULESETS, evaluate_strategy

ALL_STRATEGIES = tuple(STRATEGY_RULESETS)

def _strategy_rows(strategy, count=35):
    rows = candles(count=count)
    last = rows[-1]
    if strategy == "smc-confluence-1.0.0":
        previous = rows[-2]
        rows[-1] = OHLCV(last.timestamp_ms, previous.high + .2, previous.high + .6, previous.high + .1, previous.high + .5, 100.0, True, "fixture-provider", NOW)
    elif strategy == "ict-silver-bullet":
        rows[-1] = OHLCV(last.timestamp_ms, 100.0, 102.0, 90.0, 101.0, 100.0, True, "fixture-provider", NOW)
    elif strategy == "wyckoff-schematics":
        rows[-1] = OHLCV(last.timestamp_ms, 100.0, 108.0, 99.0, 107.0, 200.0, True, "fixture-provider", NOW)
    elif strategy in {"supply-and-demand", "breakout-momentum"}:
        rows[-1] = OHLCV(last.timestamp_ms, 100.0, 108.0, 99.0, 107.0, 100.0, True, "fixture-provider", NOW)
    elif strategy == "volatility-breakout":
        rows[-1] = OHLCV(last.timestamp_ms, 100.0, 110.0, 99.0, 109.0, 100.0, True, "fixture-provider", NOW)
    elif strategy == "statistical-mean-reversion":
        rows = []
        for index in range(count):
            close = 100.0 + (0.15 if index % 2 else -0.15)
            rows.append(OHLCV(NOW - (count - index) * INTERVAL, close - .05, close + .25, close - .25, close, 100.0, True, "fixture-provider", NOW))
        rows[-1] = OHLCV(rows[-1].timestamp_ms, 95.0, 97.0, 94.0, 96.0, 100.0, True, "fixture-provider", NOW)
    return rows

@pytest.mark.parametrize("strategy", ALL_STRATEGIES)
def test_each_known_strategy_has_versioned_positive_evidence_and_is_non_executable(strategy):
    result = evaluate_strategy(validate(_strategy_rows(strategy)), strategy_id=strategy, symbol="EUR/USD", timeframe="M15")
    assert result.state == "CANDIDATE", (strategy, result.reason)
    assert result.strategy_id == strategy
    assert result.strategy_version == STRATEGY_RULESETS[strategy]
    assert result.evidence
    assert result.reason
    assert result.executable is False

def _neutral_rows(count=35):
    rows = []
    for index in range(count):
        close = 100.0
        rows.append(OHLCV(NOW - (count - index) * INTERVAL, close - .05, close + .2, close - .2, close, 100.0, True, "fixture-provider", NOW))
    return rows

@pytest.mark.parametrize("strategy", ALL_STRATEGIES)
def test_each_known_strategy_negative_case_waits_with_rejection_reason(strategy):
    result = evaluate_strategy(validate(_neutral_rows()), strategy_id=strategy, symbol="EUR/USD", timeframe="M15")
    assert result.state == "WAITING"
    assert result.direction == "WAIT"
    assert result.entry is None
    assert result.reason
    assert result.executable is False

@pytest.mark.parametrize("strategy", ALL_STRATEGIES)
def test_each_known_strategy_insufficient_history_waits(strategy):
    result = evaluate_strategy(validate(candles(count=10)), strategy_id=strategy, symbol="EUR/USD", timeframe="M15")
    assert result.state == "WAITING"
    assert result.reason == "INSUFFICIENT_CLOSED_CANDLES"
    assert result.strategy_id == strategy


def test_unknown_strategy_is_only_not_implemented_case():
    result = evaluate_strategy(validate(candles()), strategy_id="future-strategy", symbol="EUR/USD", timeframe="M15")
    assert result.state == "WAITING"
    assert result.reason == "NOT_IMPLEMENTED"
    assert result.strategy_id == "future-strategy"
    assert result.executable is False


def test_source_mismatch_fails_closed_before_analysis():
    rows = candles()
    rows[-1] = OHLCV(**{**rows[-1].__dict__, "source": "different-provider"})
    with pytest.raises(DataIntegrityError, match="SOURCE_MISMATCH"):
        validate(rows)


def test_json_boundary_preserves_non_executable_candidate_and_provenance():
    payload = {
        "symbol": "EUR/USD", "timeframe": "M15", "strategy_id": "smc-confluence-1.0.0",
        "source": "fixture-provider", "now_ms": NOW, "expected_interval_ms": INTERVAL,
        "provider_retrieved_at_ms": NOW, "candles": [row.__dict__ for row in candles("LONG")],
    }
    result = json.loads(analyze_json(json.dumps(payload)))
    assert result["state"] == "CANDIDATE"
    assert result["source"] == "fixture-provider"
    assert result["candle_timestamp_ms"] == candles("LONG")[-1].timestamp_ms
    assert result["score_basis"]
    assert result["executable"] is False


def test_json_boundary_unknown_strategy_is_not_implemented_without_fallback():
    result = json.loads(analyze_json(json.dumps({
        "symbol": "EUR/USD", "timeframe": "M15", "strategy_id": "future-strategy",
        "source": "fixture-provider", "now_ms": NOW, "candles": [],
    })))
    assert result["strategy_version"] == "NOT_IMPLEMENTED"
    assert result["reason"] == "NOT_IMPLEMENTED"
    assert result["executable"] is False


@pytest.mark.parametrize("quote_price", [0, -1, float("nan"), float("inf")])
def test_json_boundary_rejects_invalid_quote_price(quote_price):
    result = json.loads(analyze_json(json.dumps({
        "symbol": "EUR/USD", "timeframe": "M15", "strategy_id": STRATEGY_ID,
        "source": "fixture-provider", "now_ms": NOW, "quote_price": quote_price,
        "candles": [],
    }, allow_nan=True)))
    assert result["state"] == "DATA_UNAVAILABLE"
    assert result["reason"] == "INVALID_QUOTE_PRICE"
    assert result["executable"] is False

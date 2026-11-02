import json

from quantitative import DataIntegrityError, OHLCV, analyze_json, evaluate_smc, validate_ohlcv
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
    assert first.confidence_score == 100
    assert len(first.score_basis) == 4
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


def test_candle_source_mismatch_fails_closed():
    rows = candles()
    rows[0] = OHLCV(**{**rows[0].__dict__, "source": "other-provider"})
    with pytest.raises(DataIntegrityError, match="CANDLE_SOURCE_MISMATCH"):
        validate(rows)


def test_short_series_cannot_create_signal():
    result = evaluate_smc(validate(candles(count=10)), symbol="EUR/USD", timeframe="M15")
    assert result.state == "WAITING"
    assert result.reason == "INSUFFICIENT_CLOSED_CANDLES"


def test_json_boundary_returns_not_implemented_without_falling_back_to_smc():
    result = json.loads(analyze_json(json.dumps({
        "symbol": "EUR/USD", "timeframe": "M15", "strategy_id": "ict-silver-bullet",
        "source": "fixture-provider", "now_ms": NOW, "candles": [],
    })))
    assert result["state"] == "NOT_IMPLEMENTED"
    assert result["direction"] == "WAIT"
    assert result["executable"] is False


def test_json_boundary_rejects_missing_candles_fail_closed():
    result = json.loads(analyze_json(json.dumps({
        "symbol": "EUR/USD", "timeframe": "M15", "strategy_id": "smc-confluence-1.0.0",
        "source": "fixture-provider", "now_ms": NOW,
    })))
    assert result["state"] == "DATA_UNAVAILABLE"
    assert result["direction"] == "WAIT"
    assert result["executable"] is False

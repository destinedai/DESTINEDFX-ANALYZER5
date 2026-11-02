"""Deterministic, analysis-only quantitative primitives for bridge and Android."""
from __future__ import annotations

from dataclasses import asdict, dataclass
import json
from math import isfinite
from typing import Iterable, Sequence

STRATEGY_ID = "smc-confluence-1.0.0"
RULESET_VERSION = "rules-2026.10-python-2"
MAX_CANDLE_AGE_MS = 5 * 60 * 1000


class DataIntegrityError(ValueError):
    """Raised when provider data cannot safely enter deterministic analysis."""


@dataclass(frozen=True)
class OHLCV:
    timestamp_ms: int
    open: float
    high: float
    low: float
    close: float
    volume: float = 0.0
    complete: bool = True
    source: str = ""
    retrieved_at_ms: int = 0


@dataclass(frozen=True)
class ValidatedSeries:
    candles: tuple[OHLCV, ...]
    source: str
    last_timestamp_ms: int
    interval_ms: int | None


@dataclass(frozen=True)
class SignalCandidate:
    symbol: str
    strategy_id: str
    strategy_version: str
    timeframe: str
    state: str
    direction: str
    entry: float | None
    stop_loss: float | None
    take_profit: float | None
    reward_to_risk: float | None
    confidence_score: int
    score_basis: tuple[str, ...]
    evidence: tuple[str, ...]
    reason: str
    source: str
    candle_timestamp_ms: int
    executable: bool = False


def validate_ohlcv(candles: Iterable[OHLCV], *, now_ms: int, source: str,
                   expected_interval_ms: int | None = None,
                   allow_session_gaps: bool = False,
                   max_age_ms: int = MAX_CANDLE_AGE_MS) -> ValidatedSeries:
    """Validate provider candles without repairing, sorting, or fabricating data."""
    rows = tuple(candles)
    if not rows:
        raise DataIntegrityError("NO_CANDLES")
    if not source.strip():
        raise DataIntegrityError("MISSING_SOURCE")
    if now_ms <= 0 or max_age_ms <= 0:
        raise DataIntegrityError("INVALID_CLOCK")
    previous: OHLCV | None = None
    intervals: list[int] = []
    for row in rows:
        if row.source.strip() != source.strip():
            raise DataIntegrityError("CANDLE_SOURCE_MISMATCH")
        if row.timestamp_ms <= 0 or row.timestamp_ms > now_ms:
            raise DataIntegrityError("FUTURE_OR_INVALID_TIMESTAMP")
        if row.retrieved_at_ms <= 0 or row.retrieved_at_ms > now_ms:
            raise DataIntegrityError("INVALID_RETRIEVAL_TIMESTAMP")
        if not row.complete:
            raise DataIntegrityError("INCOMPLETE_CANDLE")
        values = (row.open, row.high, row.low, row.close, row.volume)
        if not all(isfinite(value) for value in values):
            raise DataIntegrityError("NON_FINITE_OHLCV")
        if min(row.open, row.high, row.low, row.close) <= 0 or row.volume < 0:
            raise DataIntegrityError("IMPOSSIBLE_OHLCV")
        if row.high < max(row.open, row.close, row.low) or row.low > min(row.open, row.close, row.high):
            raise DataIntegrityError("INVALID_OHLC_RANGE")
        if previous is not None:
            delta = row.timestamp_ms - previous.timestamp_ms
            if delta <= 0:
                raise DataIntegrityError("UNORDERED_OR_DUPLICATE_CANDLES")
            intervals.append(delta)
        previous = row
    if rows[-1].timestamp_ms + max_age_ms < now_ms:
        raise DataIntegrityError("STALE_CANDLES")
    if expected_interval_ms is not None:
        if expected_interval_ms <= 0:
            raise DataIntegrityError("CANDLE_GAP_OR_WRONG_INTERVAL")
        for delta in intervals:
            if delta == expected_interval_ms:
                continue
            # Explicitly bounded weekend/session closure allowance for non-crypto feeds.
            if not (allow_session_gaps and expected_interval_ms <= delta <= 72 * 60 * 60 * 1000):
                raise DataIntegrityError("CANDLE_GAP_OR_WRONG_INTERVAL")
    interval = intervals[0] if intervals and all(delta == intervals[0] for delta in intervals) else None
    return ValidatedSeries(rows, source.strip(), rows[-1].timestamp_ms, interval)


def _ema(values: Sequence[float], period: int) -> float | None:
    if len(values) < period:
        return None
    alpha = 2.0 / (period + 1.0)
    result = sum(values[:period]) / period
    for value in values[period:]:
        result = alpha * value + (1.0 - alpha) * result
    return result


def _atr(candles: Sequence[OHLCV], period: int = 14) -> float | None:
    if len(candles) < period + 1:
        return None
    true_ranges = [max(current.high - current.low,
                       abs(current.high - previous.close),
                       abs(current.low - previous.close))
                   for previous, current in zip(candles, candles[1:])]
    return sum(true_ranges[-period:]) / period


def evaluate_smc(series: ValidatedSeries, *, symbol: str, timeframe: str,
                 quote_price: float | None = None) -> SignalCandidate:
    """Detect a closed-candle BOS candidate; never marks it executable."""
    candles = series.candles
    if len(candles) < 22:
        return _waiting(series, symbol, timeframe, "INSUFFICIENT_CLOSED_CANDLES")
    closes = [row.close for row in candles]
    atr = _atr(candles)
    ema20 = _ema(closes, 20)
    if atr is None or ema20 is None or atr <= 0:
        return _waiting(series, symbol, timeframe, "INSUFFICIENT_STRUCTURE_OR_ATR")
    current, previous = candles[-1], candles[-2]
    evidence: list[str] = []
    direction = "WAIT"
    stop: float | None = None
    entry: float | None = None
    if current.close > previous.high and current.close > ema20:
        direction = "LONG"
        entry = current.close
        stop = min(row.low for row in candles[-5:-1]) - 0.25 * atr
        evidence.extend(("BOS_UP_CLOSED_CANDLE", "CLOSE_ABOVE_EMA20"))
    elif current.close < previous.low and current.close < ema20:
        direction = "SHORT"
        entry = current.close
        stop = max(row.high for row in candles[-5:-1]) + 0.25 * atr
        evidence.extend(("BOS_DOWN_CLOSED_CANDLE", "CLOSE_BELOW_EMA20"))
    if direction == "WAIT" or entry is None or stop is None:
        return _waiting(series, symbol, timeframe, "NO_CONFIRMED_BOS")
    risk = abs(entry - stop)
    if risk <= 0 or not isfinite(risk):
        return _waiting(series, symbol, timeframe, "INVALID_STRUCTURE_RISK")
    target = entry + 2.0 * risk if direction == "LONG" else entry - 2.0 * risk
    score_basis = (
        "BOS confirmation: 40 points",
        "EMA20 directional confirmation: 20 points",
        "Closed-candle data integrity: 20 points",
        "Minimum 2R target proposal: 20 points",
    )
    return SignalCandidate(
        symbol=symbol, strategy_id=STRATEGY_ID, strategy_version=RULESET_VERSION,
        timeframe=timeframe, state="CANDIDATE", direction=direction, entry=entry,
        stop_loss=stop, take_profit=target, reward_to_risk=2.0,
        confidence_score=sum((40, 20, 20, 20)), score_basis=score_basis,
        evidence=tuple(evidence) + ("MINIMUM_2R_TARGET_PROPOSAL",),
        reason="Closed-candle BOS candidate; requires independent quote, RiskEngine, broker and account validation.",
        source=series.source, candle_timestamp_ms=series.last_timestamp_ms,
        executable=False,
    )


def _waiting(series: ValidatedSeries, symbol: str, timeframe: str, reason: str) -> SignalCandidate:
    return SignalCandidate(
        symbol=symbol, strategy_id=STRATEGY_ID, strategy_version=RULESET_VERSION,
        timeframe=timeframe, state="WAITING", direction="WAIT", entry=None,
        stop_loss=None, take_profit=None, reward_to_risk=None, confidence_score=0,
        score_basis=(), evidence=(), reason=reason, source=series.source,
        candle_timestamp_ms=series.last_timestamp_ms, executable=False,
    )


def _candidate_to_dict(candidate: SignalCandidate) -> dict:
    result = asdict(candidate)
    result["evidence"] = list(candidate.evidence)
    result["score_basis"] = list(candidate.score_basis)
    return result


def analyze_json(payload: str) -> str:
    """Analyze one immutable candle snapshot for the local Android runtime."""
    try:
        request = json.loads(payload)
        if not isinstance(request, dict):
            raise DataIntegrityError("INVALID_REQUEST")
        symbol = str(request.get("symbol", "")).strip()
        timeframe = str(request.get("timeframe", "")).strip()
        strategy_id = str(request.get("strategy_id", "")).strip()
        source = str(request.get("source", "")).strip()
        now_ms = int(request.get("now_ms", 0))
        expected_interval_ms = request.get("expected_interval_ms")
        if expected_interval_ms is not None:
            expected_interval_ms = int(expected_interval_ms)
        provider_retrieved_at_ms = int(request.get("provider_retrieved_at_ms", 0))
        allow_session_gaps = bool(request.get("allow_session_gaps", False))
        rows = request.get("candles")
        if not symbol or not timeframe or not strategy_id or not isinstance(rows, list):
            raise DataIntegrityError("INVALID_REQUEST")
        if strategy_id != STRATEGY_ID:
            return json.dumps({
                "symbol": symbol, "strategy_id": strategy_id, "strategy_version": "",
                "timeframe": timeframe, "state": "NOT_IMPLEMENTED", "direction": "WAIT",
                "entry": None, "stop_loss": None, "take_profit": None,
                "reward_to_risk": None, "confidence_score": 0, "score_basis": [],
                "evidence": [], "reason": "Python ruleset is not implemented for this strategy.",
                "source": source, "candle_timestamp_ms": 0, "executable": False,
            }, sort_keys=True)
        candles = tuple(OHLCV(
            timestamp_ms=int(row["timestamp_ms"]), open=float(row["open"]),
            high=float(row["high"]), low=float(row["low"]), close=float(row["close"]),
            volume=float(row.get("volume", 0.0)), complete=bool(row.get("complete", True)),
            source=str(row.get("source", source)),
            retrieved_at_ms=int(row.get("retrieved_at_ms", provider_retrieved_at_ms)),
        ) for row in rows)
        series = validate_ohlcv(candles, now_ms=now_ms, source=source,
                                expected_interval_ms=expected_interval_ms,
                                allow_session_gaps=allow_session_gaps)
        return json.dumps(_candidate_to_dict(evaluate_smc(
            series, symbol=symbol, timeframe=timeframe,
            quote_price=request.get("quote_price"),
        )), sort_keys=True)
    except (DataIntegrityError, KeyError, TypeError, ValueError, OverflowError) as error:
        return json.dumps({
            "state": "DATA_UNAVAILABLE", "direction": "WAIT", "executable": False,
            "reason": str(error) or "INVALID_REQUEST",
        }, sort_keys=True)

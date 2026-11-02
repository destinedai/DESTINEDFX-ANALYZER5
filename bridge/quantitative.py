"""Deterministic, analysis-only quantitative primitives for the bridge.

Only validated provider-sourced, timestamped closed candles enter strategy rules.
Every result is a non-executable candidate; this module does not estimate
probabilities, size positions, or approve orders.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, time, timezone
import json
from math import isfinite, sqrt
from typing import Iterable, Sequence
from zoneinfo import ZoneInfo

STRATEGY_ID = "smc-confluence-1.0.0"
RULESET_VERSION = "rules-2026.10-python-1"
MAX_CANDLE_AGE_MS = 5 * 60 * 1000
STRATEGY_RULESETS = {
    "smc-confluence-1.0.0": "python-smc-confluence-1.0.0",
    "ict-silver-bullet": "python-ict-silver-bullet-1.0.0",
    "wyckoff-schematics": "python-wyckoff-schematics-1.0.0",
    "supply-and-demand": "python-supply-and-demand-1.0.0",
    "volatility-breakout": "python-volatility-breakout-1.0.0",
    "breakout-momentum": "python-breakout-momentum-1.0.0",
    "statistical-mean-reversion": "python-statistical-mean-reversion-1.0.0",
    "multi-ema-trend-following": "python-multi-ema-trend-following-1.0.0",
}


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
    """Validate provider candles without sorting, repairing, or fabricating data."""
    rows = tuple(candles)
    source = source.strip()
    if not rows:
        raise DataIntegrityError("NO_CANDLES")
    if not source:
        raise DataIntegrityError("MISSING_SOURCE")
    if now_ms <= 0 or max_age_ms <= 0:
        raise DataIntegrityError("INVALID_CLOCK")
    previous = None
    intervals = []
    for row in rows:
        if row.timestamp_ms <= 0 or row.timestamp_ms > now_ms:
            raise DataIntegrityError("FUTURE_OR_INVALID_TIMESTAMP")
        if row.retrieved_at_ms <= 0 or row.retrieved_at_ms > now_ms:
            raise DataIntegrityError("INVALID_RETRIEVAL_TIMESTAMP")
        if not row.complete:
            raise DataIntegrityError("INCOMPLETE_CANDLE")
        if row.source.strip() != source:
            raise DataIntegrityError("SOURCE_MISMATCH")
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
            if not (allow_session_gaps and expected_interval_ms <= delta <= 72 * 60 * 60 * 1000):
                raise DataIntegrityError("CANDLE_GAP_OR_WRONG_INTERVAL")
    interval = intervals[0] if intervals and all(delta == intervals[0] for delta in intervals) else None
    return ValidatedSeries(rows, source, rows[-1].timestamp_ms, interval)


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
    ranges = [max(c.high - c.low, abs(c.high - p.close), abs(c.low - p.close))
              for p, c in zip(candles, candles[1:])]
    return sum(ranges[-period:]) / period


def _mean(values: Sequence[float]) -> float:
    return sum(values) / len(values)


def _std(values: Sequence[float], mean: float) -> float:
    return sqrt(sum((x - mean) ** 2 for x in values) / len(values))


def _ict_silver_bullet_window(timestamp_ms: int) -> str | None:
    """Return the NY-local Silver Bullet window for a candle's open timestamp.

    UTC-to-New-York conversion is intentional: ZoneInfo applies US daylight-saving
    transitions rather than relying on a fixed offset or the device's local timezone.
    Windows are [start, end): 03:00-04:00 (London), 10:00-11:00 (NY AM),
    and 14:00-15:00 (NY PM).
    """
    local_time = datetime.fromtimestamp(timestamp_ms / 1000, tz=timezone.utc).astimezone(
        ZoneInfo("America/New_York")
    ).time().replace(tzinfo=None)
    for start, end, label in (
        (time(3, 0), time(4, 0), "LONDON"),
        (time(10, 0), time(11, 0), "NEW_YORK_AM"),
        (time(14, 0), time(15, 0), "NEW_YORK_PM"),
    ):
        if start <= local_time < end:
            return label
    return None


def _waiting(series: ValidatedSeries, symbol: str, timeframe: str,
             strategy_id: str, reason: str, evidence=()) -> SignalCandidate:
    return SignalCandidate(
        symbol, strategy_id, STRATEGY_RULESETS.get(strategy_id, "NOT_IMPLEMENTED"),
        timeframe, "WAITING", "WAIT", None, None, None, None, 0, (),
        tuple(evidence), reason, series.source, series.last_timestamp_ms, False,
    )


def _candidate(series: ValidatedSeries, symbol: str, timeframe: str,
               strategy_id: str, direction: str, evidence: Sequence[str]) -> SignalCandidate:
    atr = _atr(series.candles)
    if atr is None or atr <= 0:
        return _waiting(series, symbol, timeframe, strategy_id, "INSUFFICIENT_STRUCTURE_OR_ATR")
    entry = series.candles[-1].close
    prior = series.candles[-5:-1]
    stop = (min(x.low for x in prior) - 0.25 * atr if direction == "LONG"
            else max(x.high for x in prior) + 0.25 * atr)
    risk = abs(entry - stop)
    if risk <= 0 or not isfinite(risk):
        return _waiting(series, symbol, timeframe, strategy_id, "INVALID_STRUCTURE_RISK")
    target = entry + 2 * risk if direction == "LONG" else entry - 2 * risk
    return SignalCandidate(
        symbol, strategy_id, STRATEGY_RULESETS[strategy_id], timeframe, "CANDIDATE",
        direction, entry, stop, target, 2.0, 0,
        ("rule-condition match only", "NO_EMPIRICAL_CONFIDENCE_SCORE", "minimum 2R target proposal"),
        tuple(evidence) + ("MINIMUM_2R_TARGET_PROPOSAL",),
        "Deterministic closed-candle candidate; requires independent quote, RiskEngine, broker and account validation.",
        series.source, series.last_timestamp_ms, False,
    )


def evaluate_smc(series: ValidatedSeries, *, symbol: str, timeframe: str,
                 quote_price: float | None = None) -> SignalCandidate:
    candles = series.candles
    if len(candles) < 22:
        return _waiting(series, symbol, timeframe, STRATEGY_ID, "INSUFFICIENT_CLOSED_CANDLES")
    closes = [x.close for x in candles]
    atr = _atr(candles)
    ema20 = _ema(closes, 20)
    if atr is None or ema20 is None or atr <= 0:
        return _waiting(series, symbol, timeframe, STRATEGY_ID, "INSUFFICIENT_STRUCTURE_OR_ATR")
    current, previous = candles[-1], candles[-2]
    if current.close > previous.high and current.close > ema20:
        return _candidate(series, symbol, timeframe, STRATEGY_ID, "LONG",
                          ("BOS_UP_CLOSED_CANDLE", "CLOSE_ABOVE_EMA20"))
    if current.close < previous.low and current.close < ema20:
        return _candidate(series, symbol, timeframe, STRATEGY_ID, "SHORT",
                          ("BOS_DOWN_CLOSED_CANDLE", "CLOSE_BELOW_EMA20"))
    return _waiting(series, symbol, timeframe, STRATEGY_ID, "NO_CONFIRMED_BOS")


def _evaluate_ruleset(series: ValidatedSeries, *, symbol: str, timeframe: str,
                      strategy_id: str) -> SignalCandidate:
    candles = series.candles
    minimum = {
        "ict-silver-bullet": 21, "wyckoff-schematics": 21,
        "supply-and-demand": 22, "volatility-breakout": 22,
        "breakout-momentum": 22, "statistical-mean-reversion": 22,
        "multi-ema-trend-following": 30,
    }[strategy_id]
    if len(candles) < minimum:
        return _waiting(series, symbol, timeframe, strategy_id, "INSUFFICIENT_CLOSED_CANDLES")
    current = candles[-1]
    closes = [x.close for x in candles]
    atr = _atr(candles)
    if atr is None or atr <= 0:
        return _waiting(series, symbol, timeframe, strategy_id, "INSUFFICIENT_STRUCTURE_OR_ATR")
    long = short = False
    evidence = ()
    if strategy_id == "ict-silver-bullet":
        session = _ict_silver_bullet_window(current.timestamp_ms)
        if session is None:
            return _waiting(series, symbol, timeframe, strategy_id,
                            "OUTSIDE_ICT_SILVER_BULLET_WINDOW")
        # A sweep must precede the displacement/FVG candles and be confirmed
        # against bars that precede it. No future bars are read.
        sweep = None
        for index in reversed(range(max(5, len(candles) - 8), len(candles) - 4)):
            prior = candles[index - 5:index]
            candle = candles[index]
            if candle.low < min(x.low for x in prior) and candle.close > min(x.low for x in prior):
                sweep = ("LONG", candle)
                break
            if candle.high > max(x.high for x in prior) and candle.close < max(x.high for x in prior):
                sweep = ("SHORT", candle)
                break
        if sweep is None:
            return _waiting(series, symbol, timeframe, strategy_id, "NO_CONFIRMED_LIQUIDITY_SWEEP")
        direction, _ = sweep
        fvg_left, displacement = candles[-4], candles[-2]
        body = abs(displacement.close - displacement.open)
        candle_range = displacement.high - displacement.low
        if (direction == "LONG" and displacement.close <= displacement.open) or (
            direction == "SHORT" and displacement.close >= displacement.open
        ) or body < 0.5 * atr or candle_range < atr:
            return _waiting(series, symbol, timeframe, strategy_id, "NO_CONFIRMED_DISPLACEMENT")
        if direction == "LONG":
            gap_low, gap_high = fvg_left.high, displacement.low
            if gap_high <= gap_low or current.low < gap_low:
                return _waiting(series, symbol, timeframe, strategy_id, "NO_UNMITIGATED_BULLISH_FVG")
            consequent_encroachment = (gap_low + gap_high) / 2.0
            if current.low > consequent_encroachment or current.close <= consequent_encroachment:
                return _waiting(series, symbol, timeframe, strategy_id, "FVG_CONSEQUENT_ENCROACHMENT_NOT_CONFIRMED")
            evidence = ("LIQUIDITY_SWEEP_LOW", "DISPLACEMENT_CLOSE_UP", "BULLISH_FVG",
                        "FVG_CONSEQUENT_ENCROACHMENT", f"SILVER_BULLET_{session}")
        else:
            gap_low, gap_high = displacement.high, fvg_left.low
            if gap_high <= gap_low or current.high > gap_high:
                return _waiting(series, symbol, timeframe, strategy_id, "NO_UNMITIGATED_BEARISH_FVG")
            consequent_encroachment = (gap_low + gap_high) / 2.0
            if current.high < consequent_encroachment or current.close >= consequent_encroachment:
                return _waiting(series, symbol, timeframe, strategy_id, "FVG_CONSEQUENT_ENCROACHMENT_NOT_CONFIRMED")
            evidence = ("LIQUIDITY_SWEEP_HIGH", "DISPLACEMENT_CLOSE_DOWN", "BEARISH_FVG",
                        "FVG_CONSEQUENT_ENCROACHMENT", f"SILVER_BULLET_{session}")
        return _candidate(series, symbol, timeframe, strategy_id, direction, evidence)
    elif strategy_id == "wyckoff-schematics":
        average_volume = _mean([x.volume for x in candles[-11:-1]])
        long = current.close > max(x.high for x in candles[-11:-1]) and current.volume >= average_volume
        short = current.close < min(x.low for x in candles[-11:-1]) and current.volume >= average_volume
        evidence = (("SPRING_OR_SIGN_OF_STRENGTH", "VOLUME_CONFIRMATION") if long else
                    ("UPTHRUST_OR_SIGN_OF_WEAKNESS", "VOLUME_CONFIRMATION"))
    elif strategy_id == "supply-and-demand":
        base = candles[-5:-1]
        tight = _mean([x.high - x.low for x in base]) <= 1.5 * atr
        long = tight and current.close > max(x.high for x in base)
        short = tight and current.close < min(x.low for x in base)
        evidence = (("DEMAND_BASE", "DEMAND_BREAKOUT") if long else
                    ("SUPPLY_BASE", "SUPPLY_BREAKDOWN"))
    elif strategy_id == "volatility-breakout":
        prior_atr = _atr(candles[:-1])
        expansion = prior_atr is not None and current.high - current.low >= 1.5 * prior_atr
        long = expansion and current.close > current.open
        short = expansion and current.close < current.open
        evidence = (("RANGE_EXPANSION", "CLOSE_UP") if long else
                    ("RANGE_EXPANSION", "CLOSE_DOWN"))
    elif strategy_id == "breakout-momentum":
        body = abs(current.close - current.open)
        range_size = current.high - current.low
        long = current.close > max(x.high for x in candles[-11:-1]) and body >= 0.5 * range_size
        short = current.close < min(x.low for x in candles[-11:-1]) and body >= 0.5 * range_size
        evidence = (("TEN_CANDLE_BREAKOUT", "MOMENTUM_BODY") if long else
                    ("TEN_CANDLE_BREAKDOWN", "MOMENTUM_BODY"))
    elif strategy_id == "statistical-mean-reversion":
        window = closes[-21:-1]
        mean = _mean(window)
        deviation = _std(window, mean)
        long = deviation > 0 and current.close < mean - 1.5 * deviation and current.close > current.open
        short = deviation > 0 and current.close > mean + 1.5 * deviation and current.close < current.open
        evidence = (("LOWER_BAND_DEVIATION", "REVERSAL_CLOSE") if long else
                    ("UPPER_BAND_DEVIATION", "REVERSAL_CLOSE"))
    elif strategy_id == "multi-ema-trend-following":
        ema9, ema21 = _ema(closes, 9), _ema(closes, 21)
        long = ema9 is not None and ema21 is not None and ema9 > ema21 and current.close > ema9
        short = ema9 is not None and ema21 is not None and ema9 < ema21 and current.close < ema9
        evidence = (("EMA9_ABOVE_EMA21", "CLOSE_ABOVE_EMA9") if long else
                    ("EMA9_BELOW_EMA21", "CLOSE_BELOW_EMA9"))
    if long:
        return _candidate(series, symbol, timeframe, strategy_id, "LONG", evidence)
    if short:
        return _candidate(series, symbol, timeframe, strategy_id, "SHORT", evidence)
    return _waiting(series, symbol, timeframe, strategy_id, "NO_CONFIRMED_RULESET_EVENT")


def evaluate_strategy(series: ValidatedSeries, *, strategy_id: str,
                      symbol: str, timeframe: str) -> SignalCandidate:
    """Dispatch known rulesets; unknown IDs fail closed as NOT_IMPLEMENTED."""
    if strategy_id not in STRATEGY_RULESETS:
        return _waiting(series, symbol, timeframe, strategy_id, "NOT_IMPLEMENTED")
    if strategy_id == STRATEGY_ID:
        return evaluate_smc(series, symbol=symbol, timeframe=timeframe)
    return _evaluate_ruleset(series, symbol=symbol, timeframe=timeframe, strategy_id=strategy_id)


def evaluate_smc_confluence(series: ValidatedSeries, *, symbol: str,
                            timeframe: str) -> SignalCandidate:
    return evaluate_strategy(series, strategy_id=STRATEGY_ID, symbol=symbol, timeframe=timeframe)


def analyze_json(payload: str) -> str:
    """Analyze one immutable Kotlin-validated candle snapshot."""
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
        quote_price = request.get("quote_price")
        if quote_price is not None:
            quote_price = float(quote_price)
            if not isfinite(quote_price) or quote_price <= 0:
                raise DataIntegrityError("INVALID_QUOTE_PRICE")
        rows = request.get("candles")
        if not symbol or not timeframe or not strategy_id or not isinstance(rows, list):
            raise DataIntegrityError("INVALID_REQUEST")
        candles = tuple(OHLCV(
            timestamp_ms=int(row["timestamp_ms"]), open=float(row["open"]),
            high=float(row["high"]), low=float(row["low"]), close=float(row["close"]),
            volume=float(row.get("volume", 0.0)), complete=bool(row.get("complete", True)),
            source=str(row.get("source", source)),
            retrieved_at_ms=int(row.get("retrieved_at_ms", provider_retrieved_at_ms)),
        ) for row in rows)
        if strategy_id not in STRATEGY_RULESETS:
            result = _waiting(ValidatedSeries(candles, source, candles[-1].timestamp_ms if candles else 0, None),
                              symbol, timeframe, strategy_id, "NOT_IMPLEMENTED")
        else:
            series = validate_ohlcv(
                candles, now_ms=now_ms, source=source,
                expected_interval_ms=expected_interval_ms,
                allow_session_gaps=bool(request.get("allow_session_gaps", False)),
            )
            result = evaluate_strategy(series, strategy_id=strategy_id, symbol=symbol, timeframe=timeframe)
        response = result.__dict__.copy()
        response["evidence"] = list(result.evidence)
        response["score_basis"] = list(result.score_basis)
        return json.dumps(response, sort_keys=True)
    except (DataIntegrityError, KeyError, TypeError, ValueError, OverflowError, IndexError) as error:
        return json.dumps({
            "state": "DATA_UNAVAILABLE", "direction": "WAIT", "executable": False,
            "reason": str(error) or "INVALID_REQUEST",
        }, sort_keys=True)

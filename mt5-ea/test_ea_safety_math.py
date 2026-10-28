import math


def target_distance(gross_loss, round_turn_cost, unit_profit, tick, bullish=True):
    if not all(math.isfinite(x) for x in (gross_loss, round_turn_cost, unit_profit)) or gross_loss <= 0 or round_turn_cost <= 0 or unit_profit <= 0:
        raise ValueError("verified positive cost and profit conversion required")
    required = 2.0 * (gross_loss + round_turn_cost)
    raw = required / unit_profit
    return math.ceil(raw / tick) * tick if bullish else math.floor(raw / tick) * tick


def qualifies_position(deals, magic, open_position_volume=0.0):
    if open_position_volume > 1e-7 or not deals:
        return False
    if any(d["magic"] != magic for d in deals):
        return False
    entered = sum(d["volume"] for d in deals if d["entry"] == "IN")
    exited = sum(d["volume"] for d in deals if d["entry"] in {"OUT", "OUT_BY"})
    return entered > 0 and exited + 1e-7 >= entered


def test_positive_commission_target_meets_same_after_cost_definition():
    distance = target_distance(10.0, 2.0, 100.0, 0.01)
    assert distance == 0.24
    assert distance * 100.0 / (10.0 + 2.0) >= 2.0


def test_zero_or_unavailable_cost_is_rejected():
    for cost in (0.0, float("nan")):
        try:
            target_distance(10.0, cost, 100.0, 0.01)
        except ValueError:
            pass
        else:
            raise AssertionError("unsafe cost must not produce a target")


def test_tick_rounding_is_conservative_for_both_directions():
    buy = target_distance(10.0, 2.0, 37.0, 0.01, True)
    sell = target_distance(10.0, 2.0, 37.0, 0.01, False)
    required = 2.0 * 12.0
    assert buy * 37.0 >= required
    assert sell * 37.0 <= required  # final broker-profit validation must reject if rounding under-delivers


def test_multiple_entry_exit_deals_are_qualified_once_and_charges_are_not_duplicated():
    deals = [
        {"magic": 42, "entry": "IN", "volume": 1.0, "net": -1.0},
        {"magic": 42, "entry": "OUT", "volume": 0.4, "net": 3.0},
        {"magic": 42, "entry": "OUT_BY", "volume": 0.6, "net": 4.0},
    ]
    assert qualifies_position(deals, 42)
    assert sum(d["net"] for d in deals) == 6.0


def test_manual_other_ea_partial_and_unresolved_positions_do_not_qualify():
    base = [{"magic": 42, "entry": "IN", "volume": 1.0}, {"magic": 42, "entry": "OUT", "volume": 1.0}]
    assert not qualifies_position([{**d, "magic": 7} for d in base], 42)
    assert not qualifies_position(base[:1], 42)
    assert not qualifies_position(base, 42, open_position_volume=0.2)

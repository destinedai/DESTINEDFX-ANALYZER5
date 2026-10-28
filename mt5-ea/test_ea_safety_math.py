import math


def required_gross_reward(gross_loss, round_turn_cost):
    if not all(math.isfinite(x) for x in (gross_loss, round_turn_cost)) or gross_loss <= 0 or round_turn_cost <= 0:
        raise ValueError("verified positive gross loss and round-turn cost are required")
    # Net reward = gross reward - cost; net risk = gross loss + cost.
    # Require net reward >= 2 * net risk.
    return 2.0 * gross_loss + 3.0 * round_turn_cost


def target_distance(gross_loss, round_turn_cost, unit_profit, tick):
    if not math.isfinite(unit_profit) or unit_profit <= 0 or not math.isfinite(tick) or tick <= 0:
        raise ValueError("positive finite profit conversion and tick are required")
    raw = required_gross_reward(gross_loss, round_turn_cost) / unit_profit
    return math.ceil(raw / tick - 1e-12) * tick


def observed_round_turn_cost_per_lot(positions, symbol, minimum_closed_positions=3):
    total_charges = 0.0
    total_entered_volume = 0.0
    complete_count = 0
    for position in positions:
        if position.get("symbol") != symbol:
            continue
        entered = position.get("entered_volume")
        exited = position.get("exited_volume")
        open_volume = position.get("open_volume")
        deals = position.get("deals")
        numbers = (entered, exited, open_volume)
        if not all(isinstance(x, (int, float)) and math.isfinite(x) for x in numbers):
            continue
        if entered <= 0 or open_volume > 1e-7 or abs(exited - entered) > max(1e-7, entered * 1e-5):
            continue
        if not isinstance(deals, list) or not deals:
            continue
        if not any(d.get("entry") == "IN" for d in deals) or not any(d.get("entry") in {"OUT", "OUT_BY"} for d in deals):
            continue
        charges = 0.0
        valid = True
        for deal in deals:
            for field in ("commission", "fees", "swap"):
                value = deal.get(field)
                if not isinstance(value, (int, float)) or not math.isfinite(value):
                    valid = False
                    break
                charges += abs(value)
            if not valid:
                break
        if not valid or charges <= 0:
            continue
        total_charges += charges
        total_entered_volume += entered
        complete_count += 1
    if complete_count < minimum_closed_positions or total_entered_volume <= 0 or total_charges <= 0:
        return None
    return total_charges / total_entered_volume


def test_net_two_r_after_costs_includes_cost_on_winning_and_losing_outcomes():
    distance = target_distance(gross_loss=10.0, round_turn_cost=2.0, unit_profit=100.0, tick=0.01)
    gross_reward = distance * 100.0
    net_reward = gross_reward - 2.0
    net_risk = 10.0 + 2.0
    assert distance == 0.26
    assert net_reward >= 2.0 * net_risk
    assert math.isclose(net_reward / net_risk, 2.0, abs_tol=1e-9)


def test_conservative_tick_rounding_keeps_net_two_r_for_buy_and_sell_targets():
    required = required_gross_reward(10.0, 2.0)
    for unit_profit in (37.0, 83.5, 100.0):
        distance = target_distance(10.0, 2.0, unit_profit, 0.01)
        assert distance * unit_profit + 1e-9 >= required
        assert distance * unit_profit - 2.0 + 1e-9 >= 2.0 * (10.0 + 2.0)


def test_slippage_allowance_is_included_in_the_same_cost_budget():
    commission = 2.0
    round_trip_slippage = 0.5
    all_in_cost = commission + round_trip_slippage
    target = target_distance(10.0, all_in_cost, 100.0, 0.01)
    assert target * 100.0 - all_in_cost >= 2.0 * (10.0 + all_in_cost)


def test_zero_or_unavailable_cost_and_invalid_ticks_are_rejected():
    cases = [(10.0, 0.0, 100.0, 0.01), (10.0, float("nan"), 100.0, 0.01),
             (10.0, 2.0, float("inf"), 0.01), (10.0, 2.0, 100.0, 0.0)]
    for loss, cost, unit, tick in cases:
        try:
            target_distance(loss, cost, unit, tick)
        except ValueError:
            pass
        else:
            raise AssertionError("unsafe inputs must not produce a target")


def test_observed_cost_sums_entry_exit_commission_fees_and_historical_swap():
    positions = []
    for index in range(3):
        positions.append({
            "symbol": "EURUSD",
            "entered_volume": 1.0,
            "exited_volume": 1.0,
            "open_volume": 0.0,
            "deals": [
                {"entry": "IN", "commission": -3.0, "fees": -0.1, "swap": 0.0},
                {"entry": "OUT", "commission": -3.0, "fees": -0.1, "swap": -0.8},
            ],
        })
    assert math.isclose(observed_round_turn_cost_per_lot(positions, "EURUSD"), 7.0)


def test_observed_cost_requires_three_complete_closed_positions_and_positive_cost():
    base = {
        "symbol": "EURUSD", "entered_volume": 1.0, "exited_volume": 1.0, "open_volume": 0.0,
        "deals": [
            {"entry": "IN", "commission": -3.0, "fees": 0.0, "swap": 0.0},
            {"entry": "OUT", "commission": -3.0, "fees": 0.0, "swap": 0.0},
        ],
    }
    assert observed_round_turn_cost_per_lot([base, base], "EURUSD") is None
    assert observed_round_turn_cost_per_lot([base, base, {**base, "exited_volume": 0.5}], "EURUSD") is None
    assert observed_round_turn_cost_per_lot([base, base, {**base, "open_volume": 0.2}], "EURUSD") is None
    no_cost = {**base, "deals": [
        {"entry": "IN", "commission": 0.0, "fees": 0.0, "swap": 0.0},
        {"entry": "OUT", "commission": 0.0, "fees": 0.0, "swap": 0.0},
    ]}
    assert observed_round_turn_cost_per_lot([no_cost] * 3, "EURUSD") is None


def test_wrong_symbol_and_non_finite_cost_data_are_not_counted():
    base = {
        "symbol": "EURUSD", "entered_volume": 1.0, "exited_volume": 1.0, "open_volume": 0.0,
        "deals": [
            {"entry": "IN", "commission": -3.0, "fees": 0.0, "swap": 0.0},
            {"entry": "OUT", "commission": -3.0, "fees": 0.0, "swap": 0.0},
        ],
    }
    assert observed_round_turn_cost_per_lot([base] * 3, "GBPUSD") is None
    invalid = {**base, "deals": [
        {"entry": "IN", "commission": -3.0, "fees": 0.0, "swap": 0.0},
        {"entry": "OUT", "commission": float("nan"), "fees": 0.0, "swap": 0.0},
    ]}
    assert observed_round_turn_cost_per_lot([base, base, invalid], "EURUSD") is None

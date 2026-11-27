from pathlib import Path

EA = Path(__file__).with_name("DestinedFXTraderEA.mq5").read_text(encoding="utf-8")


def test_every_automatic_ea_entry_is_gated_by_closed_higher_timeframe_structure():
    gate_start = EA.index("bool ConfirmHigherTimeframeDirection(")
    gate_end = EA.index("int VolDigits(", gate_start)
    gate = EA[gate_start:gate_end]
    assert "HigherTimeframeFor(executionTf)" in gate
    assert "ClosedRates(sym,higherTf,higher,80)" in gate
    assert "EmaClosed(sym,higherTf,20)" in gate
    assert "EmaClosed(sym,higherTf,50)" in gate
    assert "iADX(sym,higherTf,14)" in gate
    assert "higherHigh&&higherLow" in gate
    assert "lowerHigh&&lowerLow" in gate
    assert "bool ranging=" in gate
    assert "if(ranging)" in gate
    assert 'if(bull&&!uptrend)' in gate
    assert 'if(!bull&&!downtrend)' in gate
    assert "No higher timeframe exists" in gate
    scan_start = EA.index("void Scan()")
    scan_end = EA.index("bool Ack(", scan_start)
    scan = EA[scan_start:scan_end]
    assert scan.index("FindStrategySetup(") < scan.index("ConfirmHigherTimeframeDirection(")
    assert scan.index("ConfirmHigherTimeframeDirection(") < scan.index("Place(sym,tf,bull")
    assert 'SetWait(sym,g_tfs[t],why);continue;' in scan


def test_bot_start_keeps_market_watch_auto_scan_and_never_replaces_it_with_phone_symbol():
    assert 'requested=="AUTO"' in EA
    assert "SymbolsTotal(true)" in EA
    assert "ArraySize(symbols)<32" in EA
    assert 'g_state="RUNNING"' in EA
    assert "Scan();" in EA

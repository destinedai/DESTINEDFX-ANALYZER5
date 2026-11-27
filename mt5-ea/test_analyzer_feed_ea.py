from pathlib import Path


SOURCE = Path(__file__).with_name("AnalyzerMarketFeedEA.mq5").read_text(encoding="utf-8")


def test_analyzer_publisher_reads_live_mt5_quotes_and_closed_bars():
    assert "SymbolInfoTick(" in SOURCE
    assert "CopyRates(" in SOURCE
    assert "SYMBOL_DIGITS" in SOURCE
    assert "TERMINAL_CONNECTED" in SOURCE
    assert "/v1/analyzer/market-heartbeat" in SOURCE
    assert "/v1/analyzer/market-selection" in SOURCE
    assert "broker_quotes" in SOURCE
    assert "broker_candles" in SOURCE


def test_analyzer_publisher_is_separate_from_bot_and_has_no_trade_or_pairing_commands():
    lowered = SOURCE.lower()
    assert "trade.buy(" not in lowered
    assert "trade.sell(" not in lowered
    assert "positionclose(" not in lowered
    assert "pairing_code" not in lowered
    assert "app_token" not in lowered
    assert "authorization:" not in lowered
    assert "InpAnalyzerFeedBaseURL" in SOURCE
    assert "http://127.0.0.1:8000" in SOURCE

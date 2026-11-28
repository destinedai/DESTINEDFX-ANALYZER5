from pathlib import Path

from mt5_server_catalog import search_mt5_servers


def write_catalog(root: Path, labels: list[str]) -> None:
    config = root / "Config"
    config.mkdir(parents=True)
    # UTF-16LE, null-separated labels mirror the readable index within servers.dat.
    payload = "\x00".join(labels).encode("utf-16le")
    (config / "servers.dat").write_bytes(payload)


def test_fbs_exness_and_deriv_are_discovered_from_catalogue_for_both_modes(tmp_path):
    write_catalog(tmp_path, [
        "FBS-Demo",
        "FBS-Real",
        "Exness-MT5Trial5",
        "Exness-MT5Real7",
        "Deriv-Demo",
        "Deriv-Real",
        "Unrelated-Demo",
    ])

    assert search_mt5_servers("FBS", "DEMO", "FBS", terminal_root=tmp_path) == ["FBS-Demo"]
    assert search_mt5_servers("FBS", "LIVE", "FBS", terminal_root=tmp_path) == ["FBS-Real"]
    assert search_mt5_servers("Exness", "DEMO", "Exness", terminal_root=tmp_path) == ["Exness-MT5Trial5"]
    assert search_mt5_servers("Exness", "LIVE", "Exness", terminal_root=tmp_path) == ["Exness-MT5Real7"]
    assert search_mt5_servers("Deriv", "DEMO", "Deriv", terminal_root=tmp_path) == ["Deriv-Demo"]
    assert search_mt5_servers("Deriv", "LIVE", "Deriv", terminal_root=tmp_path) == ["Deriv-Real"]


def test_catalogue_search_is_generic_for_additional_brokers_without_a_code_allowlist(tmp_path):
    labels = [
        "XMGlobal-Demo", "XMGlobal-Real",
        "OctaFX-Demo", "OctaFX-Real",
        "ICMarketsSC-Demo", "ICMarketsSC-Live",
        "Pepperstone-EdgeDemo", "Pepperstone-EdgeLive",
        "Tickmill-Demo", "Tickmill-Live",
        "RoboForex-ProDemo", "RoboForex-ProReal",
    ]
    write_catalog(tmp_path, labels)

    searches = [
        ("XM", "XMGlobal-Demo", "XMGlobal-Real"),
        ("OctaFX", "OctaFX-Demo", "OctaFX-Real"),
        ("IC Markets", "ICMarketsSC-Demo", "ICMarketsSC-Live"),
        ("Pepperstone", "Pepperstone-EdgeDemo", "Pepperstone-EdgeLive"),
        ("Tickmill", "Tickmill-Demo", "Tickmill-Live"),
        ("RoboForex", "RoboForex-ProDemo", "RoboForex-ProReal"),
    ]
    for broker, demo_server, live_server in searches:
        assert search_mt5_servers(broker, "DEMO", broker, terminal_root=tmp_path) == [demo_server]
        assert search_mt5_servers(broker, "LIVE", broker, terminal_root=tmp_path) == [live_server]


def test_catalogue_absence_never_invents_fbs_or_other_broker_servers(tmp_path):
    assert search_mt5_servers("FBS", "DEMO", "FBS", terminal_root=tmp_path) == []
    assert search_mt5_servers("FBS", "LIVE", "FBS", terminal_root=tmp_path) == []
    assert search_mt5_servers("Exness", "DEMO", "Exness", terminal_root=tmp_path) == []
    assert search_mt5_servers("Deriv", "LIVE", "Deriv", terminal_root=tmp_path) == []
    assert search_mt5_servers("UnknownBroker", "DEMO", "UnknownBroker", terminal_root=tmp_path) == []


def test_mode_filter_rejects_ambiguous_and_wrong_mode_catalogue_entries(tmp_path):
    write_catalog(tmp_path, [
        "FBS-Demo", "FBS-Real", "FBS-Server", "FBS-DemoLive",
        "Exness-MT5Trial5", "Exness-MT5Real7",
    ])
    assert search_mt5_servers("FBS", "DEMO", "FBS", terminal_root=tmp_path) == ["FBS-Demo"]
    assert search_mt5_servers("FBS", "LIVE", "FBS", terminal_root=tmp_path) == ["FBS-Real"]
    assert search_mt5_servers("Exness", "DEMO", "Exness", terminal_root=tmp_path) == ["Exness-MT5Trial5"]
    assert search_mt5_servers("Exness", "LIVE", "Exness", terminal_root=tmp_path) == ["Exness-MT5Real7"]
    assert search_mt5_servers("FBS", "PAPER", "FBS", terminal_root=tmp_path) == []
    assert search_mt5_servers("FBS", "DEMO", "x" * 97, terminal_root=tmp_path) == []
    assert search_mt5_servers("x" * 65, "DEMO", "x" * 65, terminal_root=tmp_path) == []

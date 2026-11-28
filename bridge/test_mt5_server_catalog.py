from pathlib import Path

from mt5_server_catalog import search_mt5_servers


def write_catalog(root: Path, labels: list[str]) -> None:
    config = root / "Config"
    config.mkdir(parents=True)
    # Match the terminal's UTF-16LE, null-terminated label storage.
    payload = "\x00".join(labels).encode("utf-16le")
    (config / "servers.dat").write_bytes(payload)


def test_search_returns_only_catalogued_or_owner_confirmed_servers_and_separates_modes(tmp_path):
    write_catalog(tmp_path, [
        "FBS-Demo",
        "FBS-Real",
        "Exness-MT5Trial5",
        "Exness-MT5Real7",
        "HFMarketsGlobal-Demo",
        "HFMarketsGlobal-Live1",
        "Deriv-Demo",
        "Deriv-Real",
        "Unrelated-Demo",
    ])

    assert search_mt5_servers("FBS", "DEMO", "FBS", terminal_root=tmp_path) == ["FBS-Demo"]
    assert search_mt5_servers("FBS", "LIVE", "FBS", terminal_root=tmp_path) == ["FBS-Real"]
    assert search_mt5_servers("EXNESS", "DEMO", "Exness", terminal_root=tmp_path) == ["Exness-MT5Trial5"]
    assert search_mt5_servers("EXNESS", "LIVE", "Exness", terminal_root=tmp_path) == ["Exness-MT5Real7"]
    assert search_mt5_servers("HFM", "DEMO", "HFM", terminal_root=tmp_path) == ["HFMarketsGlobal-Demo"]
    assert search_mt5_servers("HFM", "LIVE", "HFM", terminal_root=tmp_path) == ["HFMarketsGlobal-Live1"]
    assert search_mt5_servers("DERIV", "DEMO", "Deriv", terminal_root=tmp_path) == ["Deriv-Demo"]
    assert search_mt5_servers("DERIV", "LIVE", "Deriv", terminal_root=tmp_path) == ["Deriv-Real"]


def test_search_never_invents_or_accepts_unclassified_server_names(tmp_path):
    write_catalog(tmp_path, ["FBS-Unknown", "EXNESS-MT5-Server"])
    assert search_mt5_servers("FBS", "DEMO", "FBS", terminal_root=tmp_path) == ["FBS-Demo"]
    assert search_mt5_servers("FBS", "LIVE", "FBS", terminal_root=tmp_path) == ["FBS-Real"]
    assert search_mt5_servers("FBS", "DEMO", "FBS-Unknown", terminal_root=tmp_path) == []
    assert search_mt5_servers("EXNESS", "LIVE", "EXNESS", terminal_root=tmp_path) == []
    assert search_mt5_servers("UNKNOWN", "LIVE", "FBS", terminal_root=tmp_path) == []
    assert search_mt5_servers("FBS", "DEMO", "x" * 97, terminal_root=tmp_path) == []


def test_missing_catalogue_returns_only_explicit_owner_confirmed_fbs_labels(tmp_path):
    assert search_mt5_servers("FBS", "DEMO", "FBS", terminal_root=tmp_path) == ["FBS-Demo"]
    assert search_mt5_servers("FBS", "LIVE", "FBS", terminal_root=tmp_path) == ["FBS-Real"]
    assert search_mt5_servers("EXNESS", "DEMO", "EXNESS", terminal_root=tmp_path) == []
    assert search_mt5_servers("HFM", "DEMO", "HFM", terminal_root=tmp_path) == []
    assert search_mt5_servers("DERIV", "LIVE", "DERIV", terminal_root=tmp_path) == []

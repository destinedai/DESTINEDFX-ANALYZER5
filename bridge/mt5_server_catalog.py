"""Read-only search of the genuine MetaTrader terminal server catalogue.

MetaTrader stores discovered trading servers in Config/servers.dat. Results are exact labels
from that catalogue plus the two FBS labels explicitly confirmed by the account owner.
"""
from __future__ import annotations

import os
import re
import stat
from pathlib import Path

MAX_CATALOG_BYTES = 16 * 1024 * 1024
SERVER_NAME = re.compile(r"(?<![A-Za-z0-9])([A-Za-z0-9][A-Za-z0-9._-]{2,95})(?![A-Za-z0-9])")
BROKER_MARKERS = {
    "FBS": ("FBS",),
    "EXNESS": ("EXNESS",),
    "HFM": ("HFM", "HFMARKETS"),
    "DERIV": ("DERIV",),
}
# The owner explicitly confirmed these two exact labels from the original FBS MT5
# search results. Other broker labels must come from the installed MT5 catalogue.
OWNER_CONFIRMED_LABELS = {
    "FBS": {"DEMO": ("FBS-Demo",), "LIVE": ("FBS-Real",)},
}


def _read_server_labels(path: Path) -> set[str]:
    try:
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            return set()
        if info.st_size <= 0 or info.st_size > MAX_CATALOG_BYTES:
            return set()
        raw = path.read_bytes()
    except OSError:
        return set()

    found: dict[str, str] = {}
    # MetaTrader's binary catalogue stores readable labels as UTF-16 strings.
    # ASCII is also scanned for builds that store a portion of the index narrowly.
    for encoding in ("utf-16le", "utf-16be", "utf-8", "latin-1"):
        decoded = raw.decode(encoding, errors="ignore")
        for match in SERVER_NAME.finditer(decoded):
            label = match.group(1).strip("._-")
            if not label:
                continue
            found.setdefault(label.upper(), label)
    return set(found.values())


def _server_mode(label: str) -> str | None:
    folded = label.casefold()
    if any(marker in folded for marker in ("demo", "trial", "contest", "practice")):
        return "DEMO"
    if any(marker in folded for marker in ("real", "live")):
        return "LIVE"
    return None


def _matches_broker(label: str, broker: str) -> bool:
    normalized = re.sub(r"[^A-Z0-9]", "", label.upper())
    return any(marker in normalized for marker in BROKER_MARKERS[broker])


def search_mt5_servers(
    broker: str,
    mode: str,
    query: str = "",
    *,
    terminal_root: str | Path | None = None,
) -> list[str]:
    """Return exact catalogue or owner-confirmed labels; no free-typed server is accepted."""
    brand = str(broker).strip().upper()
    account_mode = str(mode).strip().upper()
    search = str(query).strip().casefold()
    if brand not in BROKER_MARKERS or account_mode not in {"DEMO", "LIVE"}:
        return []
    if len(search) > 96:
        return []

    if terminal_root is None:
        configured_root = os.environ.get("DESTINEDFX_MT5_TERMINAL_TEMPLATE", "").strip()
        if not configured_root:
            return []
        root = Path(configured_root)
    else:
        root = Path(terminal_root)

    # The terminal is copied with /portable, so Config/servers.dat lives beside
    # terminal64.exe. The lowercase spelling is accepted for Wine compatibility.
    paths = (root / "Config" / "servers.dat", root / "config" / "servers.dat")
    labels: dict[str, str] = {}
    for label in OWNER_CONFIRMED_LABELS.get(brand, {}).get(account_mode, ()):
        if not search or search in label.casefold():
            labels.setdefault(label.casefold(), label)
    for path in paths:
        for label in _read_server_labels(path):
            if not _matches_broker(label, brand):
                continue
            if _server_mode(label) != account_mode:
                continue
            if search and search not in label.casefold():
                continue
            labels.setdefault(label.casefold(), label)
    return sorted(labels.values(), key=str.casefold)

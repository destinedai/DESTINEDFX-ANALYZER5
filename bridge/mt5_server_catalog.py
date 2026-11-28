"""Generic, read-only search of the installed MetaTrader server catalogue.

Broker names are search terms, not a supported-broker allowlist. Results must be readable
server labels in the installed terminal's Config/servers.dat and must carry a clear account-mode
marker. No provider server names are fabricated when the catalogue is absent.
"""
from __future__ import annotations

import os
import re
import stat
from pathlib import Path

MAX_CATALOG_BYTES = 16 * 1024 * 1024
MAX_BROKER_QUERY_LENGTH = 64
SERVER_NAME = re.compile(r"(?<![A-Za-z0-9])([A-Za-z0-9][A-Za-z0-9._-]{2,95})(?![A-Za-z0-9])")
DEMO_MARKERS = ("DEMO", "TRIAL", "CONTEST", "PRACTICE")
LIVE_MARKERS = ("REAL", "LIVE")


def _normalise_search(value: str) -> str:
    return re.sub(r"[^A-Z0-9]", "", str(value or "").upper())


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
    # MetaTrader server names are stored as readable UTF-16 strings in the binary catalogue.
    # Scan UTF-16 and the narrow encodings used by some terminal builds.
    for encoding in ("utf-16le", "utf-16be", "utf-8", "latin-1"):
        decoded = raw.decode(encoding, errors="ignore")
        for match in SERVER_NAME.finditer(decoded):
            label = match.group(1).strip("._-")
            if label:
                found.setdefault(label.upper(), label)
    return set(found.values())


def _server_mode(label: str) -> str | None:
    normalized = _normalise_search(label)
    is_demo = any(marker in normalized for marker in DEMO_MARKERS)
    is_live = any(marker in normalized for marker in LIVE_MARKERS)
    # Ambiguous labels (neither mode, or both modes) are never returned.
    if is_demo == is_live:
        return None
    return "DEMO" if is_demo else "LIVE"


def _matches_broker(label: str, broker_query: str) -> bool:
    normalized_label = _normalise_search(label)
    normalized_broker = _normalise_search(broker_query)
    return bool(
        len(normalized_broker) >= 2
        and normalized_broker in normalized_label
    )


def search_mt5_servers(
    broker: str,
    mode: str,
    query: str = "",
    *,
    terminal_root: str | Path | None = None,
) -> list[str]:
    """Search catalogue labels by any typed broker name and exact DEMO/REAL mode."""
    broker_query = str(broker or "").strip()
    account_mode = str(mode or "").strip().upper()
    search_query = str(query or "").strip()

    normalized_broker = _normalise_search(broker_query)
    normalized_query = _normalise_search(search_query)
    if not 2 <= len(normalized_broker) <= MAX_BROKER_QUERY_LENGTH:
        return []
    if account_mode not in {"DEMO", "LIVE"}:
        return []
    if len(search_query) > 96:
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
    for path in paths:
        for label in _read_server_labels(path):
            if not _matches_broker(label, broker_query):
                continue
            if normalized_query and normalized_query not in _normalise_search(label):
                continue
            if _server_mode(label) != account_mode:
                continue
            labels.setdefault(label.casefold(), label)
    return sorted(labels.values(), key=str.casefold)

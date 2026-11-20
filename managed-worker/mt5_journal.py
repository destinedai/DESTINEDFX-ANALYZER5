"""Conservative MT5 journal decoding and authorization failure classification.

All example logs in tests are synthetic fixtures. This module returns bounded
codes only; it never returns or logs journal text, login values, or passwords.
"""
from __future__ import annotations

import datetime as _datetime
import re
from pathlib import Path

MAX_JOURNAL_BYTES = 65_536
_TIMESTAMP = re.compile(r"^\s*(\d{4}\.\d{2}\.\d{2})\s+(\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?)")
_AUTH_FAILURE = re.compile(
    r"\bNetwork\s+['\"](?P<login>[0-9]{1,20})['\"]\s*:\s*"
    r"authorization\s+on\s+(?P<server>.+?)\s+failed\s*\((?P<reason>[^()]*)\)",
    re.IGNORECASE,
)


def decode_mt5_journal(data: bytes) -> str:
    """Decode BOM-marked UTF-16 journals and UTF-8 fallbacks without dropping text."""
    if data.startswith(b"\xff\xfe"):
        return data[2:].decode("utf-16-le", errors="replace")
    if data.startswith(b"\xfe\xff"):
        return data[2:].decode("utf-16-be", errors="replace")
    # A bounded tail may not contain the file BOM. UTF-16 ASCII text has a
    # strong alternating-NUL signature; do not feed it to errors=ignore UTF-8.
    even_nuls = data[0::2].count(0)
    odd_nuls = data[1::2].count(0)
    if odd_nuls >= 4 and odd_nuls > even_nuls * 2:
        return data.decode("utf-16-le", errors="replace")
    if even_nuls >= 4 and even_nuls > odd_nuls * 2:
        return data.decode("utf-16-be", errors="replace")
    try:
        return data.decode("utf-8-sig")
    except UnicodeDecodeError:
        return data.decode("utf-8", errors="replace")


def _line_epoch(line: str) -> float | None:
    match = _TIMESTAMP.match(line)
    if not match:
        return None
    value = f"{match.group(1)} {match.group(2)}"
    fmt = "%Y.%m.%d %H:%M:%S.%f" if "." in match.group(2) else "%Y.%m.%d %H:%M:%S"
    try:
        # MT5 journal timestamps are local terminal time. Convert with the
        # same host timezone as time.time() so the cutoff is comparable.
        return _datetime.datetime.strptime(value, fmt).timestamp()
    except ValueError:
        return None


def classify_mt5_journal(text: str, since_epoch: float | None = None) -> str:
    """Classify the newest real-format authorization failure after a cutoff."""
    events: list[tuple[float, str]] = []
    for line in (text or "").splitlines():
        match = _AUTH_FAILURE.search(line)
        if not match:
            continue
        stamp = _line_epoch(line)
        if since_epoch is not None:
            if stamp is None or stamp < since_epoch:
                continue
        events.append((stamp if stamp is not None else 0.0, match.group("reason").strip()))
    if not events:
        return "LOGIN_FAILED_UNCLASSIFIED"

    reason = events[-1][1].casefold()
    compact = re.sub(r"[^a-z0-9]+", " ", reason).strip()
    if any(term in compact for term in (
        "invalid account", "invalid password", "wrong password", "incorrect password",
        "password incorrect", "invalid credentials",
    )):
        return "WRONG_CREDENTIALS"
    if any(term in compact for term in ("no connection", "connection failed", "connection lost")):
        return "NO_CONNECTION"
    if any(term in compact for term in ("server not found", "unknown server")):
        return "SERVER_NOT_FOUND"
    if any(term in compact for term in ("account disabled", "account is disabled", "account blocked")):
        return "ACCOUNT_DISABLED"
    if any(term in compact for term in ("old version", "client version too old")):
        return "TERMINAL_VERSION_UNSUPPORTED"
    # In particular, do not infer meaning from the broad words "network",
    # "authorization failed", or "not allowed".
    return "LOGIN_FAILED_UNCLASSIFIED"


def read_recent_mt5_journal(path: str | Path, max_bytes: int = MAX_JOURNAL_BYTES) -> str:
    """Read a bounded journal tail; pass its text to the cutoff-aware classifier."""
    file_path = Path(path)
    limit = max(1, min(int(max_bytes), MAX_JOURNAL_BYTES))
    with file_path.open("rb") as stream:
        stream.seek(0, 2)
        size = stream.tell()
        start = max(0, size - limit)
        if start and start % 2:
            start += 1
        stream.seek(start)
        data = stream.read(limit)
    text = decode_mt5_journal(data)
    if start:
        # The first line may be cut by the byte bound. Discard it rather than
        # risk parsing a truncated timestamp or authorization message.
        text = text.split("\n", 1)[1] if "\n" in text else ""
    return text


def latest_journal_file(path: str | Path) -> Path | None:
    """Return the newest regular .log file from an MT5 Logs directory."""
    directory = Path(path)
    try:
        candidates = [item for item in directory.glob("*.log") if item.is_file() and not item.is_symlink()]
        return max(candidates, key=lambda item: item.stat().st_mtime, default=None)
    except OSError:
        return None


def read_attempt_start(path: str | Path) -> float | None:
    try:
        value = float(Path(path).read_text(encoding="ascii").strip())
        return value if value > 0 else None
    except (OSError, ValueError, UnicodeError):
        return None

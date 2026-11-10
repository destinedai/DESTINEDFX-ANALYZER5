#!/usr/bin/env python3
"""Collect redacted MT5/Wine deployment diagnostics without changing state."""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from urllib.parse import urlsplit

_SAFE_PROVENANCE_KEYS = {
    "verified_at_utc",
    "wrapper_host",
    "wrapper_sha256",
    "wrapper_expected_sha256",
    "setup_host",
    "setup_sha256",
    "setup_expected_sha256",
    "authenticode_status",
    "authenticode_signer",
}
_BUILD_PATTERN = re.compile(r"\bbuild\s*[:#]?\s*(\d{4,})\b", re.IGNORECASE)
_RELEVANT_LOG = re.compile(r"err:|warn|error|fail|block|debugger|build|started", re.IGNORECASE)
_SECRET_VALUE = re.compile(
    r"(?i)\b(password|passwd|token|pairing(?:[_ ]code)?|authorization|login|account)\b"
    r"([\s:=]+)([^\s,;]+)"
)
_LONG_HEX = re.compile(r"\b[0-9a-f]{40,}\b", re.IGNORECASE)
_LONG_TOKEN = re.compile(r"\b[A-Za-z0-9_-]{32,}\b")
_EMAIL = re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.IGNORECASE)
_SENSITIVE_LOG = re.compile(
    r"password|passwd|token|bearer|pairing|credential|account|login|server|broker|company|customer|identity|username|user|authorization",
    re.IGNORECASE,
)


def _run(argv: list[str], *, env: dict[str, str] | None = None) -> str:
    try:
        result = subprocess.run(
            argv,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=8,
            check=False,
            env=env,
        )
    except (OSError, subprocess.SubprocessError):
        return ""
    return result.stdout.strip() if result.returncode == 0 else ""


def _display_path(path: Path, home: Path) -> str:
    try:
        return "~/" + str(path.resolve().relative_to(home.resolve()))
    except (OSError, ValueError):
        return str(path)


def resolve_terminal_path(home: Path, explicit: str = "") -> Path:
    candidates = [Path(explicit).expanduser()] if explicit else []
    candidates.extend(
        home / relative
        for relative in (
            ".mt5/drive_c/Program Files/MetaTrader 5/terminal64.exe",
            ".wine/drive_c/Program Files/MetaTrader 5/terminal64.exe",
        )
    )
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    return candidates[0] if candidates else home / ".mt5/drive_c/Program Files/MetaTrader 5/terminal64.exe"


def _package_info(run_command=_run) -> dict[str, str]:
    version = run_command([
        "dpkg-query", "-W", "-f=${binary:Package} ${Version}\\n", "wine", "wine64"
    ])
    policy = run_command(["apt-cache", "policy", "wine"])
    installed = ""
    for line in version.splitlines():
        package, _, package_version = line.partition(" ")
        if package.split(":", 1)[0] == "wine":
            installed = package_version.strip()
            break
    origins = []
    in_installed_version = False
    for line in policy.splitlines():
        version_match = re.match(r"\s*(?:\*\*\*\s+)?(\S+)\s+\d+\s*$", line)
        if version_match:
            in_installed_version = version_match.group(1) == installed
            continue
        if in_installed_version:
            origin_match = re.match(r"\s+\d+\s+(https?://\S+)\s+(.+)$", line)
            if origin_match:
                parsed = urlsplit(origin_match.group(1))
                if parsed.hostname:
                    origins.append(f"{parsed.hostname} {origin_match.group(2).strip()}")
    return {
        "packages": version or "unavailable",
        "origin": ", ".join(dict.fromkeys(origins)) if origins else "unavailable",
    }


def _read_kv(path: Path, allowed: set[str]) -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            key, separator, value = line.partition("=")
            if separator and key in allowed and "\n" not in value and "\r" not in value:
                values[key] = value
    except OSError:
        pass
    return values


def _prefix_info(prefix: Path, state_dir: Path, observed_windows_version: str = "unknown") -> dict[str, str]:
    arch = "unknown"
    try:
        match = re.search(r"(?m)^#arch=(win32|win64)$", (prefix / "system.reg").read_text(encoding="utf-8", errors="replace"))
        if match:
            arch = match.group(1)
    except OSError:
        pass
    configured = _read_kv(state_dir / "mt5-prefix.env", {"prefix", "prefix_arch", "windows_version"})
    configured_prefix = configured.get("prefix", "")
    windows_version = observed_windows_version if observed_windows_version != "unknown" else configured.get("windows_version", "unknown")
    if configured_prefix and Path(configured_prefix).expanduser().resolve() != prefix.resolve():
        windows_version = "unknown"
    return {"architecture": arch, "configured_windows_version": windows_version}


def _installer_info(state_dir: Path) -> dict[str, str]:
    result = _read_kv(state_dir / "mt5-installer-provenance.env", _SAFE_PROVENANCE_KEYS)
    repo_root = Path(__file__).resolve().parents[2]
    pins = _read_kv(
        repo_root / "deploy/codespaces/installer-pins.env",
        {"MT5_LINUX_SCRIPT_URL", "MT5_LINUX_SCRIPT_SHA256", "MT5_SETUP_URL", "MT5_SETUP_SHA256"},
    )
    marker = _read_kv(state_dir / "installer-verified", {"verified_at", "method", "signer"})
    if not result:
        if pins.get("MT5_LINUX_SCRIPT_URL"):
            result.update({
                "wrapper_host": urlsplit(pins["MT5_LINUX_SCRIPT_URL"]).hostname or "unavailable",
                "wrapper_expected_sha256": pins.get("MT5_LINUX_SCRIPT_SHA256", "unavailable"),
                "setup_host": urlsplit(pins.get("MT5_SETUP_URL", "")).hostname or "unavailable",
                "setup_expected_sha256": pins.get("MT5_SETUP_SHA256", "unavailable"),
            })
        if marker:
            result["authenticode_status"] = "verified-marker-present"
            result["authenticode_signer"] = marker.get("signer", "unavailable")
            result["verified_at_utc"] = marker.get("verified_at", "unavailable")
            result["verification_method"] = marker.get("method", "unavailable")
        if result:
            result["status"] = "configured-pins-and-marker; downloaded artifact hashes unavailable"
    for key in ("wrapper_sha256", "wrapper_expected_sha256", "setup_sha256", "setup_expected_sha256"):
        if key in result and not re.fullmatch(r"[0-9a-fA-F]{64}", result[key]):
            result[key] = "invalid-or-unavailable"
    return result or {"status": "unavailable"}


def _candidate_logs(prefix: Path, terminal: Path, runtime_dir: Path) -> list[Path]:
    candidates = [runtime_dir / "terminal.log", runtime_dir / "terminal-control.log", runtime_dir / "installer-gui.log"]
    roots = [terminal.parent / "Logs", terminal.parent / "logs"]
    users = prefix / "drive_c" / "users"
    try:
        roots.extend(users.glob("*/AppData/Roaming/MetaQuotes/Terminal/*/logs"))
    except OSError:
        pass
    for root in roots:
        try:
            candidates.extend(sorted(root.glob("*.log"), key=lambda path: path.stat().st_mtime, reverse=True)[:3])
        except OSError:
            continue
    return list(dict.fromkeys(candidates))


def _read_log(path: Path) -> str:
    try:
        with path.open("rb") as stream:
            stream.seek(0, os.SEEK_END)
            size = stream.tell()
            stream.seek(max(0, size - 96 * 1024))
            data = stream.read()
    except OSError:
        return ""
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        return data.decode("utf-16", errors="replace")
    return data.decode("utf-8", errors="replace")


def _redact_log_line(line: str, home: Path) -> str:
    if _SENSITIVE_LOG.search(line):
        return "[sensitive startup log line omitted]"
    value = line.replace(str(home), "~")
    value = re.sub(r"https?://\S+", "<redacted-url>", value)
    value = _EMAIL.sub("<redacted-email>", value)
    value = _SECRET_VALUE.sub(lambda match: f"{match.group(1)}{match.group(2)}<redacted>", value)
    value = _LONG_HEX.sub("<redacted-hash>", value)
    value = _LONG_TOKEN.sub("<redacted-token>", value)
    value = re.sub(r"(?<![A-Za-z])\d{4,}(?![A-Za-z])", "<redacted-number>", value)
    return " ".join(value.split())[:400]


def _startup_logs(prefix: Path, terminal: Path, runtime_dir: Path, home: Path) -> list[str]:
    lines: list[str] = []
    for path in _candidate_logs(prefix, terminal, runtime_dir):
        for line in _read_log(path).splitlines():
            if "PASS: MT5-compatible Wine detected" in line:
                continue
            if _RELEVANT_LOG.search(line):
                safe = _redact_log_line(line, home)
                if safe:
                    lines.append(safe)
    return lines[-20:]


def _mt5_build(prefix: Path, terminal: Path) -> str:
    builds = []
    for path in _candidate_logs(prefix, terminal, Path("/nonexistent")):
        if path.suffix.lower() != ".log":
            continue
        builds.extend(_BUILD_PATTERN.findall(_read_log(path)))
    return builds[-1] if builds else "unavailable"


def _windows_product_version(wine: str, prefix: Path, run_command=_run) -> str:
    result = run_command(
        [wine, "reg", "query", r"HKLM\Software\Microsoft\Windows NT\CurrentVersion", "/v", "ProductName"],
        env={**os.environ, "WINEPREFIX": str(prefix), "WINEDEBUG": "-all"},
    )
    match = re.search(r"(?m)^\s*ProductName\s+REG_SZ\s+([^\r\n]+)", result)
    if match:
        return match.group(1).strip()[:80]
    return "unknown"


def _process_state(runtime_dir: Path, run_command=_run) -> tuple[str, str]:
    pid_text = (runtime_dir / "terminal.pid").read_text(encoding="ascii", errors="ignore").strip() if (runtime_dir / "terminal.pid").exists() else ""
    if pid_text.isdecimal() and int(pid_text) > 1:
        try:
            os.kill(int(pid_text), 0)
            try:
                process_stat = Path(f"/proc/{int(pid_text)}/stat").read_text(encoding="ascii")
                if process_stat.rsplit(") ", 1)[-1].startswith("Z "):
                    raise ProcessLookupError
            except FileNotFoundError:
                raise ProcessLookupError
            return "RUNNING_NOT_VERIFIED", "pending"
        except PermissionError:
            return "RUNNING_NOT_VERIFIED", "pending"
        except ProcessLookupError:
            pass
    process_names = run_command(["ps", "-eo", "comm="]).splitlines()
    if any(name.strip().lower() == "terminal64.exe" for name in process_names):
        return "RUNNING_UNTRACKED_NOT_VERIFIED", "pending"
    exit_text = (runtime_dir / "terminal.exit-code").read_text(encoding="ascii", errors="ignore").strip() if (runtime_dir / "terminal.exit-code").exists() else ""
    if re.fullmatch(r"-?\d+", exit_text):
        return "EXITED", exit_text
    return "NOT_RUNNING", "unavailable"


def _debugger_process_names(run_command=_run) -> list[str]:
    names = run_command(["ps", "-eo", "comm="]).splitlines()
    expected = {"winedbg", "gdb", "gdbserver", "lldb", "strace"}
    return sorted({name.strip() for name in names if name.strip().lower() in expected})


def collect_report(
    home: Path,
    state_dir: Path,
    runtime_dir: Path,
    prefix: Path,
    explicit_terminal: str = "",
    run_command=_run,
) -> dict:
    wine = shutil.which("wine") or "/usr/bin/wine"
    wine_env = {**os.environ, "WINEPREFIX": str(prefix), "WINEDEBUG": "-all"}
    wine_version = run_command([wine, "--version"], env=wine_env) or "unavailable"
    windows_version = _windows_product_version(wine, prefix, run_command)
    terminal = resolve_terminal_path(home, explicit_terminal)
    terminal_exists = terminal.is_file()
    process_state, exit_code = _process_state(runtime_dir, run_command)
    return {
        "wine_version": wine_version,
        "wine_package": _package_info(run_command),
        "prefix": {
            "path": _display_path(prefix, home),
            **_prefix_info(prefix, state_dir, windows_version),
        },
        "terminal": {
            "path": _display_path(terminal, home),
            "exists": terminal_exists,
            "build": _mt5_build(prefix, terminal),
            "process": process_state,
            "exit_code": exit_code,
            "ready": "NOT_VERIFIED",
        },
        "debugger_process_names": _debugger_process_names(run_command),
        "installer": _installer_info(state_dir),
        "startup_log": _startup_logs(prefix, terminal, runtime_dir, home),
    }


def main() -> int:
    home = Path(os.environ.get("HOME", str(Path.home()))).expanduser()
    state_dir = Path(os.environ.get("DESTINEDFX_CODESPACES_STATE_DIR", str(home / ".local/share/destinedfx-codespaces"))).expanduser()
    runtime_dir = Path(os.environ.get("DESTINEDFX_RUNTIME_DIR", str(state_dir / "runtime"))).expanduser()
    prefix = Path(os.environ.get("WINEPREFIX", str(home / ".mt5"))).expanduser()
    report = collect_report(home, state_dir, runtime_dir, prefix, os.environ.get("MT5_TERMINAL_EXE", ""))
    print("MT5_DIAGNOSTICS " + json.dumps(report, sort_keys=True, separators=(",", ":")))
    for line in report["startup_log"]:
        print("MT5_LOG " + line)
    print(f"MT5_TERMINAL_PROCESS state={report['terminal']['process']} exit_code={report['terminal']['exit_code']}")
    print("MT5_TERMINAL_READY NOT_VERIFIED (a live process is not proof of terminal readiness)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
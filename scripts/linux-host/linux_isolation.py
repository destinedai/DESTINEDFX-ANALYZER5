#!/usr/bin/env python3
"""Linux per-pairing MT5 isolation primitives for the Codespaces worker.

Each broker pairing receives its own non-login Unix identity, private home,
portable terminal tree, Wine prefix, Xauthority cookie and Xvfb display.
The Bridge only accepts a slot after the root host agent independently verifies
both the reservation and the running Wine/MT5 process.
"""
from __future__ import annotations

import hashlib
import json
import os
import pwd
import re
import secrets
import signal
import socket
import stat
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

_SAFE = re.compile(r"^[A-Za-z0-9_-]{1,128}$")
_SLOT_USER = re.compile(r"^dfxslot_[0-9a-f]{16}$")
_DISPLAY = re.compile(r"^:1[0-9]{2}$")
_PROC_ROOT = Path("/proc")


class IsolationError(RuntimeError):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def pairing_key(pairing_id: str) -> str:
    if not isinstance(pairing_id, str) or not _SAFE.fullmatch(pairing_id):
        raise IsolationError("INVALID_PAIRING_ID")
    return hashlib.sha256(pairing_id.encode("utf-8")).hexdigest()[:16]


def username_for_pairing(pairing_id: str) -> str:
    return "dfxslot_" + pairing_key(pairing_id)


def _run(
    argv: list[str],
    *,
    input_text: str | None = None,
    timeout: int = 30,
    check: bool = True,
) -> subprocess.CompletedProcess:
    if not argv or any(not isinstance(x, str) or "\x00" in x for x in argv):
        raise IsolationError("INVALID_SLOT_COMMAND")
    try:
        result = subprocess.run(
            argv,
            input=input_text,
            text=True,
            stdin=subprocess.PIPE if input_text is not None else subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        raise IsolationError("SLOT_COMMAND_FAILED") from None
    if check and result.returncode != 0:
        raise IsolationError("SLOT_COMMAND_FAILED")
    return result


def _sudo(
    *argv: str,
    input_text: str | None = None,
    timeout: int = 30,
    check: bool = True,
) -> subprocess.CompletedProcess:
    return _run(["sudo", "-n", *argv], input_text=input_text, timeout=timeout, check=check)


def _proc_start_ticks(pid: int, proc_root: Path = _PROC_ROOT) -> int | None:
    try:
        raw = (proc_root / str(pid) / "stat").read_text(encoding="ascii")
        tail = raw[raw.rfind(")") + 2:].split()
        return int(tail[19])
    except (OSError, ValueError, IndexError):
        return None


def _proc_snapshot(pid: int, proc_root: Path = _PROC_ROOT) -> dict[str, Any] | None:
    base = proc_root / str(pid)
    try:
        status = (base / "status").read_text(encoding="ascii", errors="replace")
        uid_line = next(line for line in status.splitlines() if line.startswith("Uid:"))
        uid = int(uid_line.split()[1])
        args = [item.decode("utf-8", "replace") for item in (base / "cmdline").read_bytes().split(b"\0") if item]
        env = {}
        for item in (base / "environ").read_bytes().split(b"\0"):
            if b"=" in item:
                key, value = item.split(b"=", 1)
                env[key.decode("ascii", "ignore")] = value.decode("utf-8", "replace")
        ticks = _proc_start_ticks(pid, proc_root)
        if ticks is None:
            return None
        return {"pid": pid, "uid": uid, "args": args, "env": env, "start_ticks": ticks}
    except (OSError, ValueError, StopIteration, PermissionError):
        return None


def find_slot_process(
    *,
    linux_uid: int,
    wine_prefix: str,
    terminal_path: str,
    display: str,
    proc_root: Path = _PROC_ROOT,
) -> dict[str, int] | None:
    """Find the actual MT5 terminal process, not a shared Wine server or PID file."""
    expected_prefix = os.path.realpath(wine_prefix)
    expected_terminal = os.path.realpath(terminal_path).lower()
    for entry in proc_root.glob("[0-9]*"):
        try:
            pid = int(entry.name)
        except ValueError:
            continue
        record = _proc_snapshot(pid, proc_root)
        if not record or record["uid"] != linux_uid:
            continue
        env = record["env"]
        if os.path.realpath(env.get("WINEPREFIX", "")) != expected_prefix:
            continue
        if env.get("DISPLAY") != display:
            continue
        joined = " ".join(record["args"]).replace("\\", "/").lower()
        if "terminal64.exe" not in joined:
            continue
        # Wine sometimes reports a Z: path instead of the Unix path. The UID,
        # exact prefix, exact display, and executable basename remain mandatory.
        if expected_terminal not in joined and not any(
            Path(part).name.lower() == "terminal64.exe" for part in record["args"]
        ):
            continue
        return {"pid": pid, "start_ticks": record["start_ticks"]}
    return None


def verify_slot_reservation(payload: dict[str, Any], slots_root: Path, owner_uid: int) -> bool:
    """Root-agent check of the real Unix account and private slot directory."""
    try:
        pairing_id = str(payload["pairing_id"])
        job_id = str(payload["job_id"])
        username = str(payload["linux_user"])
        slot_root = Path(str(payload["slot_root"])).resolve(strict=True)
        wine_prefix = Path(str(payload["wine_prefix"])).resolve(strict=True)
        terminal_home = Path(str(payload["terminal_home"])).resolve(strict=True)
        xauthority = Path(str(payload["xauthority"])).resolve(strict=True)
        display = str(payload["display"])
        xvfb_pid = int(payload["xvfb_pid"])
        xvfb_ticks = int(payload["xvfb_start_ticks"])
        linux_uid = int(payload["linux_uid"])
        if not _SAFE.fullmatch(pairing_id) or not _SAFE.fullmatch(job_id):
            return False
        if username != username_for_pairing(pairing_id) or not _SLOT_USER.fullmatch(username):
            return False
        if not _DISPLAY.fullmatch(display) or xvfb_pid <= 1 or xvfb_ticks <= 0:
            return False
        account = pwd.getpwnam(username)
        if account.pw_uid != linux_uid or linux_uid in {0, owner_uid}:
            return False
        if Path(account.pw_dir).resolve() != slot_root:
            return False
        if not account.pw_shell.endswith("/nologin"):
            return False
        root = slots_root.resolve(strict=True)
        if slot_root.parent != root or not slot_root.is_dir():
            return False
        if wine_prefix.parent != slot_root / job_id or terminal_home.parent != slot_root / job_id:
            return False
        if not wine_prefix.is_dir() or not terminal_home.is_dir():
            return False
        if xauthority.parent != slot_root / job_id or not xauthority.is_file():
            return False
        for path in (slot_root, wine_prefix, terminal_home, xauthority):
            info = path.stat()
            if info.st_uid != linux_uid:
                return False
            if stat.S_IMODE(info.st_mode) & 0o077:
                return False
        xvfb = _proc_snapshot(xvfb_pid)
        if not xvfb or xvfb["uid"] != linux_uid or xvfb["start_ticks"] != xvfb_ticks:
            return False
        args = " ".join(xvfb["args"])
        if "Xvfb" not in args or display not in xvfb["args"] or "-nolisten tcp" not in args:
            return False
        if xvfb["env"].get("DISPLAY") != display:
            return False
        if os.path.realpath(xvfb["env"].get("XAUTHORITY", "")) != str(xauthority):
            return False
        return True
    except (KeyError, OSError, ValueError, TypeError, KeyError):
        return False


def verify_slot_process(payload: dict[str, Any]) -> bool:
    try:
        found = find_slot_process(
            linux_uid=int(payload["linux_uid"]),
            wine_prefix=str(payload["wine_prefix"]),
            terminal_path=str(payload["terminal_path"]),
            display=str(payload["display"]),
        )
        return bool(
            found
            and found["pid"] == int(payload["pid"])
            and found["start_ticks"] == int(payload["start_ticks"])
        )
    except (KeyError, ValueError, TypeError, IsolationError):
        return False


@dataclass(frozen=True)
class SlotReservation:
    pairing_id: str
    job_id: str
    worker_id: str
    linux_user: str
    linux_uid: int
    slot_root: Path
    job_root: Path
    wine_prefix: Path
    terminal_home: Path
    terminal_path: Path
    xauthority: Path
    display: str
    xvfb_pid: int
    xvfb_start_ticks: int

    def attestation_fields(self) -> dict[str, Any]:
        return {
            "pairing_id": self.pairing_id,
            "job_id": self.job_id,
            "worker_id": self.worker_id,
            "slot_id": f"{self.worker_id}-{self.job_id}",
            "linux_user": self.linux_user,
            "linux_uid": self.linux_uid,
            "slot_root": str(self.slot_root),
            "wine_prefix": str(self.wine_prefix),
            "terminal_home": str(self.terminal_home),
            "terminal_path": str(self.terminal_path),
            "xauthority": str(self.xauthority),
            "display": self.display,
            "xvfb_pid": self.xvfb_pid,
            "xvfb_start_ticks": self.xvfb_start_ticks,
            "process_identity": f"xvfb-pid={self.xvfb_pid};start_ticks={self.xvfb_start_ticks}",
            "ea_identity": "DestinedFXTraderEA",
        }


class LinuxSlotManager:
    """Creates and owns one non-login OS identity and Wine display per pairing."""

    def __init__(
        self,
        slots_root: str | Path,
        runtime_root: str | Path,
        *,
        xvfb: str = "/usr/bin/Xvfb",
        xauth: str = "/usr/bin/xauth",
        display_min: int = 100,
        display_max: int = 199,
    ):
        self.slots_root = Path(slots_root).resolve()
        self.runtime_root = Path(runtime_root).resolve()
        self.xvfb = xvfb
        self.xauth = xauth
        self.display_min = display_min
        self.display_max = display_max
        if not (100 <= display_min <= display_max <= 199):
            raise IsolationError("INVALID_DISPLAY_RANGE")

    def _prepare_roots(self) -> None:
        _sudo("install", "-d", "-o", "root", "-g", "root", "-m", "0711", str(self.slots_root))
        self.runtime_root.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(self.runtime_root, 0o700)
        if not Path(self.xvfb).is_file() or not Path(self.xauth).is_file():
            raise IsolationError("X11_ISOLATION_TOOLS_MISSING")

    def _ensure_account(self, pairing_id: str, slot_root: Path) -> tuple[str, int, int]:
        username = username_for_pairing(pairing_id)
        try:
            account = pwd.getpwnam(username)
        except KeyError:
            _sudo(
                "useradd", "--system", "--create-home",
                "--home-dir", str(slot_root),
                "--shell", "/usr/sbin/nologin",
                username,
            )
            try:
                account = pwd.getpwnam(username)
            except KeyError:
                raise IsolationError("SLOT_USER_CREATE_FAILED") from None
        if (Path(account.pw_dir).resolve() != slot_root or account.pw_uid in {0, os.getuid()}
                or not account.pw_shell.endswith("/nologin")):
            raise IsolationError("SLOT_USER_IDENTITY_MISMATCH")
        _sudo("chmod", "0700", str(slot_root))
        return username, account.pw_uid, account.pw_gid

    def _allocate_display(self, username: str, uid: int) -> tuple[str, Path]:
        for number in range(self.display_min, self.display_max + 1):
            display = f":{number}"
            socket_path = Path(f"/tmp/.X11-unix/X{number}")
            lease = self.runtime_root / f"display-{number}.json"
            if socket_path.exists() or lease.exists():
                continue
            payload = json.dumps({"display": display, "uid": uid, "user": username, "created": time.time()})
            try:
                fd = os.open(lease, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            except FileExistsError:
                continue
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                stream.write(payload)
                stream.flush()
                os.fsync(stream.fileno())
            return display, lease
        raise IsolationError("NO_FREE_X_DISPLAY")

    def prepare(self, pairing_id: str, job_id: str, worker_id: str) -> SlotReservation:
        if not _SAFE.fullmatch(job_id) or not _SAFE.fullmatch(worker_id):
            raise IsolationError("INVALID_SLOT_ID")
        self._prepare_roots()
        username = username_for_pairing(pairing_id)
        slot_root = self.slots_root / pairing_key(pairing_id)
        username, uid, gid = self._ensure_account(pairing_id, slot_root)
        job_root = slot_root / job_id
        wine_prefix = job_root / "wine"
        terminal_home = job_root / "terminal"
        xauthority = job_root / ".Xauthority"
        _sudo("install", "-d", "-o", username, "-g", username, "-m", "0700", str(job_root))
        _sudo("install", "-d", "-o", username, "-g", username, "-m", "0700", str(wine_prefix))
        _sudo("install", "-d", "-o", username, "-g", username, "-m", "0700", str(terminal_home))
        display, lease = self._allocate_display(username, uid)
        cookie = secrets.token_hex(16)
        _sudo(
            "-u", username, "--", self.xauth, "-f", str(xauthority), "source", "-",
            input_text=f"add {display} . {cookie}\n",
        )
        _sudo("chmod", "0600", str(xauthority))
        _sudo("chown", f"{username}:{gid}", str(xauthority))
        log_path = self.runtime_root / f"xvfb-{pairing_key(pairing_id)}-{job_id}.log"
        log_path.touch(mode=0o600, exist_ok=True)
        os.chmod(log_path, 0o600)
        # Xvfb runs under the dedicated login-disabled account and accepts only
        # that account's private Xauthority cookie; TCP listening is disabled.
        with log_path.open("ab") as sink:
            try:
                proc = subprocess.Popen(
                    ["sudo", "-n", "-u", username, "--", "env",
                     f"HOME={slot_root}", f"DISPLAY={display}", f"XAUTHORITY={xauthority}",
                     self.xvfb, display, "-screen", "0", "1280x800x24",
                     "-nolisten", "tcp", "-auth", str(xauthority)],
                    stdin=subprocess.DEVNULL, stdout=sink, stderr=subprocess.STDOUT,
                    start_new_session=True, close_fds=True,
                )
            except OSError:
                lease.unlink(missing_ok=True)
                raise IsolationError("X_SERVER_START_FAILED") from None
        ticks = _proc_start_ticks(proc.pid)
        if ticks is None:
            lease.unlink(missing_ok=True)
            raise IsolationError("X_SERVER_IDENTITY_UNAVAILABLE")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                lease.unlink(missing_ok=True)
                raise IsolationError("X_SERVER_START_FAILED")
            if Path(f"/tmp/.X11-unix/X{display[1:]}").exists():
                break
            time.sleep(0.1)
        else:
            self.stop(pairing_id, job_id, username, uid, display, proc.pid, ticks, strict=False)
            raise IsolationError("X_SERVER_NOT_READY")
        terminal_path = terminal_home / "terminal64.exe"
        return SlotReservation(
            pairing_id=pairing_id, job_id=job_id, worker_id=worker_id,
            linux_user=username, linux_uid=uid, slot_root=slot_root,
            job_root=job_root, wine_prefix=wine_prefix, terminal_home=terminal_home,
            terminal_path=terminal_path, xauthority=xauthority, display=display,
            xvfb_pid=proc.pid, xvfb_start_ticks=ticks,
        )

    def _slot_argv(self, reservation: SlotReservation, argv: list[str]) -> list[str]:
        return [
            "sudo", "-n", "-u", reservation.linux_user, "--", "env",
            f"HOME={reservation.slot_root}",
            f"WINEPREFIX={reservation.wine_prefix}",
            f"DISPLAY={reservation.display}",
            f"XAUTHORITY={reservation.xauthority}",
            "WINEDEBUG=-all",
            "WINEDLLOVERRIDES=",
            *argv,
        ]

    def run_as_slot(
        self,
        reservation: SlotReservation,
        argv: list[str],
        *,
        input_text: str | None = None,
        timeout: int = 300,
    ) -> subprocess.CompletedProcess:
        return _run(
            self._slot_argv(reservation, argv),
            input_text=input_text, timeout=timeout, check=True,
        )

    def popen_as_slot(
        self,
        reservation: SlotReservation,
        argv: list[str],
        *,
        stdout: int | Any = subprocess.DEVNULL,
        stderr: int | Any = subprocess.STDOUT,
    ) -> subprocess.Popen:
        try:
            return subprocess.Popen(
                self._slot_argv(reservation, argv),
                stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr,
                start_new_session=True, close_fds=True,
            )
        except OSError:
            raise IsolationError("SLOT_PROCESS_LAUNCH_FAILED") from None

    def stop(
        self,
        pairing_id: str,
        job_id: str,
        username: str,
        uid: int,
        display: str,
        xvfb_pid: int,
        xvfb_ticks: int,
        *,
        strict: bool = True,
    ) -> None:
        if username != username_for_pairing(pairing_id) or not _SLOT_USER.fullmatch(username):
            raise IsolationError("SLOT_USER_IDENTITY_MISMATCH")
        try:
            account = pwd.getpwnam(username)
        except KeyError:
            self._release_display(display)
            return
        if account.pw_uid != uid or account.pw_uid in {0, os.getuid()}:
            raise IsolationError("SLOT_USER_IDENTITY_MISMATCH")
        job_root = self.slots_root / pairing_key(pairing_id) / job_id
        wine_prefix = job_root / "wine"
        # Ask only this pairing's wineserver to exit first.
        if wine_prefix.exists():
            _sudo(
                "-u", username, "--", "env", f"HOME={account.pw_dir}",
                f"WINEPREFIX={wine_prefix}", "WINEDEBUG=-all", "wineserver", "-k",
                timeout=45,
            )
        # This account is reserved exclusively for this pairing. Terminating its
        # remaining processes cannot affect another user's terminal or display.
        _sudo("pkill", "-TERM", "-u", username, check=False, timeout=15)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            remaining = _run(["ps", "-u", username, "-o", "pid="], check=False).stdout.strip()
            if not remaining:
                break
            time.sleep(0.25)
        if _run(["ps", "-u", username, "-o", "pid="], check=False).stdout.strip():
            _sudo("pkill", "-KILL", "-u", username, check=False, timeout=15)
            time.sleep(0.5)
        if _run(["ps", "-u", username, "-o", "pid="], check=False).stdout.strip():
            if strict:
                raise IsolationError("SLOT_PROCESSES_NOT_TERMINATED")
            return
        # Only remove a home owned by the exact dedicated slot identity after all
        # its processes are confirmed dead.
        removed = _sudo("userdel", "--remove", username, check=False, timeout=60)
        try:
            pwd.getpwnam(username)
            raise IsolationError("SLOT_USER_CLEANUP_FAILED")
        except KeyError:
            pass
        if removed.returncode != 0:
            raise IsolationError("SLOT_USER_CLEANUP_FAILED")
        self._release_display(display)
        log = self.runtime_root / f"xvfb-{pairing_key(pairing_id)}-{job_id}.log"
        log.unlink(missing_ok=True)

    def _release_display(self, display: str) -> None:
        if not _DISPLAY.fullmatch(display):
            return
        lease = self.runtime_root / f"display-{display[1:]}.json"
        try:
            record = json.loads(lease.read_text(encoding="utf-8"))
            if record.get("display") == display:
                lease.unlink(missing_ok=True)
        except (OSError, ValueError):
            pass

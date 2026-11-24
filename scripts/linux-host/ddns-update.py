#!/usr/bin/env python3
"""Secure DuckDNS updater for a two-hostname DestinedFX home host."""
from __future__ import annotations
import argparse
import json
import os
import re
import stat
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

DUCKDNS_SUFFIX = ".duckdns.org"
TOKEN_RE = re.compile(r"^[A-Za-z0-9-]{16,128}$")
LABEL_RE = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
DEFAULT_CONFIG = Path("/etc/destinedfx/ddns.json")
UPDATE_URL = "https://www.duckdns.org/update"


class DDNSError(Exception):
    """Sanitized DDNS configuration or update error."""


def validate_hostnames(hostnames: str | list[str]) -> list[str]:
    values = hostnames.split(",") if isinstance(hostnames, str) else list(hostnames)
    names = [str(item).strip().lower().rstrip(".") for item in values]
    if len(names) != 2 or len(set(names)) != 2:
        raise DDNSError("TWO_DISTINCT_DUCKDNS_HOSTNAMES_REQUIRED")
    for name in names:
        if not name.endswith(DUCKDNS_SUFFIX):
            raise DDNSError("HOSTNAME_MUST_END_IN_DUCKDNS_ORG")
        label = name[:-len(DUCKDNS_SUFFIX)]
        if not LABEL_RE.fullmatch(label):
            raise DDNSError("DUCKDNS_SUBDOMAIN_LABEL_INVALID")
    return names


def parse_update_response(body: str) -> bool:
    return body.strip().upper().startswith("OK")


def _check_config_path(path: Path) -> None:
    if path.is_symlink():
        raise DDNSError("DDNS_CONFIG_MUST_NOT_BE_A_SYMLINK")
    if path.exists():
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            raise DDNSError("DDNS_CONFIG_FILE_TYPE_INVALID")
        if stat.S_IMODE(info.st_mode) != 0o600:
            raise DDNSError("DDNS_CONFIG_FILE_MODE_MUST_BE_0600")
        if info.st_uid != os.geteuid():
            raise DDNSError("DDNS_CONFIG_FILE_OWNER_INVALID")


def _write_config(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.parent.is_symlink():
        raise DDNSError("DDNS_CONFIG_DIRECTORY_MUST_NOT_BE_A_SYMLINK")
    _check_config_path(path)
    fd, temporary = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(payload, stream, sort_keys=True)
            stream.write("\n")
        os.replace(temporary, path)
        os.chmod(path, 0o600)
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def configure(path: Path, hostnames: str, token: str) -> None:
    names = validate_hostnames(hostnames)
    secret = token.strip()
    if not TOKEN_RE.fullmatch(secret):
        raise DDNSError("DUCKDNS_TOKEN_FORMAT_INVALID")
    _write_config(path, {
        "schema_version": 1,
        "provider": "duckdns",
        "hostnames": names,
        "token": secret,
    })


def load_config(path: Path) -> dict[str, Any]:
    _check_config_path(path)
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise DDNSError("DDNS_CONFIG_UNREADABLE_OR_INVALID") from None
    if not isinstance(value, dict) or value.get("schema_version") != 1 or value.get("provider") != "duckdns":
        raise DDNSError("DDNS_CONFIG_SCHEMA_INVALID")
    names = validate_hostnames(value.get("hostnames", []))
    token = value.get("token")
    if not isinstance(token, str) or not TOKEN_RE.fullmatch(token):
        raise DDNSError("DDNS_CONFIG_TOKEN_INVALID")
    return {"provider": "duckdns", "hostnames": names, "token": token}


def update(path: Path) -> None:
    config = load_config(path)
    query = urllib.parse.urlencode({
        "domains": ",".join(name[:-len(DUCKDNS_SUFFIX)] for name in config["hostnames"]),
        "token": config["token"],
        "ip": "",
        "verbose": "true",
    })
    request = urllib.request.Request(
        UPDATE_URL + "?" + query,
        headers={"User-Agent": "DestinedFXHomeHost/1.0"},
        method="GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            if response.status != 200:
                raise DDNSError("DUCKDNS_HTTP_STATUS_NOT_OK")
            body = response.read(4096).decode("utf-8", errors="replace")
    except DDNSError:
        raise
    except (OSError, urllib.error.URLError, TimeoutError):
        raise DDNSError("DUCKDNS_UPDATE_NETWORK_ERROR") from None
    if not parse_update_response(body):
        raise DDNSError("DUCKDNS_UPDATE_REJECTED_CHECK_TOKEN_AND_SUBDOMAINS")
    print("DDNS_UPDATE_OK provider=duckdns hostname_count=2")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--configure", action="store_true")
    parser.add_argument("--hostnames", default="")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--update", action="store_true")
    args = parser.parse_args()
    try:
        if args.configure == args.update:
            raise DDNSError("SELECT_EXACTLY_ONE_OF_CONFIGURE_OR_UPDATE")
        if args.configure:
            # The token is read from stdin, never a command-line argument or log.
            configure(args.config, args.hostnames, sys.stdin.readline())
            print("DDNS_CONFIG_SAVED provider=duckdns hostname_count=2")
        else:
            update(args.config)
    except DDNSError as exc:
        print("DDNS_FAIL: " + str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

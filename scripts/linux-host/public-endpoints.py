#!/usr/bin/env python3
"""Write a non-secret endpoint manifest after the self-hosted APIs pass readiness checks."""
import argparse
import ipaddress
import json
import os
import re
import tempfile
from pathlib import Path
from urllib.parse import urlsplit

DNS = re.compile(r"(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
RESERVED = (".example", ".example.com", ".test", ".invalid", ".localhost")


def public_origin(raw, label):
    try:
        u = urlsplit(raw.strip())
        host = (u.hostname or "").lower().rstrip(".")
        port = u.port
    except (ValueError, UnicodeError):
        raise ValueError(f"{label} is not a valid HTTPS origin.") from None
    if (u.scheme.lower() != "https" or not DNS.fullmatch(host) or u.username or u.password
            or u.query or u.fragment or u.path not in ("", "/") or port not in (None, 443)):
        raise ValueError(f"{label} must be a bare public HTTPS DNS origin.")
    try:
        ipaddress.ip_address(host)
        raise ValueError(f"{label} cannot be an IP literal.")
    except ValueError as exc:
        if str(exc) == f"{label} cannot be an IP literal.":
            raise
    if host in {"example.com", "localhost"} or host.endswith(RESERVED) or "placeholder" in host:
        raise ValueError(f"{label} cannot be a placeholder or reserved test domain.")
    return "https://" + host, host


def make_manifest(source):
    if source.is_symlink() or not source.is_file():
        raise ValueError("Bridge configuration must be a regular non-symlink file.")
    wanted = {"BRIDGE_BASE_URL", "BRIDGE_HOST", "EXPECTED_FORWARDED_HOST", "APP_DOMAIN"}
    values = {}
    for line in source.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, raw = line.split("=", 1)
        key = key.strip()
        if key not in wanted:
            continue
        if key in values:
            raise ValueError(f"Duplicate public endpoint setting: {key}.")
        value = raw.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        if not value or any(c in value for c in "\r\n\x00"):
            raise ValueError(f"Empty or malformed public endpoint setting: {key}.")
        values[key] = value
    missing = sorted(wanted - values.keys())
    if missing:
        raise ValueError("Missing public endpoint settings: " + ", ".join(missing))
    bridge, bridge_host = public_origin(values["BRIDGE_BASE_URL"], "BRIDGE_BASE_URL")
    api, api_host = public_origin("https://" + values["APP_DOMAIN"], "APP_DOMAIN")
    if bridge_host != values["BRIDGE_HOST"].lower().rstrip(".") or bridge_host != values["EXPECTED_FORWARDED_HOST"].lower().rstrip("."):
        raise ValueError("BRIDGE_BASE_URL, BRIDGE_HOST and EXPECTED_FORWARDED_HOST must match.")
    if api_host == bridge_host:
        raise ValueError("APP_DOMAIN must be different from BRIDGE_HOST.")
    return {"schema_version": 1, "bridge_url": bridge, "management_api_origin": api}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("/etc/destinedfx/bridge.env"))
    parser.add_argument("--output", type=Path, default=Path("/etc/destinedfx/public-endpoints.json"))
    args = parser.parse_args()
    try:
        doc = make_manifest(args.source)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        if args.output.is_symlink():
            raise ValueError("Manifest output cannot be a symlink.")
        fd, temp = tempfile.mkstemp(prefix=".public-endpoints.", dir=args.output.parent)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                json.dump(doc, stream, sort_keys=True, separators=(",", ":"))
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            os.chmod(temp, 0o644)
            os.replace(temp, args.output)
        finally:
            if os.path.exists(temp):
                os.unlink(temp)
    except (OSError, UnicodeError, ValueError) as exc:
        parser.exit(2, f"BLOCKED: endpoint manifest not written: {exc}\n")
    print("PUBLIC_ENDPOINT_MANIFEST_WRITTEN")


if __name__ == "__main__":
    main()

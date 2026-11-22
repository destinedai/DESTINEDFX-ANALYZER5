#!/usr/bin/env python3
"""Write a non-secret endpoint manifest after public host readiness is verified."""
import argparse
import ipaddress
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path
from urllib.parse import urlsplit

DNS = re.compile(r"(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
CODESPACE_NAME_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9-]{0,127}\Z")
SUPPORTED_FORWARDING_DOMAINS = {
    "app.github.dev",
    "preview.app.github.dev",
    "githubpreview.dev",
}
RESERVED_HOSTS = {"example.com", "example.net", "example.org", "localhost"}
RESERVED_SUFFIXES = (
    ".example", ".test", ".invalid", ".localhost", ".local", ".internal",
    ".example.com", ".example.net", ".example.org", ".onion",
)


def public_origin(raw, label):
    try:
        u = urlsplit(raw.strip())
        host = (u.hostname or "").lower().rstrip(".")
        port = u.port
    except (ValueError, UnicodeError, AttributeError):
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
    if host in RESERVED_HOSTS or host.endswith(RESERVED_SUFFIXES) or "placeholder" in host:
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


def make_codespaces_manifest():
    """Read the actual GitHub-forwarded port URLs; do not guess or accept pasted hosts."""
    if os.environ.get("CODESPACES", "").lower() != "true":
        raise ValueError("Codespaces endpoint discovery must run inside GitHub Codespaces.")
    codespace = os.environ.get("CODESPACE_NAME", "").strip()
    if not CODESPACE_NAME_RE.fullmatch(codespace):
        raise ValueError("CODESPACE_NAME is missing or malformed.")
    forwarding_domain = os.environ.get(
        "GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN", ""
    ).strip().lower().rstrip(".")
    if forwarding_domain not in SUPPORTED_FORWARDING_DOMAINS:
        raise ValueError("GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN is missing or unsupported.")

    command = [
        "gh", "codespace", "ports",
        "--json", "sourcePort,browseUrl,visibility",
        "-c", codespace,
    ]
    try:
        result = subprocess.run(
            command, capture_output=True, text=True, timeout=20, check=False
        )
    except (OSError, subprocess.TimeoutExpired):
        raise ValueError("gh codespace ports could not be queried.") from None
    if result.returncode != 0:
        raise ValueError("gh codespace ports failed; forwarded endpoints were not trusted.")
    try:
        rows = json.loads(result.stdout)
    except (TypeError, ValueError):
        raise ValueError("gh codespace ports returned invalid JSON.") from None
    if not isinstance(rows, list):
        raise ValueError("gh codespace ports returned an unexpected port inventory.")

    verified = {}
    for row in rows:
        if not isinstance(row, dict):
            continue
        try:
            port = int(row.get("sourcePort"))
        except (TypeError, ValueError):
            continue
        if port not in (8000, 8010):
            continue
        if port in verified:
            raise ValueError(f"GitHub returned duplicate forwarded-port records for port {port}.")
        if str(row.get("visibility", "")).strip().lower() != "public":
            raise ValueError(f"GitHub port {port} is not public after the guarded exposure check.")
        _, browse_host = public_origin(str(row.get("browseUrl", "")), f"port {port} browseUrl")
        expected_prefix = f"{codespace.lower()}-{port}."
        if not browse_host.startswith(expected_prefix):
            raise ValueError(f"GitHub port {port} URL does not match this Codespace and port.")
        # gh currently reports its standard app.github.dev browse alias. The
        # Codespaces environment variable is the authoritative forwarding domain
        # for this host; accept the CLI alias only when its Codespace/port identity
        # and its suffix are both on the supported GitHub forwarding allow-list.
        browse_domain = browse_host[len(expected_prefix):]
        if browse_domain not in SUPPORTED_FORWARDING_DOMAINS:
            raise ValueError(f"GitHub port {port} URL uses an unsupported forwarding domain.")
        origin, _ = public_origin(
            f"https://{codespace.lower()}-{port}.{forwarding_domain}",
            f"port {port} environment-derived origin",
        )
        verified[port] = origin

    missing = {8000, 8010} - verified.keys()
    if missing:
        raise ValueError("GitHub port inventory is missing required forwarded ports: " +
                         ", ".join(str(port) for port in sorted(missing)))
    if verified[8000] == verified[8010]:
        raise ValueError("Bridge and Management API forwarded origins must be different.")
    return {
        "schema_version": 1,
        "bridge_url": verified[8000],
        "management_api_origin": verified[8010],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("/etc/destinedfx/bridge.env"))
    parser.add_argument("--output", type=Path, default=Path("/etc/destinedfx/public-endpoints.json"))
    parser.add_argument(
        "--codespaces", action="store_true",
        help="derive port 8000/8010 origins from the live gh codespace ports inventory",
    )
    args = parser.parse_args()
    try:
        if args.output.is_symlink():
            raise ValueError("Manifest output cannot be a symlink.")
        if not args.codespaces and args.source.resolve() == args.output.resolve():
            raise ValueError("Source bridge configuration and output manifest must be different files.")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        # Invalidate any previous manifest before reading new configuration.
        # A failed regeneration must never leave an old host URL buildable.
        if args.output.exists():
            if not args.output.is_file():
                raise ValueError("Manifest output must be a regular file.")
            args.output.unlink()
        doc = make_codespaces_manifest() if args.codespaces else make_manifest(args.source)
        fd, temp = tempfile.mkstemp(prefix=".public-endpoints.", dir=args.output.parent)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                json.dump(doc, stream, sort_keys=True, separators=(",", ":"))
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            os.chmod(temp, 0o600 if args.codespaces else 0o644)
            os.replace(temp, args.output)
        finally:
            if os.path.exists(temp):
                os.unlink(temp)
    except (OSError, UnicodeError, ValueError) as exc:
        parser.exit(2, f"BLOCKED: endpoint manifest not written: {exc}\n")
    print("PUBLIC_ENDPOINT_MANIFEST_WRITTEN")


if __name__ == "__main__":
    main()

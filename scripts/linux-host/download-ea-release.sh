#!/usr/bin/env bash
set -euo pipefail
umask 077

if [[ $# -ne 3 ]]; then
  echo "Usage: download-ea-release.sh GITHUB_RELEASE_ASSET_URL EXPECTED_SHA256 DESTINATION.ex5" >&2
  exit 2
fi
url="$1"
expected="$2"
dest="$3"
if [[ ! "$url" =~ ^https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/download/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\.ex5$ ]]; then
  echo "BLOCKED: asset URL must be a direct HTTPS GitHub release .ex5 asset URL." >&2
  exit 3
fi
if [[ ! "$expected" =~ ^[0-9a-fA-F]{64}$ ]]; then
  echo "BLOCKED: supply the reviewed 64-character release asset SHA-256." >&2
  exit 3
fi
if [[ ! "$dest" =~ \.ex5$ || -e "$dest" || -L "$dest" ]]; then
  echo "BLOCKED: destination must be a new .ex5 path." >&2
  exit 3
fi
parent="$(dirname -- "$dest")"
mkdir -p "$parent"
chmod 700 "$parent"
tmp="$(mktemp "$parent/.ea-download.XXXXXX")"
trap 'rm -f -- "$tmp"' EXIT
curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --tlsv1.2 "$url" --output "$tmp"
actual="$(sha256sum -- "$tmp" | awk '{print $1}')"
if [[ "${expected,,}" != "${actual,,}" ]]; then
  echo "BLOCKED: downloaded EA SHA-256 mismatch; asset was not installed." >&2
  exit 4
fi
chmod 644 "$tmp"
if ! mv -n -- "$tmp" "$dest"; then
  echo "BLOCKED: EA destination already exists." >&2
  exit 5
fi
if [[ -e "$tmp" ]]; then
  echo "BLOCKED: EA destination already exists." >&2
  exit 5
fi
trap - EXIT
printf 'EA_ASSET_VERIFIED %s\n' "$dest"

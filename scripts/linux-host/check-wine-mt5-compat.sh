#!/usr/bin/env bash
set -euo pipefail

wine_bin="${WINE_BIN:-$(command -v wine || true)}"
[[ -x "$wine_bin" ]] || {
  echo 'BLOCKED: Wine is not installed.' >&2
  exit 1
}
if [[ "${WINE_GUARD_ALREADY_CHECKED:-0}" != "1" ]]; then
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  WINE_BIN="$wine_bin" "$SCRIPT_DIR/wine-guard.sh"
fi
version="$($wine_bin --version 2>/dev/null || true)"
major="$(printf '%s' "$version" | sed -nE 's/[^0-9]*([0-9]+).*/\1/p')"
[[ "$major" =~ ^[0-9]+$ ]] || {
  echo "BLOCKED: could not determine Wine version from '$version'." >&2
  exit 2
}
printf 'INFO: detected Wine version: %s\n' "$version"
printf 'UNVERIFIED: no Wine version is established as compatible with the current auto-updating MT5 terminal build.\n'

#!/usr/bin/env bash
set -euo pipefail

wine_bin="${WINE_BIN:-$(command -v wine || true)}"
[[ -x "$wine_bin" ]] || {
  echo 'BLOCKED: Wine is not installed.' >&2
  exit 1
}
version="$($wine_bin --version 2>/dev/null || true)"
major="$(printf '%s' "$version" | sed -nE 's/[^0-9]*([0-9]+).*/\1/p')"
[[ "$major" =~ ^[0-9]+$ ]] || {
  echo "BLOCKED: could not determine Wine version from '$version'." >&2
  exit 2
}
if (( major >= 11 )); then
  echo "BLOCKED: $version is incompatible with current MetaTrader anti-debug checks." >&2
  echo 'OWNER STEP: rebuild the Codespace with Ubuntu Wine 9 or Wine stable 10, then rerun postCreate.sh.' >&2
  exit 3
fi
if (( major < 9 )); then
  echo "BLOCKED: $version is too old for the supported MT5 host path." >&2
  exit 4
fi
printf 'PASS: MT5-compatible Wine detected: %s\n' "$version"

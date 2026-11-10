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
printf 'INFO: detected Wine version: %s\n' "$version"
case "$major" in
  9|10)
    printf 'PASS: MT5-compatible Wine detected: %s\n' "$version"
    ;;
  *)
    echo "BLOCKED: $version is unsupported; MT5 requires Wine 9 or Wine 10." >&2
    echo 'OWNER STEP: rebuild the Codespace using the compatible Wine image.' >&2
    exit 3
    ;;
esac

#!/usr/bin/env bash
set -euo pipefail
SWAPFILE=${SWAPFILE:-/swapfile}
SIZE=${SIZE:-2G}
if swapon --show=NAME --noheadings | grep -Fxq "$SWAPFILE"; then exit 0; fi
if [[ ! -e "$SWAPFILE" ]]; then
  fallocate -l "$SIZE" "$SWAPFILE"
  chmod 600 "$SWAPFILE"
  mkswap "$SWAPFILE" >/dev/null
fi
swapon "$SWAPFILE"
grep -qF "$SWAPFILE none swap sw 0 0" /etc/fstab || echo "$SWAPFILE none swap sw 0 0" >> /etc/fstab

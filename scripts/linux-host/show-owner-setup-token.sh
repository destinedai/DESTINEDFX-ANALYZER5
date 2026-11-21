#!/usr/bin/env bash
set -euo pipefail
umask 077

CREDENTIAL=/etc/credstore.encrypted/destinedfx-owner-setup-token.cred
TMP=""

block() { printf 'BLOCKED: %s\n' "$*" >&2; exit 1; }
[[ "${EUID}" -eq 0 ]] || block "run this command with sudo on the self-hosted host."
[[ -t 1 && -r /dev/tty ]] || block "an interactive local terminal is required; refusing to print the token to a pipe or log."
[[ -f "$CREDENTIAL" && ! -L "$CREDENTIAL" ]] || block "encrypted owner setup credential is missing."
[[ "$(stat -c '%u' "$CREDENTIAL")" == "0" && "$(stat -c '%a' "$CREDENTIAL")" == "600" ]] || block "owner setup credential permissions are invalid."
command -v systemd-creds >/dev/null || block "systemd-creds is required."

TMP="$(mktemp /run/destinedfx-owner-setup.XXXXXX)"
chmod 0600 "$TMP"
cleanup() { rm -f -- "$TMP"; }
trap cleanup EXIT INT TERM
systemd-creds decrypt --name=owner-setup-token "$CREDENTIAL" "$TMP"
TOKEN="$(<"$TMP")"
[[ "${#TOKEN}" -ge 40 && "${#TOKEN}" -le 512 ]] || block "decrypted owner setup credential has an invalid size."
printf '\nOWNER SETUP TOKEN — keep private and enter it only in the DestinedFX app:\n' > /dev/tty
printf '%s\n' "$TOKEN" > /dev/tty
unset TOKEN
printf '\n' > /dev/tty

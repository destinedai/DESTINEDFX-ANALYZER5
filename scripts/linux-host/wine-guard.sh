#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WINE_BIN="${WINE_BIN:-wine}"
PROJECT_HOME="$HOME"
if [[ -n "${SUDO_USER:-}" ]]; then
  sudo_home="$(awk -F: -v user="$SUDO_USER" '$1 == user { print $6; exit }' /etc/passwd)"
  [[ -n "$sudo_home" ]] && PROJECT_HOME="$sudo_home"
fi
WINEPREFIX_VALUE="${WINEPREFIX:-$PROJECT_HOME/.mt5}"

usage() {
  printf 'Usage: %s [--report|--fix]\n' "${0##*/}"
}

redact_path() {
  local value="$1"
  if [[ -n "${HOME:-}" && "$value" == "$HOME"* ]]; then
    printf '~%s' "${value#"$HOME"}"
  elif [[ "$value" == /usr/* || "$value" == /bin/* || "$value" == /sbin/* || "$value" == /opt/* || "$value" == /etc/* ]]; then
    printf '%s' "$value"
  elif [[ "$value" == /* ]]; then
    printf '[external path redacted]'
  else
    printf '%s' "$value"
  fi
}

wine_version() {
  "$WINE_BIN" --version 2>/dev/null
}

version_allowed() {
  local value="$1" major minor
  if [[ "$value" =~ ^wine-?([0-9]+)\.([0-9]+)([[:space:]]|$) ]]; then
    major="${BASH_REMATCH[1]}"
    minor="${BASH_REMATCH[2]}"
  else
    return 1
  fi
  [[ "$major" == 9 ]] && return 0
  [[ "$major" == 10 && "$minor" =~ ^[012]$ ]]
}

print_report() {
  local version path prefix_file prefix_version
  if version="$(wine_version)"; then
    version="${version%%$'\n'*}"
    if [[ ! "$version" =~ ^wine-?[0-9]+\.[0-9]+([[:space:]]|$) ]]; then
      version='unparsable (output redacted)'
    else
      version="${version%%[[:space:]]*}"
    fi
    printf 'Wine version: %s\n' "$version"
  else
    version=unknown
    printf 'Wine version: unavailable\n'
  fi
  printf 'Wine command: %s\n' "$(redact_path "$(command -v "$WINE_BIN" 2>/dev/null || printf 'not found')")"
  printf 'wine/wine64 on PATH:\n'
  if command -v which >/dev/null 2>&1; then
    { which -a wine wine64 2>/dev/null || true; } | while IFS= read -r path; do
      printf '  %s\n' "$(redact_path "$path")"
    done
  else
    printf '  which is unavailable\n'
  fi
  printf 'Installed Wine packages:\n'
  if command -v dpkg-query >/dev/null 2>&1; then
    dpkg-query -W -f='${db:Status-Abbrev} ${binary:Package} ${Version}\n' 'wine*' 'libwine*' 2>/dev/null |
      sed '/^un /d' || true
  else
    printf '  dpkg-query is unavailable\n'
  fi
  printf 'apt-cache policy wine64:\n'
  if command -v apt-cache >/dev/null 2>&1; then
    apt-cache policy wine64 2>/dev/null |
      sed -E 's#(https?://)[^/@[:space:]]+:[^/@[:space:]]+@#\1[credentials-redacted]@#g; s#[?&][^[:space:]]*#[query-redacted]#g' || true
  else
    printf '  apt-cache is unavailable\n'
  fi
  printf 'WINEPREFIX: %s\n' "$(redact_path "$WINEPREFIX_VALUE")"
  case "${WINEARCH:-unset}" in
    unset|win32|win64) printf 'WINEARCH: %s\n' "${WINEARCH:-unset}" ;;
    *) printf 'WINEARCH: other (redacted)\n' ;;
  esac
  printf 'PATH order:\n'
  local index=0
  while IFS= read -r path; do
    printf '  %d: %s\n' "$index" "$(redact_path "$path")"
    ((index += 1))
  done < <(printf '%s' "$PATH" | tr ':' '\n')
  printf 'WineHQ apt sources:\n'
  local found=0
  for path in /etc/apt/sources.list.d/winehq*; do
    [[ -e "$path" ]] || continue
    printf '  %s\n' "${path##*/}"
    found=1
  done
  (( found )) || printf '  none found\n'
  printf 'Prefix creation Wine version: '
  prefix_file=''
  for path in "$WINEPREFIX_VALUE/system.reg" \
      "$WINEPREFIX_VALUE/drive_c/windows/wine.inf"; do
    if [[ -r "$path" ]]; then
      prefix_file="$path"
      break
    fi
  done
  if [[ -n "$prefix_file" ]]; then
    prefix_version="$(grep -Eio 'wine[- ]?[0-9]+(\.[0-9]+)+' "$prefix_file" 2>/dev/null | head -n 1 || true)"
    if [[ -n "$prefix_version" ]]; then
      printf '%s\n' "$prefix_version"
    else
      printf 'unavailable (metadata found)\n'
    fi
  else
    printf 'unavailable (prefix metadata not found)\n'
  fi
  if version_allowed "$version"; then
    printf 'Wine version allowed: PASS\n'
    return 0
  fi
  printf 'Wine version allowed: FAIL\n'
  return 1
}

print_fix_command() {
  printf 'Fix: sudo bash %s --fix\n' "${SCRIPT_DIR#"$PROJECT_ROOT"/}/wine-guard.sh" >&2
}

fix_wine() {
  local prefix="$WINEPREFIX_VALUE" candidate
  local -a packages=() files=()

  if [[ "$prefix" != "$PROJECT_HOME/.mt5" || "$prefix" == *'/../'* || -L "$prefix" ]]; then
    printf 'FAIL: refusing to remove a Wine prefix other than the project prefix %s.\n' \
      "$(redact_path "$PROJECT_HOME/.mt5")" >&2
    return 1
  fi
  if (( EUID != 0 )); then
    printf 'FAIL: --fix requires sudo.\n' >&2
    printf 'Run: sudo bash scripts/linux-host/wine-guard.sh --fix\n' >&2
    return 1
  fi
  if version="$(wine_version 2>/dev/null)" && version_allowed "$version"; then
    printf 'Wine version already allowed; no changes made.\n'
    print_report
    return $?
  fi

  mapfile -t packages < <(
    dpkg-query -W -f='${binary:Package}\n' 2>/dev/null |
      grep -E '^wine(hq-(stable|staging|devel)|-(stable|staging|devel))(-[^:[:space:]]+)?(:[^[:space:]]+)?$' || true
  )
  if ((${#packages[@]})); then
    apt-get purge -y "${packages[@]}"
  fi

  shopt -s nullglob
  files=(/etc/apt/sources.list.d/winehq*.sources /etc/apt/sources.list.d/winehq*.list
    /etc/apt/keyrings/winehq* /usr/share/keyrings/winehq*)
  shopt -u nullglob
  if ((${#files[@]})); then
    rm -f -- "${files[@]}"
  fi

  apt-get update
  apt-get install -y wine wine64 winetricks
  if ! version="$(wine_version 2>/dev/null)" || ! version_allowed "$version"; then
    candidate="$(apt-cache policy wine64 2>/dev/null | sed -n 's/^[[:space:]]*Candidate: //p' | head -n 1)"
    printf 'FAIL: Ubuntu Wine is not in the allowed range (found %s; wine64 candidate %s).\n' \
      "${version:-unknown}" "${candidate:-unknown}" >&2
    printf 'Next option (only if apt-cache confirms these exact Noble versions): sudo apt install winehq-stable=10.0.0.0~noble-1 wine-stable=10.0.0.0~noble-1 wine-stable-amd64=10.0.0.0~noble-1 wine-stable-i386=10.0.0.0~noble-1\n' >&2
    printf 'Do not install an unpinned WineHQ package or continue if any pinned version is unavailable.\n' >&2
    return 1
  fi
  apt-mark hold wine wine64 libwine

  if [[ -e "$prefix" ]]; then
    rm -rf -- "$prefix"
  fi
  install -d -m 700 "$prefix"
  WINEPREFIX="$prefix" "$WINE_BIN" wineboot -u
  if ! print_report; then
    printf 'FAIL: Wine remains outside the allowed version range after repair.\n' >&2
    return 1
  fi
}

action="${1:---report}"
case "$action" in
  --report)
    if ! print_report; then
      print_fix_command
      exit 1
    fi
    ;;
  --fix)
    fix_wine
    ;;
  -h|--help)
    usage
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

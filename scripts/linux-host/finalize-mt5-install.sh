#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="${DESTINEDFX_CODESPACES_STATE_DIR:-$HOME/.local/share/destinedfx-codespaces}"
PREFIX="${DESTINEDFX_MT5_GUI_WINEPREFIX:-${WINEPREFIX:-$HOME/.mt5}}"
TERMINAL="$PREFIX/drive_c/Program Files/MetaTrader 5/terminal64.exe"
EDITOR=""
for candidate in "$PREFIX/drive_c/Program Files/MetaTrader 5/metaeditor64.exe" "$PREFIX/drive_c/Program Files/MetaTrader 5/MetaEditor64.exe" "$PREFIX/drive_c/Program Files/MetaTrader 5/MetaEditor.exe"; do
  [[ -f "$candidate" ]] && { EDITOR="$candidate"; break; }
done
PROVENANCE="$STATE/mt5-installer-provenance.env"
COMPLETION="$STATE/installer-completed.json"
COMPLETION_HELPER="$ROOT/scripts/linux-host/installer_completion.py"
MARKER="$STATE/installer-verified"
SOURCE="$ROOT/mt5-ea/DestinedFXTraderEA.mq5"
fail(){ printf 'FAIL: %s\n' "$1" >&2; exit 1; }
[[ -f "$TERMINAL" ]] || fail 'terminal64.exe is missing; installer wizard may be incomplete.'
[[ -n "$EDITOR" ]] || fail 'MetaEditor is missing; installer wizard may be incomplete.'
[[ -s "$PROVENANCE" ]] || fail 'verified installer provenance is missing; do not create an acceptance marker.'
[[ -s "$COMPLETION" ]] || fail 'successful MetaQuotes setup completion record is missing; pre-existing terminal files are not accepted.'
[[ -f "$COMPLETION_HELPER" ]] || fail 'installer completion verifier is missing.'
python3 "$COMPLETION_HELPER" verify \
  --manifest "$COMPLETION" \
  --provenance "$PROVENANCE" \
  --terminal "$TERMINAL" \
  --metaeditor "$EDITOR" || fail 'current terminal/MetaEditor binaries do not match the successful pinned-installer completion record.'
[[ -f "$SOURCE" ]] || fail 'DestinedFX EA source is missing.'
command -v wine >/dev/null || fail 'Wine is unavailable.'
command -v winepath >/dev/null || fail 'winepath is unavailable.'
command -v xvfb-run >/dev/null || fail 'xvfb-run is unavailable for isolated MetaEditor compilation.'
tmp="$(mktemp -d "$STATE/installer-finalize.XXXXXX")"
chmod 700 "$tmp"
trap 'rm -rf "$tmp"' EXIT
cp "$SOURCE" "$tmp/DestinedFXTraderEA.mq5"
source_win="$(WINEPREFIX="$PREFIX" winepath -w "$tmp/DestinedFXTraderEA.mq5")"
log_win="$(WINEPREFIX="$PREFIX" winepath -w "$tmp/metaeditor.log")"
clean_env=(env -u WINEDEBUG -u WINEDLLOVERRIDES -u LD_PRELOAD -u LD_AUDIT -u PROTON_DEBUG -u WINEESYNC -u WINEFSYNC WINEDEBUG=-all WINEDLLOVERRIDES='' WINEPREFIX="$PREFIX")
set +e
if [[ -n "${DISPLAY:-}" ]]; then
  "${clean_env[@]}" wine "$EDITOR" "/compile:$source_win" "/log:$log_win"
  compile_rc=$?
else
  xvfb-run --auto-servernum --server-args='-screen 0 1280x800x24 -nolisten tcp' "${clean_env[@]}" wine "$EDITOR" "/compile:$source_win" "/log:$log_win"
  compile_rc=$?
fi
set -e
[[ "$compile_rc" == 0 ]] || fail "MetaEditor returned nonzero exit $compile_rc."
[[ -s "$tmp/DestinedFXTraderEA.ex5" ]] || fail "MetaEditor did not produce the EA binary (exit $compile_rc)."
[[ -f "$tmp/metaeditor.log" ]] || fail 'MetaEditor compile log was not produced.'
if ! grep -Eiq '0 errors|0 error(s)?' "$tmp/metaeditor.log"; then
  printf 'FAIL: EA compilation was not verified; redacted compiler summary follows.\n' >&2
  grep -E 'error|warning|Result|result' "$tmp/metaeditor.log" | tail -n 20 >&2 || true
  exit 1
fi

# The standalone Analyzer publisher is separate from the Bot EA and contains no trade code.
ANALYZER_SOURCE="$ROOT/mt5-ea/AnalyzerMarketFeedEA.mq5"
[[ -f "$ANALYZER_SOURCE" ]] || fail 'Independent Analyzer market-feed EA source is missing.'
cp "$ANALYZER_SOURCE" "$tmp/AnalyzerMarketFeedEA.mq5"
analyzer_source_win="$(WINEPREFIX="$PREFIX" winepath -w "$tmp/AnalyzerMarketFeedEA.mq5")"
analyzer_log_win="$(WINEPREFIX="$PREFIX" winepath -w "$tmp/AnalyzerMarketFeedEA.log")"
set +e
if [[ -n "${DISPLAY:-}" ]]; then
  "${clean_env[@]}" wine "$EDITOR" "/compile:$analyzer_source_win" "/log:$analyzer_log_win"
  analyzer_compile_rc=$?
else
  xvfb-run --auto-servernum --server-args='-screen 0 1280x800x24 -nolisten tcp' "${clean_env[@]}" wine "$EDITOR" "/compile:$analyzer_source_win" "/log:$analyzer_log_win"
  analyzer_compile_rc=$?
fi
set -e
[[ "$analyzer_compile_rc" == 0 ]] || fail "MetaEditor Analyzer EA compile returned nonzero exit $analyzer_compile_rc."
[[ -s "$tmp/AnalyzerMarketFeedEA.ex5" ]] || fail 'MetaEditor did not create AnalyzerMarketFeedEA.ex5.'
[[ -f "$tmp/AnalyzerMarketFeedEA.log" ]] || fail 'Analyzer EA compiler log was not produced.'
if ! grep -Eiq '0 errors|0 error(s)?' "$tmp/AnalyzerMarketFeedEA.log"; then
  printf 'FAIL: Analyzer feed EA compilation was not verified; compiler summary follows.\n' >&2
  grep -E 'error|warning|Result|result' "$tmp/AnalyzerMarketFeedEA.log" | tail -n 20 >&2 || true
  exit 1
fi
EXPERTS="$PREFIX/drive_c/Program Files/MetaTrader 5/MQL5/Experts"
mkdir -p "$EXPERTS"
install -m 600 "$ANALYZER_SOURCE" "$EXPERTS/AnalyzerMarketFeedEA.mq5"
install -m 600 "$tmp/AnalyzerMarketFeedEA.ex5" "$EXPERTS/AnalyzerMarketFeedEA.ex5"

umask 077
{
  printf 'verified_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'method=%s\n' "${1:-owner-finalize}"
  printf 'authenticode_status=verified\nsigner=MetaQuotes Ltd\n'
  printf 'terminal=verified\nmetaeditor=verified\nea_compile=verified\nanalyzer_feed_ea_compile=verified\nanalyzer_feed_ea_installed=verified\n'
} > "$MARKER"
chmod 600 "$MARKER"
printf 'PASS: installer acceptance marker created after terminal, MetaEditor and EA compile verification.\n'

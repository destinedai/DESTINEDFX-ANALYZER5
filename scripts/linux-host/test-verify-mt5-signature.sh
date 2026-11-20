#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
cat > "$TMP/bin/osslsigncode" <<'MOCK'
#!/usr/bin/env bash
case "${3##*/}" in
  wrong-signer.exe) printf '%s\n' 'Signature verification: ok' 'Subject: /O=Example Corp' ;;
  unsigned.exe|truncated.exe) printf '%s\n' 'No signature found' >&2; exit 1 ;;
  valid.exe) printf '%s\n' 'Signature verification: ok' 'Subject: /O=MetaQuotes Ltd' ;;
  *) exit 1 ;;
esac
MOCK
chmod +x "$TMP/bin/osslsigncode"
for name in wrong-signer.exe unsigned.exe truncated.exe valid.exe; do
  printf 'fixture-%s' "$name" > "$TMP/$name"
done
PATH="$TMP/bin:$PATH"
if "$ROOT/verify-mt5-signature.sh" "$TMP/wrong-signer.exe" >/dev/null 2>&1; then exit 1; fi
if "$ROOT/verify-mt5-signature.sh" "$TMP/unsigned.exe" >/dev/null 2>&1; then exit 1; fi
if "$ROOT/verify-mt5-signature.sh" "$TMP/truncated.exe" >/dev/null 2>&1; then exit 1; fi
"$ROOT/verify-mt5-signature.sh" "$TMP/valid.exe" >/dev/null
echo 'PASS: wrong signer, unsigned, and truncated setup fixtures are refused'

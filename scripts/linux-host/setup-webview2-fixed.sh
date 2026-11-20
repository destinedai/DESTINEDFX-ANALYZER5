#!/usr/bin/env bash
set -euo pipefail

STATE="${DESTINEDFX_CODESPACES_STATE_DIR:-$HOME/.local/share/destinedfx-codespaces}"
VERSION="156.0.4314.58"
FOLDER="Microsoft.WebView2.FixedVersionRuntime.${VERSION}.x64"
URL="https://msedge.sf.dl.delivery.mp.microsoft.com/filestreamingservice/files/b7919cc9-680d-4253-bf32-732988e68aa6/Microsoft.WebView2.FixedVersionRuntime.156.0.4314.58.x64.cab"
EXPECTED_SHA256="857dcfd6f7035508a29645fa2978c3a6a841f220df77cc366d318894e19f4792"
ROOT_URL="https://download.microsoft.com/download/2/4/8/248D8A62-FCCD-475C-85E7-6ED59520FC0F/MicrosoftRootCertificateAuthority2011.cer"
EXPECTED_ROOT_SHA1="8F43288AD272F3103B6FB1428485EA3014C0BCFE"

CACHE="$STATE/cache"
CERTS="$STATE/certs"
RUNTIME_ROOT="$STATE/webview2-fixed"
RUNTIME="$RUNTIME_ROOT/$FOLDER"
CAB="$CACHE/$FOLDER.cab"
ROOT_CERT="$CERTS/MicrosoftRootCertificateAuthority2011.cer"
ROOT_PEM="$CERTS/MicrosoftRootCertificateAuthority2011.pem"
CA_BUNDLE="$CERTS/ca-bundle-with-ms-root.pem"

fail() { echo "FAIL: $*" >&2; exit 1; }

for tool in curl sha256sum openssl osslsigncode cabextract; do
    command -v "$tool" >/dev/null 2>&1 || fail "$tool is missing."
done

mkdir -p "$CACHE" "$CERTS" "$RUNTIME_ROOT"
chmod 700 "$STATE" "$CACHE" "$CERTS" "$RUNTIME_ROOT"

if [[ ! -f "$CAB" ]] || \
   ! printf '%s  %s\n' "$EXPECTED_SHA256" "$CAB" | sha256sum -c - >/dev/null 2>&1; then
    echo "INFO: downloading pinned Microsoft WebView2 Fixed Version."
    curl -fL --retry 3 --proto '=https' --tlsv1.2 "$URL" -o "$CAB"
fi

printf '%s  %s\n' "$EXPECTED_SHA256" "$CAB" | sha256sum -c -

if [[ ! -f "$ROOT_CERT" ]]; then
    curl -fL --retry 3 --proto '=https' --tlsv1.2 "$ROOT_URL" -o "$ROOT_CERT"
fi

ROOT_SHA1="$(openssl x509 -inform DER -in "$ROOT_CERT" \
    -noout -fingerprint -sha1 | sed -E 's/^[^=]+=//;s/://g' | tr '[:lower:]' '[:upper:]')"

[[ "$ROOT_SHA1" == "$EXPECTED_ROOT_SHA1" ]] || \
    fail "Microsoft root certificate fingerprint mismatch."

openssl x509 -inform DER -in "$ROOT_CERT" -out "$ROOT_PEM"
cat /etc/ssl/certs/ca-certificates.crt "$ROOT_PEM" > "$CA_BUNDLE"

if [[ ! -f "$RUNTIME/msedgewebview2.exe" ]]; then
    [[ ! -e "$RUNTIME" ]] || fail "Incomplete runtime directory already exists: $RUNTIME"

    TEMP_DIR="$(mktemp -d "$RUNTIME_ROOT/extract.XXXXXX")"
    trap 'rm -rf "$TEMP_DIR"' EXIT

    cabextract -q -d "$TEMP_DIR" "$CAB"

    [[ -s "$TEMP_DIR/$FOLDER/msedgewebview2.exe" ]] || \
        fail "Expected Microsoft WebView2 executable was not extracted."

    mv "$TEMP_DIR/$FOLDER" "$RUNTIME"
fi

SIGNATURE_OUTPUT="$(osslsigncode verify -index 0 \
    -CAfile "$CA_BUNDLE" -in "$RUNTIME/msedgewebview2.exe" 2>&1)" || {
    printf '%s\n' "$SIGNATURE_OUTPUT" >&2
    fail "Microsoft WebView2 Authenticode verification failed."
}

grep -Fq 'O=Microsoft Corporation/CN=Microsoft Corporation' \
    <<< "$SIGNATURE_OUTPUT" || fail "Unexpected WebView2 signer."

grep -Fq 'Signature verification: ok' \
    <<< "$SIGNATURE_OUTPUT" || fail "WebView2 signature did not verify."

echo "PASS: WebView2 $VERSION extracted and Microsoft signature verified."
echo "WEBVIEW2_FIXED_RUNTIME_DIR=$RUNTIME"

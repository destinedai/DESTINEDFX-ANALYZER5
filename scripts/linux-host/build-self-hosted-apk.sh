#!/usr/bin/env bash
set -euo pipefail
umask 077
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
MANIFEST="${DESTINEDFX_ENDPOINTS_FILE:-/etc/destinedfx/public-endpoints.json}"
OUTPUT="${DESTINEDFX_APK_OUTPUT_DIR:-$HOME/destinedfx-delivery}"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
block(){ printf 'BLOCKED: %s\n' "$*" >&2; exit 2; }
[[ "$EUID" -ne 0 ]] || block "Run the APK build as a non-root Android build user."
[[ -f "$ROOT/gradlew" && -f "$ROOT/app/build.gradle.kts" ]] || block "Run from a checked-out DestinedFX repository."
[[ -f "$MANIFEST" && ! -L "$MANIFEST" && -r "$MANIFEST" ]] || block "The verified host endpoint manifest is missing."
command -v python3 >/dev/null || block "Python 3 is required."
command -v java >/dev/null || block "JDK 21 is required."
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$SDK" && -d "$SDK/build-tools" ]] || block "Set ANDROID_SDK_ROOT or ANDROID_HOME to an installed Android SDK."
TOOLS="$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -V | tail -1)"
APKSIGNER="$SDK/build-tools/$TOOLS/apksigner"
AAPT="$SDK/build-tools/$TOOLS/aapt"
[[ -x "$APKSIGNER" && -x "$AAPT" ]] || block "Android build-tools must include apksigner and aapt."

readarray -t ORIGINS < <(python3 - "$MANIFEST" <<'PY'
import ipaddress, json, socket, stat, sys, urllib.request
from pathlib import Path
from urllib.parse import urlsplit
p = Path(sys.argv[1])
if p.is_symlink(): raise SystemExit("Blocked: endpoint manifest symlink.")
st = p.stat()
if not stat.S_ISREG(st.st_mode) or st.st_size > 8192 or st.st_mode & 0o022:
    raise SystemExit("Blocked: endpoint manifest permissions or size invalid.")
d = json.loads(p.read_text(encoding="utf-8"))
if d.get("schema_version") != 1: raise SystemExit("Blocked: unsupported endpoint manifest version.")
def origin(v, name):
    try:
        u = urlsplit(v); h = (u.hostname or "").lower().rstrip("."); port = u.port
    except (ValueError, UnicodeError):
        raise SystemExit(f"Blocked: invalid {name}.")
    if u.scheme != "https" or not h or u.username or u.password or u.query or u.fragment or u.path not in ("", "/") or port not in (None, 443):
        raise SystemExit(f"Blocked: {name} must be a bare HTTPS origin.")
    try: ipaddress.ip_address(h)
    except ValueError: pass
    else: raise SystemExit(f"Blocked: {name} must use a DNS hostname.")
    if h in {"localhost", "example.com"} or h.endswith((".example", ".example.com", ".test", ".invalid", ".localhost")) or "placeholder" in h:
        raise SystemExit(f"Blocked: {name} is a placeholder.")
    try: addresses = {x[4][0].split("%",1)[0] for x in socket.getaddrinfo(h,443,type=socket.SOCK_STREAM)}
    except OSError: raise SystemExit(f"Blocked: {name} does not resolve.")
    if not addresses or any(not ipaddress.ip_address(a).is_global for a in addresses):
        raise SystemExit(f"Blocked: {name} resolves to a non-public IP.")
    return "https://" + h, h
b,bh = origin(d.get("bridge_url",""), "Bridge URL")
m,mh = origin(d.get("management_api_origin",""), "Management API origin")
if bh == mh: raise SystemExit("Blocked: Bridge and management API must use separate hostnames.")
def get(url, label):
    req = urllib.request.Request(url, headers={"Accept":"application/json"})
    try:
        with urllib.request.urlopen(req, timeout=12) as r:
            body = r.read(65537)
            if r.status != 200: raise SystemExit(f"Blocked: {label} returned non-200.")
    except SystemExit: raise
    except Exception as e: raise SystemExit(f"Blocked: {label} HTTPS probe failed ({type(e).__name__}).")
    if len(body) > 65536: raise SystemExit(f"Blocked: {label} response too large.")
    try: return json.loads(body)
    except Exception: raise SystemExit(f"Blocked: {label} returned invalid JSON.")
br = get(b + "/health/ready", "Bridge")
if br.get("status") != "ready" or br.get("database") != "ok" or br.get("managed_execution") != "configured":
    raise SystemExit("Blocked: Bridge durable readiness is not ready.")
mr = get(m + "/ready", "Management API")
if mr.get("status") != "ready": raise SystemExit("Blocked: management API/PostgreSQL is not ready.")
key = get(m + "/api/v1/auth/bridge-verification-key", "EdDSA key")
if key.get("algorithm") != "EdDSA" or key.get("issuer") != "destinedfx-management" or key.get("audience") != "destinedfx-bridge" or not str(key.get("public_key_pem","")).startswith("-----BEGIN PUBLIC KEY-----"):
    raise SystemExit("Blocked: management API EdDSA Bridge key contract is invalid.")
print(b); print(m)
print(f"Verified self-hosted endpoints: Bridge={bh}; Management API={mh}.", file=sys.stderr)
PY
) || block "Host endpoint validation failed."
[[ "${#ORIGINS[@]}" -eq 2 ]] || block "Endpoint manifest did not provide exactly two HTTPS origins."
BRIDGE_URL="${ORIGINS[0]}"; MANAGEMENT_URL="${ORIGINS[1]}"
cd "$ROOT"
chmod +x ./gradlew
export MT5_MANAGED_SERVICE_URL="$BRIDGE_URL" MANAGEMENT_API_ORIGIN="$MANAGEMENT_URL"
./gradlew testDebugUnitTest assembleDebug -Pmt5ManagedServiceUrl="$BRIDGE_URL" -PmanagementApiOrigin="$MANAGEMENT_URL" -PdestinedfxEndpointsLocked=true --stacktrace
[[ -s "$APK" ]] || block "APK output is missing."
signature="$("$APKSIGNER" verify --verbose --print-certs "$APK" 2>&1)" || block "APK signature verification failed."
grep -Eq 'certificate SHA-256 digest:[[:space:]]*[0-9a-fA-F]{64}' <<<"$signature" || block "APK signing fingerprint is missing."
metadata="$("$AAPT" dump badging "$APK")"
grep -Fq "package: name='com.aistudio.destinedfx.qwvzxp'" <<<"$metadata" || block "Unexpected APK application ID."
grep -Fq "native-code: 'arm64-v8a' 'x86_64'" <<<"$metadata" || block "Required Android ABIs are missing."
CONFIG="$(find "$ROOT/app/build/generated/source/buildConfig/debug" -type f -name BuildConfig.java -print -quit)"
[[ -n "$CONFIG" ]] || block "Generated BuildConfig is missing."
grep -Fq "DESTINEDFX_ENDPOINTS_LOCKED = true" "$CONFIG" || block "APK is not endpoint-locked."
grep -Fq "MT5_MANAGED_SERVICE_URL = \"$BRIDGE_URL\"" "$CONFIG" || block "Bridge origin was not embedded."
grep -Fq "MANAGEMENT_API_ORIGIN = \"$MANAGEMENT_URL\"" "$CONFIG" || block "Management API origin was not embedded."
mkdir -p "$OUTPUT"
cp "$APK" "$OUTPUT/DestinedFX-debug.apk"
chmod 0644 "$OUTPUT/DestinedFX-debug.apk"
sha256sum "$OUTPUT/DestinedFX-debug.apk" > "$OUTPUT/DestinedFX-debug.apk.sha256"
chmod 0644 "$OUTPUT/DestinedFX-debug.apk.sha256"
printf 'DESTINEDFX_SELF_HOSTED_APK_BUILD_OK\nAPK: %s/DestinedFX-debug.apk\n' "$OUTPUT"
cat "$OUTPUT/DestinedFX-debug.apk.sha256"

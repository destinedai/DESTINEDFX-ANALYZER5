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
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
CODESPACES_BUILD=false
CODESPACES_VALUE="$(printenv CODESPACES 2>/dev/null || true)"

# Derive the Bridge host from GitHub's Codespaces environment. The Management API
# remains a separate service and is deliberately not invented or replaced here.
if [[ ! -f "$MANIFEST" && "$CODESPACES_VALUE" == "true" ]]; then
  CODESPACE_NAME="$(printenv CODESPACE_NAME 2>/dev/null || true)"
  CODESPACE_DOMAIN="$(printenv GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN 2>/dev/null || printf 'app.github.dev')"
  MANAGEMENT_API_ORIGIN="$(printenv MANAGEMENT_API_ORIGIN 2>/dev/null || true)"
  [[ "$CODESPACE_NAME" =~ ^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$ ]] || block "GitHub Codespaces reported an invalid or missing CODESPACE_NAME."
  case "$CODESPACE_DOMAIN" in
    app.github.dev|preview.app.github.dev|githubpreview.dev) ;;
    *) block "Unsupported GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN. Update the strict Codespaces host allowlist before building." ;;
  esac
  if [[ -z "$MANAGEMENT_API_ORIGIN" ]]; then
    MANAGEMENT_API_ORIGIN="https://$CODESPACE_NAME-8010.$CODESPACE_DOMAIN"
  fi
  CODESPACE_BRIDGE_URL="https://$CODESPACE_NAME-8000.$CODESPACE_DOMAIN"
  MANIFEST="$TEMP_DIR/codespaces-public-endpoints.json"
  python3 - "$MANIFEST" "$CODESPACE_BRIDGE_URL" "$MANAGEMENT_API_ORIGIN" <<'PY'
import json, os, sys
from pathlib import Path
target = Path(sys.argv[1])
document = {
    "schema_version": 1,
    "bridge_url": sys.argv[2],
    "management_api_origin": sys.argv[3],
}
with target.open("x", encoding="utf-8") as stream:
    json.dump(document, stream, sort_keys=True, separators=(",", ":"))
    stream.write("\n")
os.chmod(target, 0o600)
PY
  export DESTINEDFX_CODESPACES_HOST_BUILD=true
  CODESPACES_BUILD=true
fi
[[ -f "$MANIFEST" && ! -L "$MANIFEST" && -r "$MANIFEST" ]] || block "A verified endpoint manifest is missing. On the persistent host, complete install-self-hosted.sh first. In Codespaces, the builder derives both forwarded origins from CODESPACE_NAME and requires both APIs to pass HTTPS readiness and EdDSA-key checks."
command -v python3 >/dev/null || block "Python 3 is required."
command -v java >/dev/null || block "JDK 21 is required."
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$SDK" && -d "$SDK/build-tools" ]] || block "Set ANDROID_SDK_ROOT or ANDROID_HOME to an installed Android SDK."
TOOLS="$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -V | tail -1)"
APKSIGNER="$SDK/build-tools/$TOOLS/apksigner"
AAPT="$SDK/build-tools/$TOOLS/aapt"
AAPT2="$SDK/build-tools/$TOOLS/aapt2"
UNZIP="$(command -v unzip || true)"
STRINGS="$(command -v strings || true)"
[[ -x "$APKSIGNER" && -x "$AAPT" && -x "$AAPT2" ]] || block "Android build-tools must include apksigner, aapt and aapt2."
[[ -n "$UNZIP" && -n "$STRINGS" ]] || block "unzip and strings are required to inspect the final APK bytecode."

readarray -t ORIGINS < <(python3 - "$MANIFEST" <<'PY'
import ipaddress, json, socket, stat, sys, urllib.request
from pathlib import Path
from urllib.parse import urlsplit
p = Path(sys.argv[1])
if p.is_symlink(): raise SystemExit("Blocked: endpoint manifest symlink.")
st = p.stat()
if not stat.S_ISREG(st.st_mode) or st.st_size > 8192 or st.st_mode & 0o022:
    raise SystemExit("Blocked: endpoint manifest permissions or size invalid.")
def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate endpoint manifest key")
        result[key] = value
    return result
try:
    d = json.loads(p.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
except (ValueError, TypeError):
    raise SystemExit("Blocked: endpoint manifest JSON is invalid or has duplicate keys.")
expected_keys = {"schema_version", "bridge_url", "management_api_origin"}
if type(d) is not dict or set(d) != expected_keys:
    raise SystemExit("Blocked: endpoint manifest must contain exactly the two public origins and schema_version; extra fields are forbidden.")
if type(d["schema_version"]) is not int or d["schema_version"] != 1:
    raise SystemExit("Blocked: unsupported endpoint manifest version.")
if type(d["bridge_url"]) is not str or type(d["management_api_origin"]) is not str:
    raise SystemExit("Blocked: endpoint manifest origins must be strings.")
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
    if h in {"example.com", "example.net", "example.org", "localhost"} or h.endswith((".example", ".test", ".invalid", ".localhost", ".local", ".internal", ".example.com", ".example.net", ".example.org", ".onion")) or "placeholder" in h:
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
    req = urllib.request.Request(url, headers={
        "Accept":"application/json",
        "X-Tunnel-Skip-AntiPhishing-Page":"true",
    })
    try:
        with urllib.request.urlopen(req, timeout=12) as r:
            if r.geturl().rstrip("/") != url.rstrip("/"):
                raise SystemExit(f"Blocked: {label} redirected to an unverified origin.")
            body = r.read(65537)
            if r.status != 200: raise SystemExit(f"Blocked: {label} returned non-200.")
    except SystemExit: raise
    except Exception as e: raise SystemExit(f"Blocked: {label} HTTPS probe failed ({type(e).__name__}).")
    if len(body) > 65536: raise SystemExit(f"Blocked: {label} response too large.")
    try: return json.loads(body)
    except Exception: raise SystemExit(f"Blocked: {label} returned invalid JSON.")
if os.environ.get("DESTINEDFX_CODESPACES_HOST_BUILD") == "true":
    live = get(b + "/health/live", "Codespaces Bridge liveness")
    health = get(b + "/health", "Codespaces Bridge forwarded-host health")
    config = get(b + "/health/config", "Codespaces Bridge configuration")
    if live.get("status") != "ok":
        raise SystemExit("Blocked: Codespaces Bridge liveness did not pass.")
    if (health.get("status") != "ok" or health.get("host_profile") != "EPHEMERAL"
            or health.get("public_exposure") is not True or health.get("expected_forwarded_host") != bh):
        raise SystemExit("Blocked: Codespaces Bridge public-forwarded-host/EPHEMERAL check did not pass.")
    if (config.get("status") != "configured" or config.get("database") != "ok"
            or config.get("managed_execution") != "configured"
            or config.get("per_user_terminal_isolation") not in {"VERIFIED", "WORKER_ATTESTATION_REQUIRED"}):
        raise SystemExit("Blocked: Codespaces Bridge configuration or per-user isolation is not ready.")
else:
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
metadata="$("$AAPT2" dump badging "$APK")"
grep -Fq "package: name='com.aistudio.destinedfx.qwvzxp'" <<<"$metadata" || block "Unexpected APK application ID."
grep -Fq "arm64-v8a" <<<"$metadata" && grep -Fq "x86_64" <<<"$metadata" || block "Required Android ABIs are missing."
# Verify the compiled endpoint constants in the APK itself, not only the
# intermediate BuildConfig source generated by Gradle.
DEX_DIR="$TEMP_DIR/dex"
mkdir -p "$DEX_DIR"
"$UNZIP" -oq "$APK" 'classes*.dex' -d "$DEX_DIR" || block "APK DEX bytecode could not be extracted."
mapfile -t DEX_FILES < <(find "$DEX_DIR" -maxdepth 1 -type f -name 'classes*.dex' -print | sort)
[[ "${#DEX_FILES[@]}" -gt 0 ]] || block "APK contains no DEX bytecode."
DEX_STRINGS="$("$STRINGS" "${DEX_FILES[@]}")" || block "APK DEX strings could not be inspected."
grep -Fq -- "$BRIDGE_URL" <<<"$DEX_STRINGS" || block "Bridge origin is not embedded in APK bytecode."
grep -Fq -- "$MANAGEMENT_URL" <<<"$DEX_STRINGS" || block "Management API origin is not embedded in APK bytecode."
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

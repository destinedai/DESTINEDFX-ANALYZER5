#!/usr/bin/env bash
set -Eeuo pipefail

collect_ui_screenshots() {
  local app_id="com.aistudio.destinedfx.qwvzxp"
  local destination="app/build/reports/ui-screenshots"
  mkdir -p "$destination"
  for width in 320 430; do
    for screen in analyzer strategies calendar bot risk settings; do
      local name="${screen}_${width}dp.png"
      adb exec-out run-as "$app_id" cat "files/ui-review/$name" > "$destination/$name" 2>/dev/null || rm -f "$destination/$name"
    done
  done
  find "$destination" -type f -name '*.png' -printf '%f %s bytes\n' || true
}

capture_failure() {
  local rc=$?
  collect_ui_screenshots || true
  if [ "$rc" -ne 0 ]; then
    mkdir -p "$RUNNER_TEMP/emulator-diagnostics"
    adb devices -l > "$RUNNER_TEMP/emulator-diagnostics/adb-devices.txt" 2>&1 || true
    adb shell getprop > "$RUNNER_TEMP/emulator-diagnostics/getprop.txt" 2>&1 || true
    adb shell df -h > "$RUNNER_TEMP/emulator-diagnostics/storage.txt" 2>&1 || true
    adb shell pm list packages > "$RUNNER_TEMP/emulator-diagnostics/packages.txt" 2>&1 || true
    adb logcat -d -v threadtime > "$RUNNER_TEMP/emulator-diagnostics/logcat.txt" 2>&1 || true
    echo "Instrumentation failed with exit code $rc. Device diagnostics captured; this is not a passing runtime test." >> "$GITHUB_STEP_SUMMARY"
  fi
  return "$rc"
}

trap capture_failure EXIT
adb wait-for-device
for attempt in $(seq 1 120); do
  booted="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  state="$(adb get-state 2>/dev/null || true)"
  if [ "$booted" = "1" ] && [ "$state" = "device" ]; then break; fi
  if [ "$attempt" -eq 120 ]; then echo "Emulator did not complete boot within 120 checks." >&2; exit 1; fi
  sleep 5
done
for attempt in $(seq 1 30); do
  if adb shell pm path android >/dev/null 2>&1; then break; fi
  if [ "$attempt" -eq 30 ]; then echo "Package manager did not become ready." >&2; exit 1; fi
  sleep 2
done
adb devices -l
adb shell getprop ro.build.version.sdk
adb shell df -h /data
./gradlew :app:connectedDebugAndroidTest --stacktrace
collect_ui_screenshots

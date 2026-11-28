from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_ci_debug_signer_is_explicit_and_default_developer_signing_is_preserved():
    build = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    workflow = (ROOT / ".github" / "workflows" / "build-apk.yml").read_text(encoding="utf-8")
    assert 'System.getenv("DESTINEDFX_DEBUG_KEYSTORE_PATH")' in build
    assert 'create("destinedfxCiDebug")' in build
    assert 'storeFile = file(destinedFxDebugKeystorePath)' in build
    assert 'signingConfig = signingConfigs.getByName("destinedfxCiDebug")' in build
    assert 'if (destinedFxDebugKeystorePath.isNotBlank())' in build
    assert 'DESTINEDFX_DEBUG_KEYSTORE_PATH=$GITHUB_WORKSPACE/debug.keystore' in workflow
    assert 'keyAlias = "androiddebugkey"' in build

 
def test_production_release_requires_owner_upload_key_endpoints_and_emulator_acceptance():
    build = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    workflow = (ROOT / ".github" / "workflows" / "build-apk.yml").read_text(encoding="utf-8")
    ui = (ROOT / "app" / "src" / "main" / "java" / "com" / "example" / "ui" / "SimplifiedBotWorkstation.kt").read_text(encoding="utf-8")
    preview_ui = (ROOT / "app" / "src" / "main" / "java" / "com" / "example" / "ui" / "workstations" / "BotWorkstation.kt").read_text(encoding="utf-8")
    assert "KEYSTORE_PATH" in build and "STORE_PASSWORD" in build and "KEY_PASSWORD" in build
    assert "destinedFxEmulatorAccepted" in build
    assert r'APK_BUILD_LABEL", "\"RELEASE APK - DEMO DEFAULT - REAL MT5 AVAILABLE AFTER CONFIRMATION\""' in build
    assert "android-release-apk:" in workflow
    assert "DESTINEDFX_UPLOAD_KEYSTORE_B64" in workflow
    assert "assembleRelease" in workflow
    assert "live_emulator_accepted" in workflow
    for label in ('"Host: ${serviceReadiness?.hostProfile', 'Backup: ${serviceReadiness?.backupStatus', 'EA heartbeat:', 'REAL MT5 confirmation is account-bound:'):
        assert label in ui
    assert "FilterChip(selected = selected, enabled = !busy, onClick = { selectMt5Slot(brand, mode) }" in ui
    assert 'if (mode == "LIVE" && !storedDemoPass && !canSelectMt5Mode' not in ui
    assert "hostProfile = liveLoginHostProfile" in ui
    assert "refreshManagedHostReadiness" in ui
    assert "CONFIRM REAL TRADE" in ui
    assert "liveDemoPassAccepted && snapshot?.liveAuthorized != true" not in ui
    assert "authorizeLiveSession" in (ROOT / "app" / "src" / "main" / "java" / "com" / "example" / "data" / "trade" / "MT5RemoteBridgeClient.kt").read_text(encoding="utf-8")
    assert '"Emulator" to com.example.BuildConfig.DESTINEDFX_EMULATOR_ACCEPTED' not in preview_ui

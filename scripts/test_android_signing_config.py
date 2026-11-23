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

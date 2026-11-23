from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import verify_apk_artifact as verifier


GRADLE = '''
applicationId = "com.aistudio.destinedfx.qwvzxp"
minSdk = 24
targetSdk = 36
versionCode = 13
versionName = "1.0.13"
ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
'''

BADGING = """package: name='com.aistudio.destinedfx.qwvzxp' versionCode='13' versionName='1.0.13'
sdkVersion:'24'
targetSdkVersion:'36'
native-code: 'arm64-v8a' 'x86_64'
"""

SIGNER = """Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
V2 Signer: certificate SHA-256 digest: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
"""


def test_gradle_apk_contract_extracts_package_version_sdk_and_abis():
    assert verifier.parse_gradle_contract(GRADLE) == {
        "package_name": "com.aistudio.destinedfx.qwvzxp",
        "version_code": 13,
        "version_name": "1.0.13",
        "minimum_sdk": 24,
        "target_sdk": 36,
        "abis": ["arm64-v8a", "x86_64"],
    }


def test_aapt_badging_extracts_installed_apk_metadata():
    assert verifier.parse_apk_badging(BADGING) == {
        "package_name": "com.aistudio.destinedfx.qwvzxp",
        "version_code": 13,
        "version_name": "1.0.13",
        "minimum_sdk": 24,
        "target_sdk": 36,
        "abis": ["arm64-v8a", "x86_64"],
    }


def test_aapt2_badging_accepts_min_sdk_version_label():
    aapt2_badging = """package: name='com.aistudio.destinedfx.qwvzxp' versionCode='13' versionName='1.0.13'
minSdkVersion:'24'
targetSdkVersion:'36'
native-code: 'arm64-v8a' 'x86_64'
"""
    assert verifier.parse_apk_badging(aapt2_badging) == {
        "package_name": "com.aistudio.destinedfx.qwvzxp",
        "version_code": 13,
        "version_name": "1.0.13",
        "minimum_sdk": 24,
        "target_sdk": 36,
        "abis": ["arm64-v8a", "x86_64"],
    }


def test_apksigner_requires_verified_scheme_and_certificate_fingerprint():
    fingerprint, schemes = verifier.parse_apksigner_output(SIGNER)
    assert fingerprint == "0123456789abcdef" * 4
    assert schemes == ["v2 scheme (APK Signature Scheme v2)"]


def test_apksigner_rejects_signature_without_verified_scheme():
    try:
        verifier.parse_apksigner_output(
            "Verified using v2 scheme (APK Signature Scheme v2): false\n"
            "V2 Signer: certificate SHA-256 digest: " + "a" * 64
        )
    except verifier.VerificationError as error:
        assert "verified APK signature scheme" in str(error)
    else:
        raise AssertionError("An APK with no verified signature scheme must be rejected.")


def test_badging_rejects_missing_target_sdk_or_abi():
    for invalid in (
        "package: name='pkg' versionCode='1' versionName='1'\nsdkVersion:'24'\n",
        "package: name='pkg' versionCode='1' versionName='1'\ntargetSdkVersion:'36'\nnative-code: 'x86_64'\n",
    ):
        try:
            verifier.parse_apk_badging(invalid)
        except verifier.VerificationError:
            pass
        else:
            raise AssertionError("Incomplete APK metadata must be rejected.")

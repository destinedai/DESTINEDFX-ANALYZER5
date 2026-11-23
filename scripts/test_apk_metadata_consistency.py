from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import verify_apk_metadata_consistency as checker

ROOT_SHA = "234523c36e0799a93a22d02eeee5417f3c5e9d06"


def _metadata(*, source=ROOT_SHA, signer="a" * 64, package="com.aistudio.destinedfx.qwvzxp",
              version_code=13, abi=None, apk_name="app-debug.apk"):
    payload = b"synthetic APK bytes for metadata consistency tests"
    return {
        "source_sha": source,
        "apk_file": apk_name,
        "package_name": package,
        "version_name": "1.0.13",
        "version_code": version_code,
        "minimum_sdk": 24,
        "target_sdk": 36,
        "abis": abi or ["arm64-v8a", "x86_64"],
        "size_bytes": len(payload),
        "sha256": hashlib.sha256(payload).hexdigest(),
        "certificate_sha256": signer,
        "verified_signature_schemes": ["v2 scheme (APK Signature Scheme v2)"],
        "debug_only": True,
    }


def _three(*, signers=None):
    signers = signers or ["a" * 64] * 3
    return {
        "android-jvm": _metadata(signer=signers[0]),
        "codespaces-bootstrap": _metadata(
            signer=signers[1], apk_name="DestinedFX-Codespaces-bootstrap.apk"
        ),
        "independent-standard": _metadata(signer=signers[2]),
    }


def test_three_build_paths_report_shared_contract_and_never_claim_upgrade():
    report = checker.build_report(_three(), ROOT_SHA, stable_signing_required=True)
    assert report["current_run_signers_match"] is True
    assert report["shared_package_contract"]["package_name"] == "com.aistudio.destinedfx.qwvzxp"
    assert report["upgrade_tested"] is False
    assert report["upgrade_compatibility_claimed"] is False
    assert report["release_build"] is False


def test_independent_ephemeral_signers_are_reported_fresh_install_only():
    report = checker.build_report(
        _three(signers=["a" * 64, "b" * 64, "c" * 64]),
        ROOT_SHA,
        stable_signing_required=False,
    )
    assert report["current_run_signers_match"] is False
    assert report["fresh_install_only_across_runs"] is True
    assert report["disposition"] == "FRESH_INSTALL_ONLY_STABLE_DEBUG_KEY_NOT_CONFIGURED"
    assert report["upgrade_compatibility_claimed"] is False


def test_configured_stable_signing_rejects_different_job_certificates():
    with pytest.raises(checker.VerificationError, match="different signers"):
        checker.build_report(
            _three(signers=["a" * 64, "b" * 64, "a" * 64]),
            ROOT_SHA,
            stable_signing_required=True,
        )


def test_wrong_source_sha_is_rejected():
    metadata = _three()
    metadata["codespaces-bootstrap"]["source_sha"] = "1" * 40
    with pytest.raises(checker.VerificationError, match="source SHA"):
        checker.build_report(metadata, ROOT_SHA, stable_signing_required=False)


def test_package_version_and_abi_drift_are_rejected():
    for field, value in (
        ("package_name", "com.example.other"),
        ("version_code", 14),
        ("abis", ["x86_64"]),
    ):
        metadata = _three()
        metadata["independent-standard"][field] = value
        with pytest.raises(checker.VerificationError, match=field):
            checker.build_report(metadata, ROOT_SHA, stable_signing_required=False)


def test_missing_required_build_path_is_rejected():
    metadata = _three()
    metadata.pop("codespaces-bootstrap")
    with pytest.raises(checker.VerificationError, match="three independent"):
        checker.build_report(metadata, ROOT_SHA, stable_signing_required=False)


def test_metadata_sidecar_and_real_apk_bytes_are_independently_checked(tmp_path):
    apk = tmp_path / "app-debug.apk"
    apk.write_bytes(b"synthetic APK bytes for metadata consistency tests")
    metadata = _metadata()
    metadata_path = tmp_path / "apk-metadata.json"
    raw = json.dumps(metadata).encode()
    metadata_path.write_bytes(raw)
    Path(str(metadata_path) + ".sha256").write_text(
        f"{hashlib.sha256(raw).hexdigest()}  {metadata_path.name}\n"
    )
    loaded = checker.load_verified_artifact("android-jvm", metadata_path, apk, ROOT_SHA)
    assert loaded["sha256"] == metadata["sha256"]
    apk.write_bytes(b"changed APK bytes")
    with pytest.raises(checker.VerificationError, match="APK size|APK SHA-256"):
        checker.load_verified_artifact("android-jvm", metadata_path, apk, ROOT_SHA)


def test_metadata_sidecar_tampering_is_rejected(tmp_path):
    apk = tmp_path / "app-debug.apk"
    apk.write_bytes(b"synthetic APK bytes for metadata consistency tests")
    metadata = _metadata()
    metadata_path = tmp_path / "apk-metadata.json"
    metadata_path.write_text(json.dumps(metadata))
    Path(str(metadata_path) + ".sha256").write_text("0" * 64 + "  apk-metadata.json\n")
    with pytest.raises(checker.VerificationError, match="sidecar"):
        checker.load_verified_artifact("android-jvm", metadata_path, apk, ROOT_SHA)

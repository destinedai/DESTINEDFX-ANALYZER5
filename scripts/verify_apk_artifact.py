#!/usr/bin/env python3
"""Verify APK metadata, signing identity, digest and exact checked-out source SHA."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


class VerificationError(RuntimeError):
    """A sanitized APK-integrity verification failure."""


def _capture(pattern: str, content: str, label: str) -> str:
    match = re.search(pattern, content, re.MULTILINE)
    if not match:
        raise VerificationError(f"APK metadata is missing {label}.")
    return match.group(1)


def parse_gradle_contract(content: str) -> dict[str, Any]:
    application_id = _capture(r'applicationId\s*=\s*"([^"]+)"', content, "applicationId")
    version_code = int(_capture(r'versionCode\s*=\s*(\d+)', content, "versionCode"))
    version_name = _capture(r'versionName\s*=\s*"([^"]+)"', content, "versionName")
    min_sdk = int(_capture(r'minSdk\s*=\s*(\d+)', content, "minSdk"))
    target_sdk = int(_capture(r'targetSdk\s*=\s*(\d+)', content, "targetSdk"))
    abi_block = re.search(r'abiFilters\s*\+=\s*listOf\(([^)]*)\)', content)
    if not abi_block:
        raise VerificationError("Gradle metadata is missing configured ABI filters.")
    abis = sorted(set(re.findall(r'"([^"]+)"', abi_block.group(1))))
    if not abis:
        raise VerificationError("Gradle metadata has no configured Android ABI.")
    return {
        "package_name": application_id,
        "version_code": version_code,
        "version_name": version_name,
        "minimum_sdk": min_sdk,
        "target_sdk": target_sdk,
        "abis": abis,
    }


def parse_apk_badging(content: str) -> dict[str, Any]:
    package = re.search(
        r"^package:\s+name=['\"]([^'\"]+)['\"]\s+versionCode=['\"](\d+)['\"]\s+versionName=['\"]([^'\"]*)['\"]",
        content,
        re.MULTILINE,
    )
    if not package:
        raise VerificationError("APK badging does not contain package, versionCode and versionName.")
    min_sdk = int(_capture(r"^sdkVersion:\s*['\"]?(\d+)", content, "minimum SDK"))
    target_sdk = int(_capture(r"^targetSdkVersion:\s*['\"]?(\d+)", content, "target SDK"))
    native_line = next((line for line in content.splitlines() if line.startswith("native-code:")), "")
    abis = sorted(set(re.findall(r"""['"]([^'"]+)['"]""", native_line)))
    if not abis:
        raise VerificationError("APK does not declare supported native ABIs.")
    return {
        "package_name": package.group(1),
        "version_code": int(package.group(2)),
        "version_name": package.group(3),
        "minimum_sdk": min_sdk,
        "target_sdk": target_sdk,
        "abis": abis,
    }


def _normalize_sha256(value: str) -> str:
    return re.sub(r"[^0-9a-f]", "", value.lower())


def parse_apksigner_output(content: str) -> tuple[str, list[str]]:
    verified = [
        match.group(1).strip()
        for match in re.finditer(r"^Verified using (.+?):\s*true\s*$", content, re.MULTILINE | re.IGNORECASE)
    ]
    if not verified:
        raise VerificationError("apksigner did not report a verified APK signature scheme.")
    match = re.search(
        r"certificate SHA-256 digest:\s*([0-9a-fA-F:]{64,95})",
        content,
        re.IGNORECASE,
    )
    if not match:
        raise VerificationError("apksigner did not report a signing certificate SHA-256 fingerprint.")
    fingerprint = _normalize_sha256(match.group(1))
    if len(fingerprint) != 64:
        raise VerificationError("APK signing certificate fingerprint is malformed.")
    return fingerprint, verified


def verify_apk(
    apk: Path,
    gradle_file: Path,
    apksigner: Path,
    aapt: Path,
    source_sha: str,
    expected_certificate_sha256: str,
) -> dict[str, Any]:
    if not re.fullmatch(r"[0-9a-fA-F]{40}", source_sha):
        raise VerificationError("The supplied source SHA is not a full Git commit SHA.")
    head = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
        timeout=10,
    ).stdout.strip()
    if head.lower() != source_sha.lower():
        raise VerificationError("The checked-out Git HEAD does not match the requested artifact source SHA.")
    if not apk.is_file() or apk.is_symlink() or apk.stat().st_size <= 0:
        raise VerificationError("The APK file is missing, empty or not a regular file.")

    gradle_contract = parse_gradle_contract(gradle_file.read_text(encoding="utf-8"))
    signer = subprocess.run(
        [str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
        check=True,
        capture_output=True,
        text=True,
        timeout=60,
    )
    certificate_sha256, verified_schemes = parse_apksigner_output(signer.stdout + "\n" + signer.stderr)
    expected = _normalize_sha256(expected_certificate_sha256)
    if not expected or len(expected) != 64:
        raise VerificationError("A valid prepared signing-certificate fingerprint is required.")
    if certificate_sha256 != expected:
        raise VerificationError("APK signer fingerprint does not match the certificate in the prepared debug keystore.")

    badging = subprocess.run(
        [str(aapt), "dump", "badging", str(apk)],
        check=True,
        capture_output=True,
        text=True,
        timeout=30,
    ).stdout
    actual = parse_apk_badging(badging)
    for key in ("package_name", "version_code", "version_name", "minimum_sdk", "target_sdk", "abis"):
        if actual[key] != gradle_contract[key]:
            raise VerificationError(
                f"APK {key} does not match app/build.gradle.kts "
                f"(configured={gradle_contract[key]!r}, actual={actual[key]!r})."
            )

    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return {
        "source_sha": source_sha.lower(),
        "apk_file": apk.name,
        **actual,
        "size_bytes": apk.stat().st_size,
        "sha256": digest.hexdigest(),
        "certificate_sha256": certificate_sha256,
        "verified_signature_schemes": verified_schemes,
        "debug_only": True,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--gradle", type=Path, default=Path("app/build.gradle.kts"))
    parser.add_argument("--apksigner", type=Path, required=True)
    parser.add_argument("--aapt", type=Path, required=True, help="Android aapt or aapt2 binary")
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--expected-certificate-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = verify_apk(
            args.apk,
            args.gradle,
            args.apksigner,
            args.aapt,
            args.source_sha,
            args.expected_certificate_sha256,
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        args.output.chmod(0o644)
        checksum_path = args.output.with_suffix(args.output.suffix + ".sha256")
        metadata_digest = hashlib.sha256(args.output.read_bytes()).hexdigest()
        checksum_path.write_text(f"{metadata_digest}  {args.output.name}\n", encoding="ascii")
        checksum_path.chmod(0o644)
        print(json.dumps(report, sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, subprocess.SubprocessError, VerificationError) as exc:
        print(f"APK verification failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

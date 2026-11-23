#!/usr/bin/env python3
"""Cross-check APK provenance and signing consistency across independent CI build paths.

This deliberately never claims that an APK upgrade was tested.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

REQUIRED_ARTIFACTS = ("android-jvm", "codespaces-bootstrap", "independent-standard")
CONTRACT_FIELDS = ("package_name", "version_code", "version_name", "minimum_sdk", "target_sdk", "abis")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
COMMIT_SHA = re.compile(r"^[0-9a-f]{40}$")


class VerificationError(RuntimeError):
    """A sanitized APK artifact consistency failure."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationError(message)


def _valid_metadata(name: str, value: Any, expected_source_sha: str) -> dict[str, Any]:
    _require(isinstance(value, dict), f"{name}: metadata must be a JSON object.")
    _require(
        isinstance(value.get("source_sha"), str)
        and COMMIT_SHA.fullmatch(value["source_sha"].lower()) is not None,
        f"{name}: source SHA is missing or malformed.",
    )
    _require(value["source_sha"].lower() == expected_source_sha.lower(),
             f"{name}: source SHA does not match this workflow run.")
    _require(isinstance(value.get("apk_file"), str) and bool(value["apk_file"]), f"{name}: APK filename is missing.")
    _require(isinstance(value.get("package_name"), str) and bool(value["package_name"]), f"{name}: package name is missing.")
    _require(isinstance(value.get("version_name"), str) and bool(value["version_name"]), f"{name}: version name is missing.")
    for field in ("version_code", "minimum_sdk", "target_sdk", "size_bytes"):
        _require(type(value.get(field)) is int and value[field] > 0, f"{name}: {field} is invalid.")
    _require(value["target_sdk"] >= value["minimum_sdk"], f"{name}: target SDK is below minimum SDK.")
    _require(type(value.get("debug_only")) is bool and value["debug_only"],
             f"{name}: artifact is not explicitly marked debug-only.")
    _require(
        isinstance(value.get("abis"), list) and bool(value["abis"])
        and all(isinstance(item, str) and item for item in value["abis"])
        and len(set(value["abis"])) == len(value["abis"]),
        f"{name}: ABI metadata is missing or invalid.",
    )
    _require(
        isinstance(value.get("verified_signature_schemes"), list)
        and bool(value["verified_signature_schemes"])
        and all(isinstance(item, str) and item for item in value["verified_signature_schemes"]),
        f"{name}: no verified signature scheme is recorded.",
    )
    for field in ("sha256", "certificate_sha256"):
        _require(
            isinstance(value.get(field), str) and SHA256.fullmatch(value[field].lower()) is not None,
            f"{name}: {field} is missing or malformed.",
        )
    normalized = dict(value)
    normalized["source_sha"] = value["source_sha"].lower()
    normalized["sha256"] = value["sha256"].lower()
    normalized["certificate_sha256"] = value["certificate_sha256"].lower()
    normalized["abis"] = sorted(value["abis"])
    normalized["verified_signature_schemes"] = sorted(set(value["verified_signature_schemes"]))
    return normalized


def load_verified_artifact(name: str, metadata_path: Path, apk_path: Path, expected_source_sha: str) -> dict[str, Any]:
    for path, label in ((metadata_path, "metadata"), (apk_path, "APK")):
        _require(not path.is_symlink() and path.is_file(), f"{name}: {label} is missing, non-regular or symlinked.")
    sidecar = Path(str(metadata_path) + ".sha256")
    _require(not sidecar.is_symlink() and sidecar.is_file(), f"{name}: metadata SHA-256 sidecar is missing or unsafe.")
    raw_metadata = metadata_path.read_bytes()
    metadata_digest = hashlib.sha256(raw_metadata).hexdigest()
    sidecar_fields = sidecar.read_text(encoding="ascii").strip().split()
    _require(
        len(sidecar_fields) == 2
        and SHA256.fullmatch(sidecar_fields[0].lower()) is not None
        and sidecar_fields[0].lower() == metadata_digest
        and sidecar_fields[1] == metadata_path.name,
        f"{name}: metadata checksum sidecar does not verify.",
    )
    try:
        metadata = json.loads(raw_metadata)
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise VerificationError(f"{name}: metadata JSON is invalid.") from None
    result = _valid_metadata(name, metadata, expected_source_sha)
    # CI may rename a verified APK when packaging a distinct artifact. The APK bytes,
    # size and metadata SHA remain authoritative; retain both names in the report.
    result["downloaded_apk_file"] = apk_path.name
    apk_digest = hashlib.sha256(apk_path.read_bytes()).hexdigest()
    _require(apk_path.stat().st_size == result["size_bytes"], f"{name}: APK size does not match metadata.")
    _require(apk_digest == result["sha256"], f"{name}: APK SHA-256 does not match metadata.")
    result["metadata_file"] = metadata_path.name
    result["metadata_sha256"] = metadata_digest
    return result


def build_report(metadata_by_name: dict[str, dict[str, Any]], expected_source_sha: str,
                 stable_signing_required: bool) -> dict[str, Any]:
    _require(COMMIT_SHA.fullmatch(expected_source_sha.lower()) is not None,
             "Expected source SHA must be a full Git commit SHA.")
    _require(set(metadata_by_name) == set(REQUIRED_ARTIFACTS),
             "All three independent APK build-path metadata records are required.")
    validated = {
        name: _valid_metadata(name, metadata_by_name[name], expected_source_sha)
        for name in REQUIRED_ARTIFACTS
    }
    reference = validated[REQUIRED_ARTIFACTS[0]]
    for name in REQUIRED_ARTIFACTS[1:]:
        for field in CONTRACT_FIELDS:
            _require(validated[name][field] == reference[field],
                     f"{name}: {field} differs from the other APK build paths.")

    signers = sorted({validated[name]["certificate_sha256"] for name in REQUIRED_ARTIFACTS})
    signers_match = len(signers) == 1
    if stable_signing_required:
        _require(signers_match,
                 "A stable debug keystore is configured, but independent APK build paths produced different signers.")

    disposition = (
        "STABLE_SIGNER_MATCHED_UPGRADE_NOT_TESTED"
        if stable_signing_required
        else "FRESH_INSTALL_ONLY_STABLE_DEBUG_KEY_NOT_CONFIGURED"
    )
    warnings = (
        ["Signer identity matches across this run, but no in-place APK upgrade test was performed."]
        if stable_signing_required
        else [
            "DESTINEDFX_DEBUG_KEYSTORE_B64 is not configured. A matching signer in this run would not establish continuity across later runs.",
            "Do not claim in-place upgrade compatibility; no actual APK update test was performed.",
        ]
    )
    return {
        "schema": "destinedfx.apk-signing-consistency.v1",
        "source_sha": expected_source_sha.lower(),
        "artifacts": {
            name: {
                field: validated[name].get(field)
                for field in (
                    "apk_file", "downloaded_apk_file", "package_name", "version_name", "version_code",
                    "minimum_sdk", "target_sdk", "abis", "size_bytes", "sha256",
                    "certificate_sha256", "verified_signature_schemes",
                    "metadata_file", "metadata_sha256", "debug_only",
                )
            }
            for name in REQUIRED_ARTIFACTS
        },
        "shared_package_contract": {field: reference[field] for field in CONTRACT_FIELDS},
        "unique_certificate_sha256": signers,
        "current_run_signers_match": signers_match,
        "stable_debug_signing_key_configured": stable_signing_required,
        "fresh_install_only_across_runs": not stable_signing_required,
        "upgrade_tested": False,
        "upgrade_compatibility_claimed": False,
        "release_build": False,
        "disposition": disposition,
        "warnings": warnings,
    }


def parse_named_paths(items: list[str], label: str) -> dict[str, Path]:
    parsed: dict[str, Path] = {}
    for item in items:
        if "=" not in item:
            raise VerificationError(f"{label} input must use NAME=PATH.")
        name, path = item.split("=", 1)
        if not name or not path or name in parsed:
            raise VerificationError(f"{label} contains an empty or duplicate name.")
        parsed[name] = Path(path)
    return parsed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--metadata", action="append", required=True, metavar="NAME=PATH")
    parser.add_argument("--apk", action="append", required=True, metavar="NAME=PATH")
    parser.add_argument("--stable-signing-required", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        metadata_paths = parse_named_paths(args.metadata, "--metadata")
        apk_paths = parse_named_paths(args.apk, "--apk")
        _require(set(metadata_paths) == set(REQUIRED_ARTIFACTS),
                 "Metadata inputs must name all three required artifacts.")
        _require(set(apk_paths) == set(REQUIRED_ARTIFACTS),
                 "APK inputs must name all three required artifacts.")
        loaded = {
            name: load_verified_artifact(name, metadata_paths[name], apk_paths[name], args.source_sha)
            for name in REQUIRED_ARTIFACTS
        }
        report = build_report(loaded, args.source_sha, args.stable_signing_required)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        digest = hashlib.sha256(args.output.read_bytes()).hexdigest()
        Path(str(args.output) + ".sha256").write_text(f"{digest}  {args.output.name}\n", encoding="ascii")
        print(json.dumps(report, sort_keys=True))
        return 0
    except (OSError, ValueError, VerificationError) as exc:
        print(f"APK consistency verification failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

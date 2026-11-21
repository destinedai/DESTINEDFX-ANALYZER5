#!/usr/bin/env python3
"""Export the Ed25519 public verification key for the DestinedFX Bridge.

The private key stays in the management backend's protected secret store. This
utility reads it only to derive a public PEM file for the persistent Bridge.
"""
from __future__ import annotations

import argparse
import os
import stat
import tempfile
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


def export_public_key(private_key_path: Path, output_path: Path, *, overwrite: bool = False) -> Path:
    private_path = private_key_path.expanduser().resolve(strict=True)
    destination = output_path.expanduser().resolve()
    if not private_path.is_file():
        raise ValueError("Private-key path must be a file")
    if destination.exists() and not overwrite:
        raise FileExistsError("Output exists; use --overwrite only after reviewing the target")

    private_key = serialization.load_pem_private_key(private_path.read_bytes(), password=None)
    if not isinstance(private_key, Ed25519PrivateKey):
        raise ValueError("Management step-up key must be an Ed25519 private key")

    public_pem = private_key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )

    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(prefix=f".{destination.name}.", dir=destination.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(public_pem)
            stream.flush()
            os.fsync(stream.fileno())
        if os.name != "nt":
            os.chmod(temporary_name, stat.S_IRUSR | stat.S_IWUSR)
        os.replace(temporary_name, destination)
    except Exception:
        try:
            os.unlink(temporary_name)
        except OSError:
            pass
        raise
    return destination


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--private-key", required=True, type=Path, help="Protected backend Ed25519 private-key PEM file")
    parser.add_argument("--output", required=True, type=Path, help="Destination for the derived public PEM")
    parser.add_argument("--overwrite", action="store_true", help="Replace an existing public-key file")
    args = parser.parse_args()
    try:
        destination = export_public_key(args.private_key, args.output, overwrite=args.overwrite)
    except (OSError, ValueError, TypeError) as exc:
        parser.error(str(exc))
    print(f"Ed25519 public verification key exported to: {destination}")
    print("The private key was not copied or printed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

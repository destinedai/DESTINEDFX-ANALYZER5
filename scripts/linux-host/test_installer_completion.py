from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path

MODULE = Path(__file__).with_name("installer_completion.py")
SPEC = importlib.util.spec_from_file_location("installer_completion", MODULE)
completion = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(completion)


def fixture(tmp_path: Path):
    state = tmp_path / "state"
    state.mkdir(mode=0o700)
    state.chmod(0o700)
    provenance = state / "mt5-installer-provenance.env"
    provenance.write_text(
        "authenticode_status=verified\n"
        "authenticode_signer=MetaQuotes Ltd\n"
        "setup_sha256=" + ("a" * 64) + "\n",
        encoding="utf-8",
    )
    provenance.chmod(0o600)
    prefix = tmp_path / "prefix"
    prefix.mkdir()
    terminal = prefix / "terminal64.exe"
    editor = prefix / "metaeditor64.exe"
    terminal.write_bytes(b"verified terminal fixture")
    editor.write_bytes(b"verified MetaEditor fixture")
    terminal.chmod(0o755)
    editor.chmod(0o755)
    manifest = state / "installer-completed.json"
    return manifest, provenance, terminal, editor


def test_successful_installer_exit_binds_exact_provenance_and_binaries(tmp_path):
    manifest, provenance, terminal, editor = fixture(tmp_path)
    record = completion.record_completion(manifest, provenance, terminal, editor, "gui", 0)
    assert record["installer_exit_code"] == 0
    assert record["mode"] == "gui"
    assert manifest.stat().st_mode & 0o777 == 0o600
    verified = completion.verify_completion(manifest, provenance, terminal, editor)
    assert verified["terminal_sha256"] == completion._fingerprint(
        terminal, os.geteuid(), private=False
    )


def test_nonzero_or_unknown_installer_exit_cannot_create_completion(tmp_path):
    manifest, provenance, terminal, editor = fixture(tmp_path)
    for mode, exit_code in (("gui", 1), ("headless", 29), ("unknown", 0)):
        try:
            completion.record_completion(manifest, provenance, terminal, editor, mode, exit_code)
        except completion.CompletionError:
            pass
        else:
            raise AssertionError("failed/unknown installer run must not be recorded")
    assert not manifest.exists()


def test_preexisting_or_changed_terminal_and_editor_do_not_match_completion(tmp_path):
    manifest, provenance, terminal, editor = fixture(tmp_path)
    completion.record_completion(manifest, provenance, terminal, editor, "headless", 0)
    terminal.write_bytes(b"different terminal content")
    try:
        completion.verify_completion(manifest, provenance, terminal, editor)
    except completion.CompletionError as exc:
        assert "terminal" in str(exc)
    else:
        raise AssertionError("changed terminal must invalidate installer completion")


def test_changed_or_unverified_provenance_invalidates_completion(tmp_path):
    manifest, provenance, terminal, editor = fixture(tmp_path)
    completion.record_completion(manifest, provenance, terminal, editor, "gui", 0)
    provenance.write_text(
        "authenticode_status=unverified\n"
        "authenticode_signer=Unknown\n",
        encoding="utf-8",
    )
    provenance.chmod(0o600)
    try:
        completion.verify_completion(manifest, provenance, terminal, editor)
    except completion.CompletionError:
        pass
    else:
        raise AssertionError("changed provenance must invalidate completion")


def test_missing_or_symlinked_binary_is_rejected(tmp_path):
    manifest, provenance, terminal, editor = fixture(tmp_path)
    editor.unlink()
    try:
        completion.record_completion(manifest, provenance, terminal, editor, "gui", 0)
    except completion.CompletionError:
        pass
    else:
        raise AssertionError("missing MetaEditor must fail")
    editor.symlink_to(terminal)
    try:
        completion.record_completion(manifest, provenance, terminal, editor, "gui", 0)
    except completion.CompletionError:
        pass
    else:
        raise AssertionError("symlinked MetaEditor must fail")


def test_manifest_owner_and_mode_are_required(tmp_path):
    manifest, provenance, terminal, editor = fixture(tmp_path)
    completion.record_completion(manifest, provenance, terminal, editor, "gui", 0)
    manifest.chmod(0o644)
    try:
        completion.verify_completion(manifest, provenance, terminal, editor)
    except completion.CompletionError:
        pass
    else:
        raise AssertionError("public manifest permissions must fail closed")
    manifest.chmod(0o600)
    value = json.loads(manifest.read_text(encoding="utf-8"))
    value["installer_exit_code"] = 1
    manifest.write_text(json.dumps(value), encoding="utf-8")
    manifest.chmod(0o600)
    try:
        completion.verify_completion(manifest, provenance, terminal, editor)
    except completion.CompletionError:
        pass
    else:
        raise AssertionError("nonzero installer exit record must fail")

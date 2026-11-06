from pathlib import Path


SCRIPT = Path(__file__).resolve().parent / "windows" / "install-owner-stack.ps1"


def test_installer_is_resumable_without_secret_rotation():
    source = SCRIPT.read_text(encoding="utf-8")
    assert "$resume = Test-Path $secretsPath" in source
    assert "Existing owner-secrets.dpapi could not be decrypted or validated" in source
    assert "if (-not $resume)" in source
    assert "ConvertFrom-SecureString $secureExisting" not in source
    assert "[IO.File]::WriteAllText($secretsPath" in source


def test_installer_requires_all_four_actual_terminal_profiles():
    source = SCRIPT.read_text(encoding="utf-8")
    assert "foreach ($broker in @('FBS','EXNESS','HFM','DERIV'))" in source
    for brand in ("FBS", "EXNESS", "HFM", "DERIV"):
        assert f"[broker_terminals.{brand}]" in source
    assert "single_broker_brand" not in source
    assert "max_terminals = 1" not in source
    assert "Measured safe simultaneous DEMO terminal capacity" in source


def test_installer_keeps_private_tailnet_and_loopback_constraints():
    source = SCRIPT.read_text(encoding="utf-8")
    assert "127.0.0.1:8000" in source
    assert "tailscale.Source serve --bg --https=443 http://127.0.0.1:8000" in source
    assert "tailscale.source funnel" not in source.lower()
    assert "Keep this PC powered on, awake" in source

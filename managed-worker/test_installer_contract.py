from pathlib import Path


SCRIPT = Path(__file__).resolve().parent / "windows" / "install-owner-stack.ps1"


def test_installer_is_resumable_without_secret_rotation():
    source = SCRIPT.read_text(encoding="utf-8")
    assert "$resume = Test-Path $secretsPath" in source
    assert "Existing owner-secrets.dpapi could not be decrypted or validated" in source
    assert "if (-not $resume)" in source
    assert "ConvertFrom-SecureString $secureExisting" not in source
    assert "[IO.File]::WriteAllText($secretsPath" in source


def test_installer_stages_one_selected_broker_and_adds_without_replacing_identity():
    source = SCRIPT.read_text(encoding="utf-8")
    assert "[switch]$AddBroker" in source
    assert "Selected genuine MT5 broker brand" in source
    assert "foreach ($broker in @('FBS','EXNESS','HFM','DERIV'))" not in source
    assert "[broker_terminals.$broker]" in source
    assert "Preserving installed worker identity." in source
    assert "already maps to a different terminal; refusing to change its terminal or data" in source
    assert "add-broker operation is an idempotent no-op" in source
    assert "Existing scheduled tasks and running worker are left untouched" in source
    assert "Add-broker mode verifies but does not reconfigure existing Tailscale Serve." in source
    assert "refusing Serve/config changes" in source
    assert 'TrimEnd() + "`n" + $brandConfig' in source
    assert "-AddBroker requires a complete existing owner stack" in source
    assert "WriteAllText($secretsPath" in source


def test_installer_keeps_private_tailnet_and_loopback_constraints():
    source = SCRIPT.read_text(encoding="utf-8")
    assert "127.0.0.1:8000" in source
    assert "tailscale.Source serve --bg --https=443 http://127.0.0.1:8000" in source
    assert "tailscale.source funnel" not in source.lower()
    assert "NOT a 24/7 service" in source
    assert "powered/awake host" in source

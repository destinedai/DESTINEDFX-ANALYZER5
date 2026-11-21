from pathlib import Path
import subprocess
import sys

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


ROOT = Path(__file__).parent
REPO_ROOT = ROOT.resolve().parents[1]
INSTALLER = (ROOT / "install-owner-stack.ps1").read_text(encoding="utf-8")
SUPERVISOR = (ROOT / "run-owner-stack.ps1").read_text(encoding="utf-8")


def test_installer_migrates_and_atomically_validates_dpapi_bundle():
    assert "$secretNeedsWrite = $false" in INSTALLER
    assert "Write-DpapiSecretBundle" in INSTALLER
    assert "ConvertTo-SecureString ([IO.File]::ReadAllText($temporary))" in INSTALLER
    assert "Move-Item -LiteralPath $temporary -Destination $secretsPath -Force" in INSTALLER
    assert "if (-not $resume -or $secretNeedsWrite)" in INSTALLER


def test_supervisor_registers_stable_worker_before_starting_worker():
    assert "Ensure-RegisteredWorker" in SUPERVISOR
    assert "/v1/operator/workers/$StableWorkerId" in SUPERVISOR
    assert "/v1/operator/workers/$StableWorkerId/rotate" in SUPERVISOR
    assert "/v1/operator/workers" in SUPERVISOR
    assert "/v1/worker/identity" in SUPERVISOR
    assert "Save-OwnerSecrets $Bundle" in SUPERVISOR
    assert SUPERVISOR.index("Ensure-RegisteredWorker $secrets $workerId") < SUPERVISOR.index("Start-ManagedChild 'worker'")


def test_worker_credential_is_not_written_to_worker_config_or_logged():
    assert "worker_token" not in INSTALLER.split('$toml = @"', 1)[-1].split('"@', 1)[0]
    assert "Write-Host $workerToken" not in SUPERVISOR
    assert "Write-Host $secrets.worker_token" not in SUPERVISOR


def test_windows_supervisor_declares_host_mode_and_loads_validated_step_up_public_key():
    assert "$env:BRIDGE_HOST_MODE='windows-owner'" in SUPERVISOR
    assert "$env:BRIDGE_HOST_PROFILE='PERSISTENT'" in SUPERVISOR
    assert "$env:EXECUTION_STORAGE_DURABLE='true'" in SUPERVISOR
    assert "management_step_up_public_key" in SUPERVISOR
    assert "MANAGEMENT_STEP_UP_PUBLIC_KEY" in SUPERVISOR
    assert "Ed25519PublicKey" in SUPERVISOR
    assert "MANAGEMENT_STEP_UP_PUBLIC_KEY" in SUPERVISOR.rsplit("} finally {", 1)[-1]


def test_installer_accepts_only_backend_ed25519_public_key_file_and_persists_it():
    assert "$ManagementStepUpPublicKeyPath" in INSTALLER
    assert "serialization.load_pem_public_key" in INSTALLER
    assert "Ed25519PublicKey" in INSTALLER
    assert "management_step_up_public_key" in INSTALLER
    assert "management_step_up_issuer" in INSTALLER
    assert "management_step_up_audience" in INSTALLER


def test_step_up_key_exporter_derives_matching_public_key_without_copying_private_key(tmp_path):
    private_key = Ed25519PrivateKey.generate()
    private_path = tmp_path / "management-private.pem"
    public_path = tmp_path / "management-public.pem"
    private_path.write_bytes(private_key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ))
    exporter = REPO_ROOT / "scripts" / "export_management_step_up_public_key.py"
    result = subprocess.run(
        [sys.executable, str(exporter), "--private-key", str(private_path), "--output", str(public_path)],
        text=True, capture_output=True, check=False, timeout=15,
    )
    assert result.returncode == 0, result.stderr
    exported = public_path.read_bytes()
    loaded = serialization.load_pem_public_key(exported)
    assert loaded.public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    ) == private_key.public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )
    assert b"PRIVATE KEY" not in exported
    assert "private key was not copied" in result.stdout.lower()


def test_windows_supervisor_configures_a_real_news_calendar_by_default():
    # New entries must remain blocked when the feed is unavailable; the Windows
    # owner host nevertheless needs an actual provider configured by default.
    assert "if ([string]::IsNullOrWhiteSpace($env:NEWS_PROVIDER_URL))" in SUPERVISOR
    assert "$env:NEWS_PROVIDER_URL='https://showmytrades.com/api/public/calendar/v1'" in SUPERVISOR
    assert "$ready.news_provider_configured -eq $true" in SUPERVISOR
    assert SUPERVISOR.index("$env:NEWS_PROVIDER_URL='https://showmytrades.com/api/public/calendar/v1'") < SUPERVISOR.index("Start-ManagedChild 'bridge'")
    cleanup = SUPERVISOR.split("finally {", 1)[-1]
    assert "'NEWS_PROVIDER_URL'" in cleanup
    assert "'NEWS_PROVIDER_TOKEN'" in cleanup

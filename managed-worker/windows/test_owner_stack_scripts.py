from pathlib import Path


ROOT = Path(__file__).parent
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

#requires -Version 5.1
#requires -RunAsAdministrator
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ProjectRoot,
    [Parameter(Mandatory=$true)][ValidatePattern('^https://[A-Za-z0-9.-]+(?::[0-9]+)?$')][string]$TailnetOrigin
)
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'This installer only runs on Windows.' }
$project = (Resolve-Path $ProjectRoot).Path
$bridgeDir = Join-Path $project 'bridge'
$workerDir = Join-Path $project 'managed-worker'
foreach ($required in @((Join-Path $bridgeDir 'app.py'), (Join-Path $workerDir 'worker.py'), (Join-Path $bridgeDir 'requirements.txt'))) {
    if (-not (Test-Path $required -PathType Leaf)) { throw "Required project file missing: $required" }
}
$base = Join-Path $env:ProgramData 'DestinedFX'
$dataDir = Join-Path $base 'data'
$backupDir = Join-Path $base 'backups'
$configDir = Join-Path $base 'config'
$logDir = Join-Path $base 'logs'
$workerData = Join-Path $base 'accounts'
$venvDir = Join-Path $base 'venv'
if (Test-Path (Join-Path $configDir 'owner-secrets.dpapi')) { throw 'An owner setup already exists. Do not rerun this first-install script: it would rotate the Fernet key and worker token. Stop the stack and perform a planned secret migration/recovery instead.' }
New-Item -ItemType Directory -Force -Path $dataDir,$backupDir,$configDir,$logDir,$workerData | Out-Null
# Keep persistent files readable only by the interactive owner, SYSTEM, and local Administrators.
$owner = "$env:USERDOMAIN\$env:USERNAME"
foreach ($path in @($base,$dataDir,$backupDir,$configDir,$logDir,$workerData)) {
    & icacls $path /inheritance:r /grant:r "$owner`:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not restrict ACLs on $path" }
}
$pythonLauncher = Get-Command py.exe -ErrorAction SilentlyContinue
if (-not $pythonLauncher) { throw 'Install Python 3.11+ from python.org first; the installer will not download software.' }
$python = $pythonLauncher.Source
$pythonVersion = & $python -3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")'
if ($LASTEXITCODE -ne 0 -or [version]$pythonVersion -lt [version]'3.11') { throw 'Python 3.11 or later is required.' }
if (-not (Test-Path (Join-Path $venvDir 'Scripts\python.exe'))) {
    & $python -3 -m venv $venvDir
    if ($LASTEXITCODE -ne 0) { throw 'Could not create persistent Python environment.' }
}
$venvPython = Join-Path $venvDir 'Scripts\python.exe'
& $venvPython -m pip install --disable-pip-version-check -r (Join-Path $bridgeDir 'requirements.txt')
if ($LASTEXITCODE -ne 0) { throw 'Bridge dependency installation failed.' }
$terminalInstall = (Resolve-Path (Read-Host 'Exact genuine broker MT5 installation directory')).Path
$terminalExe = Join-Path $terminalInstall 'terminal64.exe'
$metaEditor = Join-Path $terminalInstall 'metaeditor64.exe'
if (-not (Test-Path $terminalExe -PathType Leaf) -or -not (Test-Path $metaEditor -PathType Leaf)) { throw 'Both terminal64.exe and metaeditor64.exe must exist in the supplied genuine broker installation.' }
$eaSource = (Resolve-Path (Read-Host 'Path to DestinedFXTraderEA.mq5')).Path
if (-not $eaSource.EndsWith('.mq5',[StringComparison]::OrdinalIgnoreCase)) { throw 'EA source must be the genuine .mq5 file.' }
$broker = (Read-Host 'First broker brand (FBS / EXNESS / HFM / DERIV)').Trim().ToUpperInvariant()
if ($broker -notin @('FBS','EXNESS','HFM','DERIV')) { throw 'Unsupported broker brand.' }
$workerId = (Read-Host 'Stable worker ID (letters, digits, underscore, hyphen)').Trim()
if ($workerId -notmatch '^[A-Za-z0-9_-]{1,128}$') { throw 'Invalid stable worker ID.' }
$origin = $TailnetOrigin.TrimEnd('/')
$parsed = [uri]$origin
if ($parsed.Host -notmatch '\.ts\.net$' -or $parsed.UserInfo -or $parsed.AbsolutePath -ne '/' -or $parsed.Query -or $parsed.Fragment) { throw 'Use the exact HTTPS *.ts.net hostname shown by tailscale serve status; no path, user-info, query, or fragment.' }
$tailscale = Get-Command tailscale.exe -ErrorAction SilentlyContinue
if (-not $tailscale) { throw 'Install and authenticate Tailscale on this PC first.' }
$tsStatus = & $tailscale.Source status 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { throw 'Tailscale is not connected. Connect this PC to the owner-authorized tailnet first.' }
Write-Host 'Configuring private tailnet HTTPS proxy to the loopback-only bridge (Serve, not Funnel)...'
& $tailscale.Source serve --bg --https=443 http://127.0.0.1:8000
if ($LASTEXITCODE -ne 0) { throw 'Tailscale Serve setup failed. Complete HTTPS/certificate consent in the Tailscale admin console, then rerun.' }
$serveStatus = & $tailscale.Source serve status 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or $serveStatus -notmatch [regex]::Escape($parsed.Host)) { throw 'Serve status did not confirm the supplied tailnet host. Check the URL and rerun.' }
# Generate secrets in-process and store only as a Windows DPAPI CurrentUser-protected file.
$generated = & $venvPython -c 'import json,secrets; from cryptography.fernet import Fernet; print(json.dumps({"fernet_key":Fernet.generate_key().decode(),"worker_token":secrets.token_urlsafe(48)}))'
if ($LASTEXITCODE -ne 0) { throw 'Secret generation failed.' }
$secretObject = $generated | ConvertFrom-Json
$workerConfig = Join-Path $configDir 'worker.toml'
function TomlString([string]$Value) { '"' + $Value.Replace('\','\\').Replace('"','\"') + '"' }
$toml = @"
control_plane_url = $(TomlString $origin)
data_root = $(TomlString $workerData)
single_broker_brand = $(TomlString $broker)
max_terminals = 1
mt5_install = $(TomlString $terminalInstall)
terminal_exe = $(TomlString $terminalExe)
metaeditor_exe = $(TomlString $metaEditor)
ea_source = $(TomlString $eaSource)
compiled_ea = ""
symbol = "EURUSD"
poll_seconds = 5
auth_timeout_seconds = 300
max_restart_retries = 3
restart_backoff_seconds = 30
"@
[IO.File]::WriteAllText($workerConfig,$toml,[Text.UTF8Encoding]::new($false))
$identityPath = Join-Path $configDir 'worker-id.txt'
[IO.File]::WriteAllText($identityPath,$workerId,[Text.UTF8Encoding]::new($false))
$secretJson = @{fernet_key=$secretObject.fernet_key;worker_token=$secretObject.worker_token} | ConvertTo-Json -Compress
$secureSecret = ConvertTo-SecureString $secretJson -AsPlainText -Force
$encrypted = ConvertFrom-SecureString $secureSecret
[IO.File]::WriteAllText((Join-Path $configDir 'owner-secrets.dpapi'),$encrypted,[Text.UTF8Encoding]::new($false))
$secureSecret.Dispose(); $secretJson=$null; $secretObject=$null; $generated=$null
$supervisor = Join-Path $PSScriptRoot 'run-owner-stack.ps1'
$backupScript = Join-Path $PSScriptRoot 'backup-bridge.ps1'
if (-not (Test-Path $supervisor) -or -not (Test-Path $backupScript)) { throw 'Owner stack support scripts are missing.' }
$action = New-ScheduledTaskAction -Execute "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$supervisor`" -ProjectRoot `"$project`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $owner
$settings = New-ScheduledTaskSettingsSet -RestartCount 20 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
$principal = New-ScheduledTaskPrincipal -UserId $owner -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName 'DestinedFX Owner Bridge and Worker' -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
$backupAction = New-ScheduledTaskAction -Execute "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$backupScript`""
$backupTrigger = New-ScheduledTaskTrigger -Daily -At '3:00AM'
Register-ScheduledTask -TaskName 'DestinedFX Bridge SQLite Backup' -Action $backupAction -Trigger $backupTrigger -Settings $settings -Principal $principal -Force | Out-Null
Start-ScheduledTask -TaskName 'DestinedFX Owner Bridge and Worker'
$env:DESTINEDFX_HEALTH_URL="$origin/health/ready"
$healthCode = @'
import json,os,sys,urllib.error,urllib.request
try:
 r=urllib.request.urlopen(os.environ["DESTINEDFX_HEALTH_URL"],timeout=8); code=r.status; body=r.read()
except urllib.error.HTTPError as e:
 code=e.code; body=e.read()
try: result=json.loads(body)
except Exception: result={"response":"unreadable"}
print(json.dumps({"http_status":code,**result},separators=(",",":")))
sys.exit(0 if code==200 and result.get("status")=="ready" and result.get("database")=="ok" and result.get("managed_execution")=="configured" else 2)
'@
$ready=$false
for($attempt=0;$attempt -lt 12;$attempt++){
    & $venvPython -c $healthCode
    if($LASTEXITCODE -eq 0){$ready=$true;break}
    Start-Sleep -Seconds 5
}
Remove-Item Env:DESTINEDFX_HEALTH_URL -ErrorAction SilentlyContinue
if(-not $ready){Stop-ScheduledTask -TaskName 'DestinedFX Owner Bridge and Worker' -ErrorAction SilentlyContinue;throw 'Owner bridge did not pass the private HTTPS readiness check. Do not submit broker credentials; inspect Tailscale Serve status and local logs.'}
& $venvPython (Join-Path $PSScriptRoot 'backup-bridge.py')
if($LASTEXITCODE -ne 0){Write-Warning 'Initial database backup failed; check ProgramData\DestinedFX\backups before using this setup.'}
Write-Host "Installed for $owner. Bridge readiness returned status=ready, database=ok, managed_execution=configured through the Windows Python HTTPS client."
Write-Host "Bridge binds only to 127.0.0.1:8000; private HTTPS origin: $origin"
Write-Host "Durable SQLite path: $(Join-Path $dataDir 'bridge.sqlite3'); rolling backups: $backupDir"
Write-Host "Worker config: $workerConfig; worker ID: $workerId"
Write-Host 'Add the exact HTTPS tailnet origin to each isolated MT5 terminal WebRequest allowlist. Begin with DEMO only.'
Write-Host 'This stack starts at Windows sign-in. Keep this PC powered on, awake, and connected to the internet.'

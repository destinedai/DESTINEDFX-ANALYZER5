$ErrorActionPreference = "Stop"
function TomlString([string]$Value) {
    $escaped = $Value.Replace('\', '\\').Replace('"', '\"')
    return '"' + $escaped + '"'
}
$root = $PSScriptRoot
$workerPath = Join-Path $root "worker.py"
$eaPath = Join-Path $root "DestinedFXTraderEA.mq5"
if (!(Test-Path -LiteralPath $workerPath) -or !(Test-Path -LiteralPath $eaPath)) { throw "Worker kit is incomplete." }
if (!(Get-Command py -ErrorAction SilentlyContinue)) { throw "Python 3.11+ is required. Install from https://www.python.org/downloads/windows/." }
& py -3 -c "import sys; raise SystemExit(0 if sys.version_info >= (3,11) else 1)"
if ($LASTEXITCODE -ne 0) { throw "Python 3.11 or newer was not found through py -3." }
$inputUrl = (Read-Host "Public bridge URL (e.g. https://destinedfx-managed-bridge.onrender.com)").Trim().TrimEnd('/')
try { $uri = [System.Uri]::new($inputUrl, [System.UriKind]::Absolute) } catch { throw "Bridge URL is invalid." }
if ($uri.Scheme -ne "https" -or !$uri.Host -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or ($uri.AbsolutePath -ne "/" -and $uri.AbsolutePath -ne "")) { throw "Use a public HTTPS origin only." }
if ($uri.Host -match '(^localhost$|\.example$|\.invalid$|\.test$|^your-|^placeholder|example)') { throw "Placeholder bridge URL is not allowed." }
$bridgeUrl = "$($uri.Scheme)://$($uri.Authority)"
$mt5Install = (Read-Host "Folder of the genuine broker MT5 desktop installation").Trim().Trim('"')
if (!(Test-Path -LiteralPath $mt5Install -PathType Container)) { throw "MT5 installation folder not found." }
$terminal = Join-Path $mt5Install "terminal64.exe"
$metaeditor = Join-Path $mt5Install "metaeditor64.exe"
if (!(Test-Path -LiteralPath $terminal -PathType Leaf)) { throw "terminal64.exe not found." }
if (!(Test-Path -LiteralPath $metaeditor -PathType Leaf)) { throw "metaeditor64.exe not found." }
$workerId = (Read-Host "Stable unique worker ID (letters, digits, underscore, hyphen)").Trim()
if ($workerId -notmatch '^[A-Za-z0-9_-]{1,128}$') { throw "Worker ID contains unsupported characters." }
$defaultDataRoot = Join-Path $env:USERPROFILE "DestinedFX\accounts"
$dataRootInput = Read-Host "Private terminal-data root [$defaultDataRoot]"
if ([string]::IsNullOrWhiteSpace($dataRootInput)) { $dataRootInput = $defaultDataRoot }
$dataRoot = [System.IO.Path]::GetFullPath($dataRootInput.Trim().Trim('"'))
New-Item -ItemType Directory -Force -Path $dataRoot | Out-Null
$secureToken = Read-Host "Paste generated Render WORKER_TOKEN (input is hidden)" -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
try { $workerToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
if ($workerToken.Length -lt 32 -or $workerToken.Length -gt 512) { throw "Worker token length invalid; copy WORKER_TOKEN from private Render environment settings." }
$configPath = Join-Path $root "worker.toml"
$toml = @"
control_plane_url = $(TomlString $bridgeUrl)
data_root = $(TomlString $dataRoot)
mt5_install = $(TomlString $mt5Install)
terminal_exe = $(TomlString $terminal)
metaeditor_exe = $(TomlString $metaeditor)
ea_source = $(TomlString $eaPath)
compiled_ea = ""
symbol = "EURUSD"
poll_seconds = 5
auth_timeout_seconds = 300
max_restart_retries = 3
restart_backoff_seconds = 30
"@
[System.IO.File]::WriteAllText($configPath, $toml, [System.Text.UTF8Encoding]::new($false))
Write-Host ""
Write-Host "In the managed MT5 terminal, add this URL under Tools > Options > Expert Advisors > Allow WebRequest for listed URL:"
Write-Host "  $bridgeUrl"
Write-Host "Start with a dedicated DEMO account."
$confirm = Read-Host "Type START WORKER to begin"
if ($confirm -cne "START WORKER") { Write-Host "Cancelled; worker.toml was written without secrets."; exit 0 }
$env:DESTINEDFX_WORKER_TOKEN = $workerToken
$env:DESTINEDFX_WORKER_ID = $workerId
try {
    Write-Host "Worker running. Keep this PowerShell window open; Ctrl+C stops the worker."
    & py -3 $workerPath --config $configPath
    if ($LASTEXITCODE -ne 0) { throw "Worker exited with code $LASTEXITCODE; read the sanitized error above." }
} finally {
    Remove-Item Env:DESTINEDFX_WORKER_TOKEN -ErrorAction SilentlyContinue
    Remove-Item Env:DESTINEDFX_WORKER_ID -ErrorAction SilentlyContinue
    $workerToken = $null
    $secureToken.Dispose()
}

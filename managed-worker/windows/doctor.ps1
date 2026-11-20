#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$Config = (Join-Path $PSScriptRoot '..\worker.toml'),
    [ValidateSet('FBS','EXNESS','HFM','DERIV')][string[]]$Broker,
    [switch]$PythonCheck,
    [string]$Login,
    [string]$Server
)
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'The MT5 doctor runs on Windows only.' }
$python = Get-Command py.exe -ErrorAction SilentlyContinue
if (-not $python) { throw 'Python 3.11+ was not found through py.exe.' }
$secretFile = Join-Path $env:ProgramData 'DestinedFX\config\owner-secrets.dpapi'
if (Test-Path $secretFile -PathType Leaf) {
    $secure = ConvertTo-SecureString ([IO.File]::ReadAllText($secretFile).Trim())
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $json = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr); $secrets = $json | ConvertFrom-Json }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr); $secure.Dispose(); $json = $null }
    $env:CF_ACCESS_CLIENT_ID = [string]$secrets.cf_access_client_id
    $env:CF_ACCESS_CLIENT_SECRET = [string]$secrets.cf_access_client_secret
    $secrets = $null
}
$args = @('-3', (Join-Path $PSScriptRoot '..\doctor.py'), '--config', $Config)
if ($Broker) { foreach ($item in $Broker) { $args += @('--broker', $item) } }
if ($PythonCheck) { $args += '--python-check' }
if ($Login) { $args += @('--login', $Login) }
if ($Server) { $args += @('--server', $Server) }
if ($PythonCheck -and $Login -and $Server) { $args += '--password-stdin' }
try { & $python.Source @args; $code = $LASTEXITCODE }
finally { $env:CF_ACCESS_CLIENT_ID = $null; $env:CF_ACCESS_CLIENT_SECRET = $null }
exit $code

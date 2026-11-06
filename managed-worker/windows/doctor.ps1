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
$args = @('-3', (Join-Path $PSScriptRoot '..\doctor.py'), '--config', $Config)
if ($Broker) { foreach ($item in $Broker) { $args += @('--broker', $item) } }
if ($PythonCheck) { $args += '--python-check' }
if ($Login) { $args += @('--login', $Login) }
if ($Server) { $args += @('--server', $Server) }
if ($PythonCheck -and $Login -and $Server) { $args += '--password-stdin' }
& $python.Source @args
exit $LASTEXITCODE

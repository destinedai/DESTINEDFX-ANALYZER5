#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Backup,
    [Parameter(Mandatory=$true)][string]$TargetRoot,
    [switch]$Replace
)
$ErrorActionPreference='Stop'
$backupPath=(Resolve-Path $Backup).Path
New-Item -ItemType Directory -Force -Path $TargetRoot | Out-Null
$target=(Resolve-Path $TargetRoot).Path
$temporary=Join-Path ([IO.Path]::GetTempPath()) "destinedfx-restore-$([guid]::NewGuid().ToString('N'))"
$archive=Join-Path $temporary 'backup.zip'
New-Item -ItemType Directory -Force -Path $temporary | Out-Null
try {
    $secure=ConvertTo-SecureString ([IO.File]::ReadAllText($backupPath))
    $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        $base64=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
        [IO.File]::WriteAllBytes($archive,[Convert]::FromBase64String($base64))
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
        $secure.Dispose()
    }
    Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $temporary 'snapshot') -Force
    $snapshotRoot=Join-Path $temporary 'snapshot'
    $secretCipher=Get-Content (Join-Path $snapshotRoot 'owner-secrets.dpapi') -Raw
    $secretSecure=ConvertTo-SecureString $secretCipher.Trim()
    $secretPtr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secretSecure)
    try {
        $secretJson=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($secretPtr)
        $secretBundle=$secretJson|ConvertFrom-Json
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secretPtr)
        $secretSecure.Dispose()
        $secretJson=$null
    }
    if(-not $secretBundle.fernet_key -or -not $secretBundle.worker_bootstrap_token){throw 'DPAPI secret bundle is incomplete.'}
    try {
        if(([Convert]::FromBase64String([string]$secretBundle.fernet_key)).Length -ne 32){throw 'Fernet key length is invalid.'}
    } catch { throw 'Fernet key format is invalid.' }
    $backupWorkerId=(Get-Content (Join-Path $snapshotRoot 'worker-id.txt') -Raw).Trim()
    if($secretBundle.worker_id -and $secretBundle.worker_id -ne $backupWorkerId){throw 'DPAPI worker identity does not match the backup.'}
    $python=Join-Path (Join-Path $env:ProgramData 'DestinedFX') 'venv\Scripts\python.exe'
    $script=Join-Path $PSScriptRoot 'restore-bridge.py'
    $arguments=@($script,$snapshotRoot,$target)
    if($Replace){$arguments += '--replace'}
    & $python @arguments
    if($LASTEXITCODE -ne 0){throw 'Validated restore was rejected.'}
} finally {
    Remove-Item -LiteralPath $temporary -Recurse -Force -ErrorAction SilentlyContinue
}

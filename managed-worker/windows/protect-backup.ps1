#requires -Version 5.1
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Snapshot)
$ErrorActionPreference='Stop'
$snapshotPath=(Resolve-Path $Snapshot).Path
$archive="$snapshotPath.zip"
$protected="$snapshotPath.dpapi"
$temporary="$protected.$([guid]::NewGuid().ToString('N')).tmp"
try {
    Compress-Archive -Path (Join-Path $snapshotPath '*') -DestinationPath $archive -CompressionLevel Optimal -Force
    $base64=[Convert]::ToBase64String([IO.File]::ReadAllBytes($archive))
    $secure=ConvertTo-SecureString $base64 -AsPlainText -Force
    $cipher=ConvertFrom-SecureString $secure
    [IO.File]::WriteAllText($temporary,$cipher,[Text.UTF8Encoding]::new($false))
    $check=ConvertTo-SecureString ([IO.File]::ReadAllText($temporary))
    if(-not $check){throw 'DPAPI protected backup validation failed.'}
    Move-Item -LiteralPath $temporary -Destination $protected -Force
    Remove-Item -LiteralPath $archive -Force
    Remove-Item -LiteralPath $snapshotPath -Recurse -Force
    Write-Output "Protected backup: $protected"
} catch {
    Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
    throw
} finally {
    if($secure){$secure.Dispose()}
    if($check){$check.Dispose()}
}

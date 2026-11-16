#requires -Version 5.1
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Snapshot)
$ErrorActionPreference='Stop'
$snapshotPath=(Resolve-Path $Snapshot).Path
$archive="$snapshotPath.zip"
$protected="$snapshotPath.dpapi"
$temporary="$protected.$([guid]::NewGuid().ToString('N')).tmp"
try {
    & icacls.exe $snapshotPath /inheritance:r /grant:r "$env:USERDOMAIN\$env:USERNAME`:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Could not restrict backup staging ACLs.'}
    Compress-Archive -Path (Join-Path $snapshotPath '*') -DestinationPath $archive -CompressionLevel Optimal -Force
    $base64=[Convert]::ToBase64String([IO.File]::ReadAllBytes($archive))
    $secure=ConvertTo-SecureString $base64 -AsPlainText -Force
    $cipher=ConvertFrom-SecureString $secure
    [IO.File]::WriteAllText($temporary,$cipher,[Text.UTF8Encoding]::new($false))
    & icacls.exe $temporary /inheritance:r /grant:r "$env:USERDOMAIN\$env:USERNAME:F" '*S-1-5-18:F' '*S-1-5-32-544:F' | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Could not restrict protected backup ACLs.'}
    $check=ConvertTo-SecureString ([IO.File]::ReadAllText($temporary))
    $checkPtr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($check)
    try {
        $roundTrip=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($checkPtr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($checkPtr)
    }
    if($roundTrip -ne $base64){throw 'DPAPI protected backup round-trip validation failed.'}
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

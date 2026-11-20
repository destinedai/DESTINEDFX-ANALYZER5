#requires -Version 5.1
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Snapshot)
$ErrorActionPreference='Stop'

function Set-PrivateAcl {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string]$UserSid,
        [switch]$Directory,
        [switch]$Recursive
    )
    $right = if($Directory) { '(OI)(CI)F' } else { 'F' }
    $grants = @(
        "*${UserSid}:$right",
        "*S-1-5-18:$right",
        "*S-1-5-32-544:$right"
    )
    $arguments = @($Path, '/inheritance:r')
    if($Recursive) { $arguments += @('/T', '/C') }
    $arguments += '/grant:r'
    $arguments += $grants
    & icacls.exe @arguments | Out-Null
    if($LASTEXITCODE -ne 0) { throw "Could not restrict backup ACLs for $Path." }
}

$snapshotPath=(Resolve-Path -LiteralPath $Snapshot).Path
if(-not (Test-Path -LiteralPath $snapshotPath -PathType Container)) {
    throw 'Backup snapshot must be an existing directory.'
}
$parent=Split-Path -Parent $snapshotPath
$protected="$snapshotPath.dpapi"
if(Test-Path -LiteralPath $protected) {
    throw 'A protected backup already exists at the destination; refusing to overwrite it.'
}
$workDirectory=Join-Path $parent ".destinedfx-protect-$([guid]::NewGuid().ToString('N'))"
$archive=Join-Path $workDirectory 'backup.zip'
$temporary=Join-Path $workDirectory 'backup.dpapi.tmp'
$secure=$null
$check=$null
$base64=$null
$roundTrip=$null

try {
    $currentSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    if([string]::IsNullOrWhiteSpace($currentSid)) { throw 'The current Windows user SID is unavailable.' }

    New-Item -ItemType Directory -Path $workDirectory -Force | Out-Null
    Set-PrivateAcl -Path $workDirectory -UserSid $currentSid -Directory

    # Restrict every existing staging file before creating or reading any backup archive.
    Set-PrivateAcl -Path $snapshotPath -UserSid $currentSid -Recursive

    Compress-Archive -Path (Join-Path $snapshotPath '*') -DestinationPath $archive -CompressionLevel Optimal -Force
    Set-PrivateAcl -Path $archive -UserSid $currentSid

    $base64=[Convert]::ToBase64String([IO.File]::ReadAllBytes($archive))
    $secure=ConvertTo-SecureString $base64 -AsPlainText -Force
    $cipher=ConvertFrom-SecureString $secure
    [IO.File]::WriteAllText($temporary,$cipher,[Text.UTF8Encoding]::new($false))
    Set-PrivateAcl -Path $temporary -UserSid $currentSid

    $check=ConvertTo-SecureString ([IO.File]::ReadAllText($temporary))
    $checkPtr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($check)
    try {
        $roundTrip=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($checkPtr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($checkPtr)
    }
    if($roundTrip -ne $base64) { throw 'DPAPI protected backup round-trip validation failed.' }

    # The destination is new and on the same volume, so rename preserves the
    # already-restricted file DACL without exposing a plaintext intermediate.
    Move-Item -LiteralPath $temporary -Destination $protected
    Remove-Item -LiteralPath $snapshotPath -Recurse -Force
    Write-Output "Protected backup: $protected"
} catch {
    # Keep the original staging snapshot available for owner recovery/review.
    # If the protected rename already succeeded, retain that valid DPAPI backup too.
    throw
} finally {
    Remove-Item -LiteralPath $workDirectory -Recurse -Force -ErrorAction SilentlyContinue
    if($secure) { $secure.Dispose() }
    if($check) { $check.Dispose() }
    $base64=$null
    $roundTrip=$null
}
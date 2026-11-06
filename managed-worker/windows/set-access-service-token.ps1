#requires -Version 5.1
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'This owner secret utility runs on Windows only.' }
$secretFile = Join-Path $env:ProgramData 'DestinedFX\config\owner-secrets.dpapi'
if (-not (Test-Path $secretFile -PathType Leaf)) { throw 'The existing DPAPI owner secret bundle was not found. Install the owner stack first.' }
$clientId = Read-Host 'Cloudflare Access worker/doctor Client ID (blank disables Access headers)'
$clientSecret = ''
if ($clientId) {
    $secureInput = Read-Host 'Cloudflare Access worker/doctor Client Secret' -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureInput)
    try { $clientSecret = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr); $secureInput.Dispose() }
    if (-not $clientSecret) { throw 'A Client Secret is required when a Client ID is provided.' }
}
$encrypted = [IO.File]::ReadAllText($secretFile).Trim()
$secureBundle = ConvertTo-SecureString $encrypted
$bundlePtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureBundle)
try {
    $json = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bundlePtr)
    $secrets = $json | ConvertFrom-Json
    if (-not $secrets.fernet_key -or -not $secrets.worker_token) { throw 'The DPAPI owner secret bundle is incomplete.' }
    if ($null -eq $secrets.PSObject.Properties['cf_access_client_id']) { $secrets | Add-Member -NotePropertyName cf_access_client_id -NotePropertyValue '' }
    if ($null -eq $secrets.PSObject.Properties['cf_access_client_secret']) { $secrets | Add-Member -NotePropertyName cf_access_client_secret -NotePropertyValue '' }
    $secrets.cf_access_client_id = $clientId
    $secrets.cf_access_client_secret = $clientSecret
    $updatedJson = $secrets | ConvertTo-Json -Compress
    $updatedSecure = ConvertTo-SecureString $updatedJson -AsPlainText -Force
    $updatedEncrypted = ConvertFrom-SecureString $updatedSecure
    $temporary = "$secretFile.tmp"
    [IO.File]::WriteAllText($temporary, $updatedEncrypted, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $secretFile -Force
    $updatedSecure.Dispose()
    Write-Host 'Worker/doctor Access token updated in the DPAPI bundle. Restart the DestinedFX owner-stack task.'
}
finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bundlePtr)
    $secureBundle.Dispose()
    $json = $null; $secrets = $null; $updatedJson = $null; $updatedEncrypted = $null
    $clientSecret = $null
}

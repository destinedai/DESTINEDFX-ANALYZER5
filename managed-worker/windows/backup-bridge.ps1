$ErrorActionPreference='Stop'
$base=Join-Path $env:ProgramData 'DestinedFX'
$python=Join-Path $base 'venv\Scripts\python.exe'
$script=Join-Path $PSScriptRoot 'backup-bridge.py'
if(-not(Test-Path $python -PathType Leaf) -or -not(Test-Path $script -PathType Leaf)){throw 'Owner bridge backup prerequisites are missing.'}
& $python $script
if($LASTEXITCODE -ne 0){throw 'Bridge database backup failed; investigate before relying on recovery.'}
$snapshot=(Get-ChildItem (Join-Path $base 'backups') -Directory -Filter 'backup-*' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1)
if(-not $snapshot){throw 'Verified backup snapshot was not created.'}
$protector=Join-Path $PSScriptRoot 'protect-backup.ps1'
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $protector -Snapshot $snapshot.FullName
if($LASTEXITCODE -ne 0){throw 'DPAPI backup protection failed; plaintext snapshot was retained for operator review.'}
Get-ChildItem (Join-Path $base 'backups') -File -Filter 'backup-*.dpapi' |
    Sort-Object LastWriteTime -Descending | Select-Object -Skip 14 |
    Remove-Item -Force

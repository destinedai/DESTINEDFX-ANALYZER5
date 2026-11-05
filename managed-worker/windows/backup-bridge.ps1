$ErrorActionPreference='Stop'
$base=Join-Path $env:ProgramData 'DestinedFX'
$python=Join-Path $base 'venv\Scripts\python.exe'
$script=Join-Path $PSScriptRoot 'backup-bridge.py'
if(-not(Test-Path $python -PathType Leaf) -or -not(Test-Path $script -PathType Leaf)){throw 'Owner bridge backup prerequisites are missing.'}
& $python $script
if($LASTEXITCODE -ne 0){throw 'Bridge database backup failed; investigate before relying on recovery.'}

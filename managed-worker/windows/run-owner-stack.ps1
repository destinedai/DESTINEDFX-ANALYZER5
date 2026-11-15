[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ProjectRoot)
$ErrorActionPreference='Stop'
$project=(Resolve-Path $ProjectRoot).Path
$base=Join-Path $env:ProgramData 'DestinedFX'
$configDir=Join-Path $base 'config'
$bridgeDir=Join-Path $project 'bridge'
$workerDir=Join-Path $project 'managed-worker'
$python=Join-Path $base 'venv\Scripts\python.exe'
$secretFile=Join-Path $configDir 'owner-secrets.dpapi'
$workerConfig=Join-Path $configDir 'worker.toml'
$workerIdFile=Join-Path $configDir 'worker-id.txt'
$logDir=Join-Path $base 'logs'
foreach($p in @($python,$secretFile,$workerConfig,$workerIdFile,(Join-Path $bridgeDir 'app.py'),(Join-Path $workerDir 'worker.py'))){if(-not(Test-Path $p -PathType Leaf)){throw "Required owner-stack file missing: $p"}}
Get-ChildItem -Path $logDir -File -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-30) } | Remove-Item -Force -ErrorAction SilentlyContinue
$bridgeOrigin = & $python -c 'import sys,tomllib; from urllib.parse import urlparse; raw=tomllib.load(open(sys.argv[1],"rb"))["control_plane_url"].strip().rstrip("/"); u=urlparse(raw); host=(u.hostname or "").lower(); assert u.scheme=="https" and host.endswith(".ts.net") and not u.username and not u.password and not u.query and not u.fragment and u.path in ("","/") and (u.port is None or 1<=u.port<=65535); print(raw)' $workerConfig
if($LASTEXITCODE -ne 0 -or -not $bridgeOrigin){throw 'Persistent worker control_plane_url is not a valid owner HTTPS origin; refusing to start the bridge or worker.'}
$secure=ConvertTo-SecureString ([IO.File]::ReadAllText($secretFile).Trim())
$ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {$json=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr);$secrets=$json|ConvertFrom-Json}
finally {[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr);$secure.Dispose();$json=$null}
if(-not $secrets.fernet_key -or -not $secrets.worker_bootstrap_token){throw 'DPAPI owner secrets are incomplete.'}
$env:CF_ACCESS_CLIENT_ID=[string]$secrets.cf_access_client_id
$env:CF_ACCESS_CLIENT_SECRET=[string]$secrets.cf_access_client_secret
$workerId=[IO.File]::ReadAllText($workerIdFile).Trim()
if($workerId -notmatch '^[A-Za-z0-9_-]{1,128}$'){throw 'Stable worker ID is invalid.'}
$env:BRIDGE_DB_PATH=Join-Path (Join-Path $base 'data') 'bridge.sqlite3'
$env:BRIDGE_BASE_URL=$bridgeOrigin.Trim()
$env:ALLOW_LOOPBACK_HTTP='true'
$env:CREDENTIALS_FERNET_KEY=$secrets.fernet_key
$env:WORKER_BOOTSTRAP_TOKEN=$secrets.worker_bootstrap_token
$env:DESTINEDFX_WORKER_ID=$workerId
$env:PYTHONUNBUFFERED='1'
$bridge=$null;$worker=$null
function Start-ManagedChild([string]$Name,[string]$WorkingDirectory,[string[]]$Arguments){
    $stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
    $out=Join-Path $logDir "$Name-$stamp.out.log"
    $err=Join-Path $logDir "$Name-$stamp.err.log"
    Start-Process -FilePath $python -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
}
function Save-OwnerSecrets([object]$Bundle) {
    $json=$Bundle | ConvertTo-Json -Compress
    $secureBundle=ConvertTo-SecureString $json -AsPlainText -Force
    $encryptedBundle=ConvertFrom-SecureString $secureBundle
    $temporary="$secretFile.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        [IO.File]::WriteAllText($temporary,$encryptedBundle,[Text.UTF8Encoding]::new($false))
        $verified=ConvertTo-SecureString ([IO.File]::ReadAllText($temporary))
        if(-not $verified){throw 'DPAPI validation failed.'}
        Move-Item -LiteralPath $temporary -Destination $secretFile -Force
    } catch {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        throw
    } finally { $secureBundle.Dispose() }
}
function Ensure-RegisteredWorker([object]$Bundle,[string]$StableWorkerId) {
    $local='http://127.0.0.1:8000'
    $operator=@{Authorization="Bearer $($Bundle.worker_bootstrap_token)"}
    $current=[string]$Bundle.worker_token
    if($current){
        try {
            $identity=Invoke-RestMethod -Uri "$local/v1/worker/identity" -Headers @{Authorization="Bearer $current"} -TimeoutSec 8
            $now=[DateTimeOffset]::Now.ToUnixTimeSeconds()
            if($identity.worker_id -eq $StableWorkerId -and $identity.status -eq 'ACTIVE' -and [double]$identity.expires_at -gt ($now+3600)){return $current}
        } catch {}
    }
    $known=$null
    try { $known=Invoke-RestMethod -Uri "$local/v1/operator/workers/$StableWorkerId" -Headers $operator -TimeoutSec 8 } catch {
        if($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -ne 404){throw 'Could not inspect worker registration.'}
    }
    if($known){
        $issued=Invoke-RestMethod -Method Post -Uri "$local/v1/operator/workers/$StableWorkerId/rotate" -Headers $operator -ContentType 'application/json' -Body '{"expires_in_seconds":86400}' -TimeoutSec 8
    } else {
        $issued=Invoke-RestMethod -Method Post -Uri "$local/v1/operator/workers" -Headers $operator -ContentType 'application/json' -Body (@{worker_id=$StableWorkerId;expires_in_seconds=86400}|ConvertTo-Json -Compress) -TimeoutSec 8
    }
    if(-not $issued.credential){throw 'Bridge did not return a worker credential.'}
    $Bundle.worker_token=[string]$issued.credential
    Save-OwnerSecrets $Bundle
    return $Bundle.worker_token
}
try {
    $bridge=Start-ManagedChild 'bridge' $bridgeDir @('-m','uvicorn','app:app','--host','127.0.0.1','--port','8000','--no-access-log')
    $healthy=$false
    for($attempt=0;$attempt -lt 24;$attempt++){
        Start-Sleep -Seconds 2
        try {
            $ready=Invoke-RestMethod -Uri 'http://127.0.0.1:8000/health/config' -TimeoutSec 5
            if($ready.database -eq 'ok' -and $ready.managed_execution -eq 'configured'){$healthy=$true;break}
        } catch {}
    }
    if(-not $healthy){throw 'Bridge did not reach durable local readiness.'}
    $workerToken=Ensure-RegisteredWorker $secrets $workerId
    $env:DESTINEDFX_WORKER_TOKEN=$workerToken
    $worker=Start-ManagedChild 'worker' $workerDir @('"' + (Join-Path $workerDir 'worker.py') + '"','--config','"' + $workerConfig + '"')
    while($true){
        Start-Sleep -Seconds 5
        if($bridge.HasExited){
            Start-Sleep -Seconds 5
            $bridge=Start-ManagedChild 'bridge' $bridgeDir @('-m','uvicorn','app:app','--host','127.0.0.1','--port','8000','--no-access-log')
        }
        if($worker.HasExited){
            Start-Sleep -Seconds 5
            $worker=Start-ManagedChild 'worker' $workerDir @('"' + (Join-Path $workerDir 'worker.py') + '"','--config','"' + $workerConfig + '"')
        }
    }
} finally {
    foreach($proc in @($worker,$bridge)){if($proc -and -not $proc.HasExited){Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue}}
    foreach($name in @('BRIDGE_DB_PATH','BRIDGE_BASE_URL','ALLOW_LOOPBACK_HTTP','CREDENTIALS_FERNET_KEY','WORKER_BOOTSTRAP_TOKEN','DESTINEDFX_WORKER_TOKEN','DESTINEDFX_WORKER_ID','CF_ACCESS_CLIENT_ID','CF_ACCESS_CLIENT_SECRET','PYTHONUNBUFFERED')){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}
    $env:CREDENTIALS_FERNET_KEY=$null;$env:WORKER_BOOTSTRAP_TOKEN=$null;$env:DESTINEDFX_WORKER_TOKEN=$null
}

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
$mutex=$null
$mutexOwned=$false
$workerId=$null
try {
    $workerId=[IO.File]::ReadAllText($workerIdFile).Trim()
    if($workerId -notmatch '^[A-Za-z0-9_-]{1,128}$'){throw 'Stable worker ID is invalid.'}
    $mutex=[Threading.Mutex]::new($false,"Local\DestinedFX-Worker-$workerId")
    if(-not $mutex.WaitOne(0)){throw 'Another owner-stack supervisor already owns this worker identity.'}
    $mutexOwned=$true
    $env:BRIDGE_DB_PATH=Join-Path (Join-Path $base 'data') 'bridge.sqlite3'
    $env:BRIDGE_BASE_URL=$bridgeOrigin.Trim()
    $env:ALLOW_LOOPBACK_HTTP='true'
    $env:BRIDGE_HOST_MODE='windows-owner'
    $env:BRIDGE_HOST_PROFILE='PERSISTENT'
    $env:EXECUTION_STORAGE_DURABLE='true'
    $env:DESTINEDFX_WORKER_ID=$workerId
    $env:PYTHONUNBUFFERED='1'
} catch {
    if($mutexOwned){$mutex.ReleaseMutex()}
    if($mutex){$mutex.Dispose()}
    throw
}
$bridge=$null;$worker=$null
function Start-ManagedChild([string]$Name,[string]$WorkingDirectory,[string[]]$Arguments){
    $stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
    $out=Join-Path $logDir "$Name-$stamp.out.log"
    $err=Join-Path $logDir "$Name-$stamp.err.log"
    Start-Process -FilePath $python -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
}
function Read-DpapiBundle([string]$Path) {
    $encrypted=[IO.File]::ReadAllText($Path).Trim()
    if(-not $encrypted){throw 'DPAPI owner bundle is empty.'}
    $secure=ConvertTo-SecureString $encrypted
    $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        $plain=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
        $bundle=$plain|ConvertFrom-Json
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
        $secure.Dispose()
        $plain=$null
    }
    if(-not $bundle.fernet_key -or -not $bundle.worker_bootstrap_token){throw 'DPAPI owner secrets are incomplete.'}
    if($null -eq $bundle.PSObject.Properties['worker_token']){$bundle|Add-Member -NotePropertyName worker_token -NotePropertyValue ''}
    if($bundle.worker_id -and $bundle.worker_id -ne $workerId){throw 'DPAPI worker identity does not match the stable worker ID.'}
    return $bundle
}
function Http-StatusCode([object]$ErrorRecord) {
    try { if($ErrorRecord.Exception.Response){return [int]$ErrorRecord.Exception.Response.StatusCode} } catch {}
    return 0
}
function Save-OwnerSecrets([object]$Bundle) {
    if($null -eq $Bundle.PSObject.Properties['worker_id']){$Bundle|Add-Member -NotePropertyName worker_id -NotePropertyValue $workerId}
    if($Bundle.worker_id -ne $workerId){throw 'Refusing to persist a different worker identity.'}
    $json=$Bundle | ConvertTo-Json -Compress
    $secureBundle=ConvertTo-SecureString $json -AsPlainText -Force
    $encryptedBundle=ConvertFrom-SecureString $secureBundle
    $temporary="$secretFile.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        [IO.File]::WriteAllText($temporary,$encryptedBundle,[Text.UTF8Encoding]::new($false))
        $verified=ConvertTo-SecureString ([IO.File]::ReadAllText($temporary))
        $verifyPtr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($verified)
        try {
            $verifyJson=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($verifyPtr)
            $verifyBundle=$verifyJson|ConvertFrom-Json
        } finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($verifyPtr)
            $verified.Dispose()
            $verifyJson=$null
        }
        if(-not $verifyBundle.fernet_key -or -not $verifyBundle.worker_bootstrap_token -or $verifyBundle.worker_id -ne $workerId){throw 'DPAPI semantic validation failed.'}
        Move-Item -LiteralPath $temporary -Destination $secretFile -Force
    } catch {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        throw
    } finally { $secureBundle.Dispose() }
}
function Ensure-RegisteredWorker {
    param([object]$Bundle,[string]$StableWorkerId,[int]$RenewalMarginSeconds=21600)
    $local='http://127.0.0.1:8000'
    $operator=@{Authorization="Bearer $($Bundle.worker_bootstrap_token)"}
    $current=[string]$Bundle.worker_token
    if($current){
        try {
            $identity=Invoke-RestMethod -Uri "$local/v1/worker/identity" -Headers @{Authorization="Bearer $current"} -TimeoutSec 8
            $now=[DateTimeOffset]::Now.ToUnixTimeSeconds()
            if($identity.worker_id -ne $StableWorkerId){throw 'Registered worker identity does not match the stable worker ID.'}
            if($identity.status -eq 'REVOKED'){throw 'Worker identity is revoked; explicit operator recovery is required.'}
            if($identity.status -eq 'ACTIVE' -and [double]$identity.expires_at -gt ($now+$RenewalMarginSeconds)){return $current}
        } catch {
            if($_.Exception.Message -match 'stable worker ID|revoked'){throw}
            $status=Http-StatusCode $_
            if($status -notin @(401,403)){if($status -eq 0 -or $status -ge 500){throw 'Worker identity check could not reach the Bridge.'}}
        }
    }
    $known=$null
    try { $known=Invoke-RestMethod -Uri "$local/v1/operator/workers/$StableWorkerId" -Headers $operator -TimeoutSec 8 } catch {
        $status=Http-StatusCode $_
        if($status -eq 404){$known=$null}
        elseif($status -in @(401,403)){throw 'Worker bootstrap authorization was rejected.'}
        else {throw 'Could not inspect worker registration because the Bridge is unavailable.'}
    }
    if($known){
        if($known.status -eq 'REVOKED'){throw 'Worker identity is revoked; explicit operator recovery is required.'}
        $issued=Invoke-RestMethod -Method Post -Uri "$local/v1/operator/workers/$StableWorkerId/rotate" -Headers $operator -ContentType 'application/json' -Body '{"expires_in_seconds":86400}' -TimeoutSec 8
    } else {
        $issued=Invoke-RestMethod -Method Post -Uri "$local/v1/operator/workers" -Headers $operator -ContentType 'application/json' -Body (@{worker_id=$StableWorkerId;expires_in_seconds=86400}|ConvertTo-Json -Compress) -TimeoutSec 8
    }
    if(-not $issued.credential){throw 'Bridge did not return a worker credential.'}
    $Bundle.worker_token=[string]$issued.credential
    Save-OwnerSecrets $Bundle
    $handshake=Invoke-RestMethod -Uri "$local/v1/worker/identity" -Headers @{Authorization="Bearer $($Bundle.worker_token)"} -TimeoutSec 8
    if($handshake.worker_id -ne $StableWorkerId -or $handshake.status -ne 'ACTIVE'){throw 'Worker credential handshake did not verify the stable identity.'}
    return $Bundle.worker_token
}
function Wait-BridgeConfiguration {
    for($attempt=0;$attempt -lt 24;$attempt++){
        Start-Sleep -Seconds 2
        try {
            $ready=Invoke-RestMethod -Uri 'http://127.0.0.1:8000/health/config' -TimeoutSec 5
            if($ready.database -eq 'ok' -and $ready.managed_execution -eq 'configured'){return}
        } catch {}
    }
    throw 'Bridge did not reach durable local readiness.'
}
try {
    $secrets=Read-DpapiBundle $secretFile
    $stepUpPublicKey=[string]$secrets.management_step_up_public_key
    if (-not [string]::IsNullOrWhiteSpace($stepUpPublicKey)) {
        $env:MANAGEMENT_STEP_UP_PUBLIC_KEY=$stepUpPublicKey
        $stepUpIssuer=[string]$secrets.management_step_up_issuer
        $stepUpAudience=[string]$secrets.management_step_up_audience
        if ([string]::IsNullOrWhiteSpace($stepUpIssuer)) { $stepUpIssuer='destinedfx-management' }
        if ([string]::IsNullOrWhiteSpace($stepUpAudience)) { $stepUpAudience='destinedfx-bridge' }
        $env:MANAGEMENT_STEP_UP_ISSUER=$stepUpIssuer
        $env:MANAGEMENT_STEP_UP_AUDIENCE=$stepUpAudience
        $keyCheck=& $python -c 'import os; from cryptography.hazmat.primitives import serialization; from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey; key=serialization.load_pem_public_key(os.environ["MANAGEMENT_STEP_UP_PUBLIC_KEY"].encode()); assert isinstance(key,Ed25519PublicKey); print("ED25519_PUBLIC_KEY_VALID")'
        if ($LASTEXITCODE -ne 0 -or $keyCheck -notmatch 'ED25519_PUBLIC_KEY_VALID') {
            throw 'DPAPI management step-up verification key is invalid; refusing to start an incorrectly configured Bridge.'
        }
    } else {
        Remove-Item Env:MANAGEMENT_STEP_UP_PUBLIC_KEY -ErrorAction SilentlyContinue
        Remove-Item Env:MANAGEMENT_STEP_UP_ISSUER -ErrorAction SilentlyContinue
        Remove-Item Env:MANAGEMENT_STEP_UP_AUDIENCE -ErrorAction SilentlyContinue
        Write-Warning 'Management step-up public key is not configured. Account identity registration and LIVE authorization will remain blocked; configure it with -ManagementStepUpPublicKeyPath during the owner-stack installer/repair.'
    }
    $env:CF_ACCESS_CLIENT_ID=[string]$secrets.cf_access_client_id
    $env:CF_ACCESS_CLIENT_SECRET=[string]$secrets.cf_access_client_secret
    $env:CREDENTIALS_FERNET_KEY=$secrets.fernet_key
    $env:WORKER_BOOTSTRAP_TOKEN=$secrets.worker_bootstrap_token
    $bridge=Start-ManagedChild 'bridge' $bridgeDir @('-m','uvicorn','app:app','--host','127.0.0.1','--port','8000','--no-access-log')
    Wait-BridgeConfiguration
    $workerToken=Ensure-RegisteredWorker $secrets $workerId
    $env:DESTINEDFX_WORKER_TOKEN=$workerToken
    $worker=Start-ManagedChild 'worker' $workerDir @('"' + (Join-Path $workerDir 'worker.py') + '"','--config','"' + $workerConfig + '"')
    $renewalCheckValue=300
    $renewalMarginValue=21600
    if($env:DESTINEDFX_WORKER_RENEWAL_CHECK_SECONDS){$renewalCheckValue=[int]$env:DESTINEDFX_WORKER_RENEWAL_CHECK_SECONDS}
    if($env:DESTINEDFX_WORKER_RENEWAL_MARGIN_SECONDS){$renewalMarginValue=[int]$env:DESTINEDFX_WORKER_RENEWAL_MARGIN_SECONDS}
    $renewalInterval=[Math]::Max(60,[Math]::Min(3600,$renewalCheckValue))
    $renewalMargin=[Math]::Max(3600,[Math]::Min(21600,$renewalMarginValue))
    $nextRenewal=[DateTime]::UtcNow.AddSeconds($renewalInterval)
    $renewalFailures=0
    while($true){
        Start-Sleep -Seconds 5
        $revalidate=$false
        if($bridge.HasExited){
            Start-Sleep -Seconds 5
            $bridge=Start-ManagedChild 'bridge' $bridgeDir @('-m','uvicorn','app:app','--host','127.0.0.1','--port','8000','--no-access-log')
            Wait-BridgeConfiguration
            $revalidate=$true
        }
        if($worker.HasExited){
            $revalidate=$true
        }
        if($revalidate -or [DateTime]::UtcNow -ge $nextRenewal){
            try {
                $newToken=Ensure-RegisteredWorker $secrets $workerId -RenewalMarginSeconds $renewalMargin
                $renewalFailures=0
            } catch {
                if($_.Exception.Message -match 'revoked|bootstrap authorization|stable worker ID'){throw}
                $renewalFailures=[Math]::Min(8,$renewalFailures+1)
                $retrySeconds=[Math]::Min(300,[int][Math]::Pow(2,$renewalFailures))
                $nextRenewal=[DateTime]::UtcNow.AddSeconds($retrySeconds)
                Start-Sleep -Seconds $retrySeconds
                continue
            }
            $nextRenewal=[DateTime]::UtcNow.AddSeconds($renewalInterval)
            if($newToken -ne $workerToken){
                if($worker -and -not $worker.HasExited){Stop-Process -Id $worker.Id -Force}
                $workerToken=$newToken
                $env:DESTINEDFX_WORKER_TOKEN=$workerToken
                Start-Sleep -Seconds 2
            }
        }
        if($worker.HasExited){
            $worker=Start-ManagedChild 'worker' $workerDir @('"' + (Join-Path $workerDir 'worker.py') + '"','--config','"' + $workerConfig + '"')
        }
    }
} finally {
    foreach($proc in @($worker,$bridge)){if($proc -and -not $proc.HasExited){Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue}}
    foreach($name in @('BRIDGE_DB_PATH','BRIDGE_BASE_URL','ALLOW_LOOPBACK_HTTP','BRIDGE_HOST_MODE','BRIDGE_HOST_PROFILE','EXECUTION_STORAGE_DURABLE','MANAGEMENT_STEP_UP_PUBLIC_KEY','MANAGEMENT_STEP_UP_ISSUER','MANAGEMENT_STEP_UP_AUDIENCE','CREDENTIALS_FERNET_KEY','WORKER_BOOTSTRAP_TOKEN','DESTINEDFX_WORKER_TOKEN','DESTINEDFX_WORKER_ID','DESTINEDFX_WORKER_RENEWAL_CHECK_SECONDS','DESTINEDFX_WORKER_RENEWAL_MARGIN_SECONDS','CF_ACCESS_CLIENT_ID','CF_ACCESS_CLIENT_SECRET','PYTHONUNBUFFERED')){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}
    $env:CREDENTIALS_FERNET_KEY=$null;$env:WORKER_BOOTSTRAP_TOKEN=$null;$env:DESTINEDFX_WORKER_TOKEN=$null
    if($mutexOwned){$mutex.ReleaseMutex()}
    if($mutex){$mutex.Dispose()}
}

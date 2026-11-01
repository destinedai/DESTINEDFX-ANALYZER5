[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDir
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($env:GITHUB_WORKSPACE)) {
    throw 'GITHUB_WORKSPACE is required; run this script from the checked-out repository.'
}
if ([string]::IsNullOrWhiteSpace($env:RUNNER_TEMP)) {
    throw 'RUNNER_TEMP is required; this script is intended for a disposable Windows CI runner.'
}

$installerUrl = 'https://download.terminal.free/cdn/web/metaquotes.ltd/mt5/mt5setup.exe'
$installerPath = Join-Path $env:RUNNER_TEMP 'mt5setup.exe'
$installPath = Join-Path $env:RUNNER_TEMP 'MetaTrader5'
$sourcePath = Join-Path $env:GITHUB_WORKSPACE 'mt5-ea\DestinedFXTraderEA.mq5'

if (!(Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
    throw "EA source not found: $sourcePath"
}

Write-Host "Downloading official MetaTrader 5 installer from $installerUrl"
Invoke-WebRequest -Uri $installerUrl -OutFile $installerPath

$signature = Get-AuthenticodeSignature -LiteralPath $installerPath
if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid) {
    throw "MetaTrader installer Authenticode signature is not valid: $($signature.Status)"
}
$signer = $signature.SignerCertificate.Subject
if ($signer -notmatch 'MetaQuotes') {
    throw "Unexpected MetaTrader installer signer: $signer"
}
Write-Host "Verified installer signer: $signer"

function Find-MetaEditor {
    $roots = @($installPath, (Join-Path $env:ProgramFiles 'MetaTrader 5'))
    $x86ProgramFiles = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
    if ($x86ProgramFiles) {
        $roots += Join-Path $x86ProgramFiles 'MetaTrader 5'
    }
    foreach ($root in $roots | Select-Object -Unique) {
        if (Test-Path -LiteralPath $root -PathType Container) {
            $found = Get-ChildItem -LiteralPath $root -Filter 'metaeditor64.exe' -File -Recurse -ErrorAction SilentlyContinue |
                Select-Object -First 1
            if ($null -ne $found) {
                return $found
            }
        }
    }
    return $null
}

New-Item -ItemType Directory -Path $installPath -Force | Out-Null
Write-Host "Installing MetaTrader into the temporary CI directory $installPath"
$installProcess = Start-Process -FilePath $installerPath `
    -ArgumentList @('/auto', "/path:`"$installPath`"") `
    -Wait -PassThru
$metaEditor = Find-MetaEditor
if ($null -eq $metaEditor) {
    Write-Warning "Custom-path installation returned $($installProcess.ExitCode) and no compiler was found; retrying the official installer at its documented default path."
    $fallbackProcess = Start-Process -FilePath $installerPath -ArgumentList '/auto' -Wait -PassThru
    $metaEditor = Find-MetaEditor
    if ($null -eq $metaEditor) {
        throw "MetaTrader installation failed (custom-path exit $($installProcess.ExitCode), default-path exit $($fallbackProcess.ExitCode)); no genuine metaeditor64.exe was installed."
    }
}

$terminalRoot = Split-Path -Path $metaEditor.FullName -Parent
$expertsPath = Join-Path $terminalRoot 'MQL5\Experts'
New-Item -ItemType Directory -Path $expertsPath -Force | Out-Null
$mql5Root = Join-Path $terminalRoot 'MQL5'
$targetInclude = Join-Path $mql5Root 'Include'
$tradeInclude = Join-Path $targetInclude 'Trade\Trade.mqh'
if (!(Test-Path -LiteralPath $tradeInclude -PathType Leaf)) {
    $terminalExe = Join-Path $terminalRoot 'terminal64.exe'
    if (Test-Path -LiteralPath $terminalExe -PathType Leaf) {
        Write-Host 'Initializing the genuine terminal in portable mode to provision its standard MQL5 library.'
        $terminalProcess = Start-Process -FilePath $terminalExe -ArgumentList '/portable' -PassThru
        try {
            $deadline = (Get-Date).AddSeconds(45)
            while ((Get-Date) -lt $deadline -and !(Test-Path -LiteralPath $tradeInclude -PathType Leaf)) {
                Start-Sleep -Seconds 3
            }
        }
        finally {
            if (!$terminalProcess.HasExited) {
                Stop-Process -Id $terminalProcess.Id -Force -ErrorAction SilentlyContinue
            }
        }
    }
}
if (!(Test-Path -LiteralPath $tradeInclude -PathType Leaf)) {
    $sharedData = Join-Path $env:APPDATA 'MetaQuotes\Terminal'
    if (Test-Path -LiteralPath $sharedData -PathType Container) {
        $sharedTradeInclude = Get-ChildItem -LiteralPath $sharedData -Filter 'Trade.mqh' -File -Recurse -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($null -ne $sharedTradeInclude) {
            $sourceInclude = Split-Path -Parent $sharedTradeInclude.DirectoryName
            New-Item -ItemType Directory -Path $targetInclude -Force | Out-Null
            Copy-Item -Path (Join-Path $sourceInclude '*') -Destination $targetInclude -Recurse -Force
        }
    }
}
if (!(Test-Path -LiteralPath $tradeInclude -PathType Leaf)) {
    throw "MetaTrader's standard MQL5 library is missing Trade\Trade.mqh under $targetInclude"
}

$stagedSource = Join-Path $expertsPath 'DestinedFXTraderEA.mq5'
Copy-Item -LiteralPath $sourcePath -Destination $stagedSource -Force
$compiledEa = [System.IO.Path]::ChangeExtension($stagedSource, '.ex5')
$compileLog = [System.IO.Path]::ChangeExtension($stagedSource, '.log')
Remove-Item -LiteralPath $compiledEa, $compileLog -Force -ErrorAction SilentlyContinue

Write-Host "Compiling with genuine MetaEditor: $($metaEditor.FullName)"
$compileArguments = @("/compile:`"$stagedSource`"", "/include:`"$mql5Root`"", '/log')
$compileProcess = Start-Process -FilePath $metaEditor.FullName `
    -ArgumentList $compileArguments -Wait -PassThru
if (!(Test-Path -LiteralPath $compileLog -PathType Leaf)) {
    throw "MetaEditor exited with code $($compileProcess.ExitCode) and produced no compiler log at $compileLog"
}
$logText = Get-Content -LiteralPath $compileLog -Raw
$summary = [regex]::Match($logText, '(?im)^\s*(?:Result:\s*)?(?<errors>\d+)\s+errors?,\s*(?<warnings>\d+)\s+warnings?\b')
if (!$summary.Success) {
    throw "Could not find an errors/warnings summary in the MetaEditor log: $compileLog"
}
if ([int]$summary.Groups['errors'].Value -ne 0) {
    Write-Host '---- MetaEditor compiler diagnostics ----'
    Get-Content -LiteralPath $compileLog | ForEach-Object { Write-Host $_ }
    Write-Host '---- End MetaEditor compiler diagnostics ----'
    throw "MetaEditor reported $($summary.Groups['errors'].Value) compilation errors. See $compileLog"
}
if ($compileProcess.ExitCode -ne 0) {
    Write-Warning "MetaEditor returned code $($compileProcess.ExitCode), but its log reports zero compilation errors; the generated EX5 is still required below."
}
if (!(Test-Path -LiteralPath $compiledEa -PathType Leaf)) {
    throw "MetaEditor reported no errors but did not create the expected EA: $compiledEa"
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
$outputEa = Join-Path $OutputDir 'DestinedFXTraderEA.ex5'
$outputLog = Join-Path $OutputDir 'DestinedFXTraderEA.log'
Copy-Item -LiteralPath $compiledEa -Destination $outputEa -Force
Copy-Item -LiteralPath $compileLog -Destination $outputLog -Force
$eaHash = (Get-FileHash -LiteralPath $outputEa -Algorithm SHA256).Hash.ToLowerInvariant()
$sourceHash = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
$compilerVersion = (Get-Item -LiteralPath $metaEditor.FullName).VersionInfo.ProductVersion
$warnings = [int]$summary.Groups['warnings'].Value

@"
Artifact: DestinedFXTraderEA.ex5
Source: mt5-ea/DestinedFXTraderEA.mq5
Source SHA-256: $sourceHash
EA SHA-256: $eaHash
MetaEditor version: $compilerVersion
Installer signer: $signer
Compilation: 0 errors, $warnings warnings
Platform: disposable GitHub Actions windows-latest runner
Scope: genuine MetaEditor compilation only; no broker login, terminal heartbeat, or order execution was performed.
"@ | Set-Content -LiteralPath (Join-Path $OutputDir 'build-evidence.txt') -Encoding utf8

Write-Host "MetaEditor compilation passed with 0 errors and $warnings warnings."
Write-Host "Compiled EA SHA-256: $eaHash"

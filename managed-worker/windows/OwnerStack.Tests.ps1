Describe 'DestinedFX owner stack' {
    BeforeAll {
        $python = (Get-Command python.exe -ErrorAction SilentlyContinue).Source
        if (-not $python) { $python = (Get-Command py.exe -ErrorAction SilentlyContinue).Source }
        if (-not $python) { throw 'Python is required for owner-stack behavioral tests.' }
    }

    It 'records an unhealthy service without converting it to ready' {
        $state = Join-Path $TestDrive 'health.json'
        & $python (Join-Path $PSScriptRoot 'health-monitor.py') --url 'http://127.0.0.1:1' --state-file $state
        $LASTEXITCODE | Should -Be 2
        $result = Get-Content $state -Raw | ConvertFrom-Json
        $result.healthy | Should -BeFalse
        $result.live.status | Should -Be 0
    }

    It 'protects a backup archive with the installing Windows user DPAPI' {
        $snapshot = Join-Path $TestDrive 'backup-test'
        New-Item -ItemType Directory -Path $snapshot | Out-Null
        Set-Content -Path (Join-Path $snapshot 'manifest.json') -Value '{"format":1,"files":{}}'
        Set-Content -Path (Join-Path $snapshot 'worker-id.txt') -Value 'behavioral-test'
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'protect-backup.ps1') -Snapshot $snapshot
        $LASTEXITCODE | Should -Be 0
        Test-Path "$snapshot.dpapi" | Should -BeTrue
        Test-Path $snapshot | Should -BeFalse
    }

    It 'ships the recovery and supervision entry points' {
        (Test-Path (Join-Path $PSScriptRoot 'run-owner-stack.ps1')) | Should -BeTrue
        (Test-Path (Join-Path $PSScriptRoot 'install-owner-stack.ps1')) | Should -BeTrue
        (Test-Path (Join-Path $PSScriptRoot 'restore-bridge.ps1')) | Should -BeTrue
        (Test-Path (Join-Path $PSScriptRoot 'protect-backup.ps1')) | Should -BeTrue
        (Test-Path (Join-Path $PSScriptRoot 'health-monitor.py')) | Should -BeTrue
    }
}

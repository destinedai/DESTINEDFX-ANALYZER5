Describe 'DestinedFX owner stack' {
    BeforeAll {
        $supervisor = Get-Content (Join-Path $PSScriptRoot 'run-owner-stack.ps1') -Raw
        $installer = Get-Content (Join-Path $PSScriptRoot 'install-owner-stack.ps1') -Raw
    }

    It 'validates DPAPI content before replacement' {
        $supervisor | Should -Match 'ConvertTo-SecureString'
        $supervisor | Should -Match 'ConvertFrom-Json'
        $supervisor | Should -Match 'Move-Item -LiteralPath \$temporary -Destination \$secretFile -Force'
        $installer | Should -Match 'DPAPI semantic validation failed'
    }

    It 'uses the stable worker identity and avoids reviving revoked workers' {
        $supervisor | Should -Match 'Local\\DestinedFX-Worker-'
        $supervisor | Should -Match 'Worker identity is revoked'
        $supervisor | Should -Match 'worker_id'
    }

    It 'revalidates before child restart and renews before expiry' {
        $supervisor | Should -Match 'Ensure-RegisteredWorker \$secrets \$workerId'
        $supervisor | Should -Match 'RenewalMarginSeconds'
        $supervisor | Should -Match '\$worker.HasExited'
        $supervisor | Should -Match 'Wait-BridgeConfiguration'
    }

    It 'does not emit credentials in supervisor output' {
        $supervisor | Should -Not -Match 'Write-Host.*worker_token'
        $supervisor | Should -Not -Match 'Write-Host.*workerToken'
    }
}

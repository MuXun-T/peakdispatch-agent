[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackupFile,
    [string]$ReportPath = '..\docs\operations\restore-drill-results.json',
    [string]$RecoveryStartScript,
    [string]$AppHealthUrl,
    [int]$HealthWaitSeconds = 14400
)

$ErrorActionPreference = 'Stop'
foreach ($name in @('MYSQL_USER', 'MYSQL_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "Required environment variable $name is missing"
    }
}
$mysqlCommand = Get-Command mysql -ErrorAction SilentlyContinue
if (-not $mysqlCommand) { throw 'mysql client is required on PATH' }
$archivePath = (Resolve-Path -LiteralPath $BackupFile).Path
$checksumPath = "$archivePath.sha256"
$manifestPath = "$archivePath.manifest.json"
if (-not (Test-Path -LiteralPath $checksumPath) -or -not (Test-Path -LiteralPath $manifestPath)) {
    throw 'Backup checksum or manifest is missing'
}
$expectedHash = ([System.IO.File]::ReadAllText($checksumPath) -split '\s+')[0].ToLowerInvariant()
$actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
if ($expectedHash -ne $actualHash) { throw 'Backup SHA256 verification failed' }
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.validation -ne 'PASS' -or $manifest.sha256 -ne $actualHash) {
    throw 'Backup manifest does not match the verified archive'
}

$hostName = if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { '127.0.0.1' }
$port = if ($env:MYSQL_PORT) { $env:MYSQL_PORT } else { '3306' }
$targetDatabase = 'peakdispatch_restore_drill_' + [DateTime]::UtcNow.ToString('yyyyMMddHHmmss')
if ($targetDatabase -notmatch '^peakdispatch_restore_drill_[0-9]{14}$') { throw 'Unsafe restore database name' }
$scratch = Join-Path ([System.IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch | Out-Null
$oldPassword = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
$startedAt = [DateTime]::UtcNow
$rtoStatus = 'NOT RUN'
$healthReadyAt = $null
try {
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $env:MYSQL_PASSWORD, 'Process')
    Expand-Archive -LiteralPath $archivePath -DestinationPath $scratch
    $dump = Get-ChildItem -LiteralPath $scratch -Filter '*.sql' -File | Select-Object -First 1
    if (-not $dump) { throw 'Backup archive contains no SQL dump' }
    $createSql = "CREATE DATABASE $targetDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
    $createArgs = @("--host=$hostName", "--port=$port", "--user=$env:MYSQL_USER", "--execute=$createSql")
    & $mysqlCommand.Source @createArgs
    if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated restore-drill database' }

    $import = [System.Diagnostics.ProcessStartInfo]::new()
    $import.FileName = $mysqlCommand.Source
    $import.UseShellExecute = $false
    $import.RedirectStandardInput = $true
    $import.RedirectStandardOutput = $true
    $import.RedirectStandardError = $true
    foreach ($argument in @("--host=$hostName", "--port=$port", "--user=$env:MYSQL_USER", "--database=$targetDatabase")) {
        [void]$import.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $import
    [void]$process.Start()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $inputFile = [System.IO.File]::OpenRead($dump.FullName)
    try { $inputFile.CopyTo($process.StandardInput.BaseStream) } finally { $inputFile.Dispose(); $process.StandardInput.Close() }
    $process.WaitForExit()
    [void]$stderrTask.GetAwaiter().GetResult()
    [void]$stdoutTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { throw "MySQL restore import failed with exit code $($process.ExitCode)" }

    $countSql = 'SELECT COUNT(*) FROM delivery_order; SELECT COUNT(*) FROM courier; SELECT COUNT(*) FROM outbox_event;'
    $countArgs = @("--host=$hostName", "--port=$port", "--user=$env:MYSQL_USER", "--database=$targetDatabase",
        '--batch', '--skip-column-names', "--execute=$countSql")
    $counts = & $mysqlCommand.Source @countArgs
    if ($LASTEXITCODE -ne 0 -or @($counts).Count -lt 3) { throw 'Restored database integrity query failed' }
    $databaseVerifiedAt = [DateTime]::UtcNow

    $recoveryProcess = $null
    if ($AppHealthUrl -and $RecoveryStartScript) {
        $recoveryScriptPath = (Resolve-Path -LiteralPath $RecoveryStartScript).Path
        $redisNamespace = 'restore' + [DateTime]::UtcNow.ToString('MMddHHmmss')
        $launcher = Join-Path $PSHOME 'powershell.exe'
        $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $launcher
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        foreach ($argument in @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $recoveryScriptPath,
                '-TargetDatabase', $targetDatabase, '-RedisNamespace', $redisNamespace)) {
            [void]$startInfo.ArgumentList.Add($argument)
        }
        $recoveryProcess = [System.Diagnostics.Process]::new()
        $recoveryProcess.StartInfo = $startInfo
        [void]$recoveryProcess.Start()
        $deadline = [DateTime]::UtcNow.AddSeconds([Math]::Min($HealthWaitSeconds, 14400))
        while ([DateTime]::UtcNow -lt $deadline -and -not $recoveryProcess.HasExited) {
            try {
                $health = Invoke-RestMethod -Uri $AppHealthUrl -TimeoutSec 5
                if ($health.status -eq 'UP') { $healthReadyAt = [DateTime]::UtcNow; break }
            } catch { }
            Start-Sleep -Seconds 5
        }
        if ($healthReadyAt) {
            $rtoStatus = if (($healthReadyAt - $startedAt).TotalHours -le 4) { 'PASS' } else { 'FAIL' }
        } else {
            $rtoStatus = 'FAIL'
        }
    }

    $rpoAgeSeconds = ([DateTime]::UtcNow - [DateTime]::Parse($manifest.backupCompletedAtUtc).ToUniversalTime()).TotalSeconds
    $report = [ordered]@{
        generatedAtUtc = [DateTime]::UtcNow.ToString('o')
        status = if ($rtoStatus -eq 'FAIL' -or $rpoAgeSeconds -gt 86400) { 'FAIL' } elseif ($rtoStatus -eq 'PASS') { 'PASS' } else { 'NOT RUN' }
        backup = [System.IO.Path]::GetFileName($archivePath)
        backupSha256 = $actualHash
        restoredDatabase = $targetDatabase
        restoredTableCounts = @($counts)
        databaseRestoreSeconds = ($databaseVerifiedAt - $startedAt).TotalSeconds
        applicationReadyAtUtc = if ($healthReadyAt) { $healthReadyAt.ToString('o') } else { $null }
        rtoStatus = $rtoStatus
        rpoAgeSeconds = [Math]::Round($rpoAgeSeconds, 2)
        rpoTargetHours = 24
        rtoTargetHours = 4
        recoveryStartup = if ($recoveryProcess) { 'started by RecoveryStartScript' } else { 'NOT RUN: provide RecoveryStartScript and AppHealthUrl to launch an isolated recovery runtime' }
        redisRebuild = if ($healthReadyAt) { "Application became healthy with isolated namespace $redisNamespace; verify the recovery script waits for worker projection rebuild" } else { 'NOT RUN: no recovery application process reached health' }
        secretsPrinted = $false
    }
    $resolvedReport = if ([System.IO.Path]::IsPathRooted($ReportPath)) {
        [System.IO.Path]::GetFullPath($ReportPath)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot $ReportPath))
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resolvedReport) | Out-Null
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $resolvedReport -Encoding utf8
    Write-Output "Restore drill $($report.status): database=$targetDatabase, RPO age=$($report.rpoAgeSeconds)s, RTO=$rtoStatus"
    if ($report.status -eq 'FAIL') { exit 1 }
} finally {
    if ($recoveryProcess -and -not $recoveryProcess.HasExited) {
        $recoveryProcess.Kill($true)
        $recoveryProcess.WaitForExit(10000) | Out-Null
        $recoveryProcess.Dispose()
    }
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $oldPassword, 'Process')
    $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    $scratchPath = [System.IO.Path]::GetFullPath($scratch)
    if ($scratchPath.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $scratchPath -Recurse -Force -ErrorAction SilentlyContinue
    }
}

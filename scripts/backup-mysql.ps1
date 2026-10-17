[CmdletBinding()]
param(
    [string]$BackupDirectory = '..\backups',
    [int]$RetentionDays = 7,
    [string]$ReportPath = '..\docs\operations\backup-results.json'
)

$ErrorActionPreference = 'Stop'
foreach ($name in @('MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "Required environment variable $name is missing"
    }
}
$dumpCommand = Get-Command mysqldump -ErrorAction SilentlyContinue
if (-not $dumpCommand) { throw 'mysqldump is required on PATH' }
if ($RetentionDays -lt 1) { throw 'RetentionDays must be positive' }

$hostName = if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { '127.0.0.1' }
$port = if ($env:MYSQL_PORT) { $env:MYSQL_PORT } else { '3306' }
$startedAt = [DateTime]::UtcNow
$stamp = $startedAt.ToString('yyyyMMddTHHmmssZ')
$baseName = "peakdispatch_$stamp"
$backupPath = if ([System.IO.Path]::IsPathRooted($BackupDirectory)) {
    [System.IO.Path]::GetFullPath($BackupDirectory)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot $BackupDirectory))
}
New-Item -ItemType Directory -Force -Path $backupPath | Out-Null
$scratch = Join-Path ([System.IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch | Out-Null
$dumpPath = Join-Path $scratch "$baseName.sql"
$archivePath = Join-Path $backupPath "$baseName.sql.zip"
$oldPassword = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
try {
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $env:MYSQL_PASSWORD, 'Process')
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $dumpCommand.Source
    $start.UseShellExecute = $false
    $start.RedirectStandardError = $true
    foreach ($argument in @('--single-transaction', '--routines', '--events', '--hex-blob',
            '--no-tablespaces', '--set-gtid-purged=OFF', "--host=$hostName", "--port=$port",
            "--user=$env:MYSQL_USER", "--result-file=$dumpPath", $env:MYSQL_DATABASE)) {
        [void]$start.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    [void]$process.Start()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "mysqldump failed with exit code $($process.ExitCode); inspect the local client error output"
    }
    if (-not (Test-Path -LiteralPath $dumpPath) -or (Get-Item -LiteralPath $dumpPath).Length -eq 0) {
        throw 'mysqldump produced an empty file'
    }
    Compress-Archive -LiteralPath $dumpPath -DestinationPath $archivePath -CompressionLevel Optimal
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        if ($zip.Entries.Count -ne 1 -or $zip.Entries[0].Length -eq 0) { throw 'Backup archive validation failed' }
    } finally {
        $zip.Dispose()
    }
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$archivePath.sha256" -Value "$hash  $([System.IO.Path]::GetFileName($archivePath))" -Encoding ascii
    $manifest = [ordered]@{
        database = $env:MYSQL_DATABASE
        backupStartedAtUtc = $startedAt.ToString('o')
        backupCompletedAtUtc = [DateTime]::UtcNow.ToString('o')
        archive = [System.IO.Path]::GetFileName($archivePath)
        sizeBytes = (Get-Item -LiteralPath $archivePath).Length
        sha256 = $hash
        validation = 'PASS'
    }
    $manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath "$archivePath.manifest.json" -Encoding utf8
    $resolvedReport = if ([System.IO.Path]::IsPathRooted($ReportPath)) {
        [System.IO.Path]::GetFullPath($ReportPath)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot $ReportPath))
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resolvedReport) | Out-Null
    $manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $resolvedReport -Encoding utf8
    $expiry = [DateTime]::UtcNow.AddDays(-$RetentionDays)
    Get-ChildItem -LiteralPath $backupPath -Filter 'peakdispatch_*.sql.zip' -File |
        Where-Object { $_.LastWriteTimeUtc -lt $expiry } |
        ForEach-Object {
            Remove-Item -LiteralPath $_.FullName -Force
            Remove-Item -LiteralPath "$($_.FullName).sha256" -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath "$($_.FullName).manifest.json" -Force -ErrorAction SilentlyContinue
        }
    Write-Output "Backup PASS: $archivePath ($($manifest.sizeBytes) bytes, SHA256 $hash); report: $resolvedReport"
} finally {
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $oldPassword, 'Process')
    Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue
}

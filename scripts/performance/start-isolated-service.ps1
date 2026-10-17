[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('API', 'WORKER')]
    [string]$Role,
    [int]$Port = 0,
    [string]$InstanceName = '',
    [string]$CpuAffinityMask = '0xFF'
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$benchmarkRoot = 'E:\perf\peakdispatch\benchmark'
$mysqlCredentials = Get-Content 'E:\perf\peakdispatch\mysql-credentials.json' -Raw | ConvertFrom-Json
$accounts = Get-Content (Join-Path $benchmarkRoot 'demo-accounts.json') -Raw | ConvertFrom-Json
$fixtureMetadata = Get-Content (Join-Path $benchmarkRoot 'fixture-metadata.json') -Raw | ConvertFrom-Json
$fixtureReader = [System.IO.StreamReader]::new((Join-Path $benchmarkRoot 'couriers.json'))
try {
    $fixtureUsername = $null
    while ($null -ne ($fixtureLine = $fixtureReader.ReadLine())) {
        $usernameMatch = [regex]::Match($fixtureLine, '"username"\s*:\s*"([^"]+)"')
        if ($usernameMatch.Success) {
            $fixtureUsername = $usernameMatch.Groups[1].Value
            break
        }
    }
} finally {
    $fixtureReader.Dispose()
}
if ([string]::IsNullOrWhiteSpace($fixtureUsername) -or $fixtureUsername -notmatch '\d+$') {
    throw 'Courier fixture does not contain a username with a numeric suffix.'
}
$courierUsernamePrefix = $fixtureUsername -replace '\d+$', ''
if ($Port -eq 0) { $Port = if ($Role -eq 'API') { 8080 } else { 8081 } }

$env:SPRING_PROFILES_ACTIVE = 'demo'
$env:MYSQL_HOST = '127.0.0.1'
$env:MYSQL_PORT = '13306'
$env:MYSQL_DATABASE = if ($env:PD_PERF_MYSQL_DATABASE) { $env:PD_PERF_MYSQL_DATABASE } else { 'peakdispatch_perf_bench' }
$env:MYSQL_USER = $mysqlCredentials.username
$env:MYSQL_PASSWORD = $mysqlCredentials.password
$env:REDIS_HOST = '127.0.0.1'
$env:REDIS_PORT = '16379'
$env:PD_ENV = if ($env:PD_PERF_REDIS_NAMESPACE) { $env:PD_PERF_REDIS_NAMESPACE } else { 'perfbench-run3-20261014' }
$env:PD_DATA_MODE = 'simulation'
$env:PD_RUNTIME_ROLE = $Role
$env:SERVER_PORT = [string]$Port
$apiPoolSize = if ($env:PD_PERF_API_DB_POOL_SIZE) { $env:PD_PERF_API_DB_POOL_SIZE } else { '20' }
$workerPoolSize = if ($env:PD_PERF_WORKER_DB_POOL_SIZE) { $env:PD_PERF_WORKER_DB_POOL_SIZE } else { '10' }
$schedulerPoolSize = if ($env:PD_PERF_SCHEDULING_POOL_SIZE) { $env:PD_PERF_SCHEDULING_POOL_SIZE } else { '4' }
$env:DB_POOL_SIZE = if ($Role -eq 'API') { $apiPoolSize } else { $workerPoolSize }
$env:PD_SCHEDULING_POOL_SIZE = $schedulerPoolSize
$env:SPRING_TASK_SCHEDULING_POOL_SIZE = $schedulerPoolSize
$env:PD_DEMO_BCRYPT_STRENGTH = if ($env:PD_PERF_DEMO_BCRYPT_STRENGTH) { $env:PD_PERF_DEMO_BCRYPT_STRENGTH } else { '4' }
$env:PD_DEMO_COURIER_ACCOUNT_COUNT = [string]$fixtureMetadata.couriers
$env:PD_DEMO_COURIER_USERNAME_PREFIX = $courierUsernamePrefix
$env:PD_DEMO_COURIER_ACCOUNT_PASSWORD = $accounts.performanceCourierPassword
$env:PD_DEMO_COURIER_REGION_SCOPE = $fixtureMetadata.regionId
$env:PD_PERF_API_DB_POOL_SIZE = $apiPoolSize
$env:PD_PERF_WORKER_DB_POOL_SIZE = $workerPoolSize
$env:PD_PERF_JAVA_MAX_HEAP = 'API -Xms512m -Xmx2g; each Worker -Xms256m -Xmx1g'
$env:PD_RATE_LIMIT_ENABLED = 'true'
$env:PD_RATE_LIMIT_WINDOW_MS = '1000'
$env:PD_RATE_LIMIT_USER = '300'
$env:PD_RATE_LIMIT_ROLE = '600'
$env:PD_RATE_LIMIT_REGION = '600'
$env:PD_RATE_LIMIT_API = '600'
$env:PD_OUTBOX_DELAY_MS = '100'
$env:PD_STREAM_POLL_DELAY_MS = '100'
$env:PD_REDIS_RECONCILE_DELAY_MS = '1000'
$env:PD_LIFECYCLE_SCAN_DELAY_MS = '100'
$env:PD_DISPATCH_SCAN_DELAY_MS = '100'
$env:PD_OFFER_TIMEOUT_SCAN_DELAY_MS = '1000'
$env:ELASTICSEARCH_URIS = 'http://127.0.0.1:9200'
$env:LOGGING_LEVEL_ROOT = 'INFO'
$env:LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_JDBC_CORE_JDBCTEMPLATE = 'INFO'
$env:PD_DEMO_MERCHANT_USERNAME = $accounts.merchant.username
$env:PD_DEMO_MERCHANT_PASSWORD = $accounts.merchant.password
$env:PD_DEMO_COURIER_USERNAME = $accounts.courier.username
$env:PD_DEMO_COURIER_PASSWORD = $accounts.courier.password
$env:PD_DEMO_OPS_VIEWER_USERNAME = $accounts.opsViewer.username
$env:PD_DEMO_OPS_VIEWER_PASSWORD = $accounts.opsViewer.password
$env:PD_DEMO_OPS_APPROVER_USERNAME = $accounts.opsApprover.username
$env:PD_DEMO_OPS_APPROVER_PASSWORD = $accounts.opsApprover.password
$env:PD_DEMO_OPS_APPROVER_BACKUP_USERNAME = $accounts.opsApproverBackup.username
$env:PD_DEMO_OPS_APPROVER_BACKUP_PASSWORD = $accounts.opsApproverBackup.password
$env:PD_DEMO_OPS_ADMIN_USERNAME = $accounts.opsAdmin.username
$env:PD_DEMO_OPS_ADMIN_PASSWORD = $accounts.opsAdmin.password

$jar = Join-Path $workspace 'peakdispatch-api-worker\target\peakdispatch-api-worker-0.1.0-SNAPSHOT.jar'
$logName = if ($env:PD_PERF_REDIS_NAMESPACE) { $env:PD_PERF_REDIS_NAMESPACE + '-' + $Role.ToLowerInvariant() } else { $Role.ToLowerInvariant() + '-optimized' }
if (-not [string]::IsNullOrWhiteSpace($InstanceName)) { $logName += '-' + $InstanceName }
$stdout = Join-Path 'E:\perf\peakdispatch\run-logs' ($logName + '-stdout.log')
$stderr = Join-Path 'E:\perf\peakdispatch\run-logs' ($logName + '-stderr.log')
$heapArguments = if ($Role -eq 'API') { @('-Xms512m', '-Xmx2g') } else { @('-Xms256m', '-Xmx1g') }
$javaArguments = $heapArguments + @('-Dlogging.level.root=INFO',
    '-Dlogging.level.org.springframework.jdbc.core.JdbcTemplate=WARN', '-jar', $jar)
$process = Start-Process -FilePath (Get-Command java.exe -ErrorAction Stop).Source `
    -ArgumentList $javaArguments `
    -WorkingDirectory (Split-Path $jar) `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr `
    -PassThru -WindowStyle Hidden
$maskDigits = $CpuAffinityMask -replace '^0[xX]', ''
if ($maskDigits -notmatch '^[0-9A-Fa-f]+$') { throw 'CpuAffinityMask must be a hexadecimal Windows processor mask.' }
$process.ProcessorAffinity = [IntPtr][Convert]::ToInt64($maskDigits, 16)

$auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
    "$($accounts.merchant.username):$($accounts.merchant.password)"))
$probe = if ($Role -eq 'API') {
    'http://127.0.0.1:' + $Port + '/api/v1/system/recovery'
} else {
    'http://127.0.0.1:' + $Port + '/actuator/prometheus'
}
$ready = $false
$deadline = (Get-Date).AddMinutes(2)
while ((Get-Date) -lt $deadline -and -not $ready) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $probe -Headers @{ Authorization = $auth } -TimeoutSec 5
        $ready = $response.StatusCode -eq 200
    } catch {
        Start-Sleep -Seconds 2
    }
}
if (-not $ready) {
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Get-Content $stderr -Tail 30 -ErrorAction SilentlyContinue
    throw "$Role did not become ready on port $Port."
}
Write-Output "$Role ready on port $Port (PID $($process.Id))."

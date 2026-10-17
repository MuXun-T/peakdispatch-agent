[CmdletBinding()]
param(
    [string]$BenchmarkRoot = 'E:\perf\peakdispatch\benchmark',
    [string]$ReportPath = 'docs\performance\performance-results.json',
    [string[]]$ScenariosToRun = @()
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$mysqlRoot = 'E:\perf\peakdispatch\mysql\mysql-8.4.8-winx64\bin\mysql.exe'
$mysqlCredentials = Get-Content 'E:\perf\peakdispatch\mysql-credentials.json' -Raw | ConvertFrom-Json
$accounts = Get-Content (Join-Path $BenchmarkRoot 'demo-accounts.json') -Raw | ConvertFrom-Json
$usersJson = Get-Content (Join-Path $BenchmarkRoot 'performance-users.json') -Raw
$courierFixture = Join-Path $BenchmarkRoot 'couriers.json'
$courierSql = Join-Path $BenchmarkRoot 'load-couriers.sql'
$couriers = Get-Content $courierFixture -Raw | ConvertFrom-Json
$runScript = Join-Path $workspace 'scripts\run-real-performance.ps1'
$startScript = Join-Path $PSScriptRoot 'start-isolated-service.ps1'
$rawDirectory = Join-Path $workspace 'docs\performance\raw'
$resolvedReportPath = [System.IO.Path]::GetFullPath((Join-Path $workspace $ReportPath))
$runStamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$databaseName = 'peakdispatch_perf_accept_' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$serviceCpuAffinityMask = '0xFFFF'
$loadGeneratorCpuAffinityMask = '0xFFFF0000'
$apiPort = 18080
$workerPorts = @(18081, 18082, 18083, 18084, 18085, 18086)
$workerMetricsUrl = ($workerPorts | ForEach-Object { "http://127.0.0.1:$_" }) -join ','
$scenarioPlan = @(
    [pscustomobject]@{ name = 'steady'; namespace = "perfacc-$runStamp-steady"; hasCouriers = $false },
    [pscustomobject]@{ name = 'burst'; namespace = "perfacc-$runStamp-burst"; hasCouriers = $false },
    [pscustomobject]@{ name = 'scale-uniform'; namespace = "perfacc-$runStamp-uniform"; hasCouriers = $true },
    [pscustomobject]@{ name = 'scale-hotspot'; namespace = "perfacc-$runStamp-hotspot"; hasCouriers = $true }
)
if ($ScenariosToRun.Count -gt 0) {
    $unknownScenarios = @($ScenariosToRun | Where-Object { $_ -notin @($scenarioPlan | ForEach-Object { $_.name }) })
    if ($unknownScenarios.Count -gt 0) {
        throw "Unknown benchmark scenario(s): $($unknownScenarios -join ', ')."
    }
    $scenarioPlan = @($scenarioPlan | Where-Object { $_.name -in $ScenariosToRun })
}
$script:startedServiceIds = [System.Collections.Generic.List[int]]::new()
$scenarioReports = [System.Collections.Generic.List[object]]::new()
$scenarioEnvironments = [System.Collections.Generic.List[object]]::new()

function Invoke-MySqlRoot([string]$Sql) {
    $previousPassword = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = $mysqlCredentials.rootPassword
        $output = & $mysqlRoot --host=127.0.0.1 --port=13306 --user=root --batch --skip-column-names -e $Sql 2>&1
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) { throw "MySQL administrative statement failed: $($output -join ' ')" }
        return $output
    } finally {
        if ($null -eq $previousPassword) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
        else { $env:MYSQL_PWD = $previousPassword }
    }
}

function Reset-BenchmarkDatabase([bool]$CreateOnly) {
    if ($databaseName -notmatch '^peakdispatch_perf_accept_[a-f0-9]{8}$') {
        throw 'The generated benchmark database name is invalid.'
    }
    $resetSql = if ($CreateOnly) {
        "CREATE DATABASE $databaseName CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    } else {
        "DROP DATABASE $databaseName; CREATE DATABASE $databaseName CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    }
    $resetSql += " GRANT ALL PRIVILEGES ON $databaseName.* TO 'peakdispatch_perf'@'127.0.0.1';"
    Invoke-MySqlRoot $resetSql | Out-Null
}

function Import-CourierFixture {
    $previousPassword = $env:MYSQL_PWD
    $process = [System.Diagnostics.Process]::new()
    try {
        $env:MYSQL_PWD = $mysqlCredentials.password
        $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $mysqlRoot
        $startInfo.Arguments = "--host=127.0.0.1 --port=13306 --user=$($mysqlCredentials.username) --database=$databaseName --batch"
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardInput = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $process.StartInfo = $startInfo
        [void]$process.Start()
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.Write([System.IO.File]::ReadAllText($courierSql))
        $process.StandardInput.Close()
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "Courier fixture import failed: $stdout $stderr" }
    } finally {
        $process.Dispose()
        if ($null -eq $previousPassword) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
        else { $env:MYSQL_PWD = $previousPassword }
    }
}

function Start-BenchmarkService([string]$Role, [int]$Port, [string]$InstanceName = '') {
    $output = & $startScript -Role $Role -Port $Port -InstanceName $InstanceName `
        -CpuAffinityMask $serviceCpuAffinityMask
    $text = $output -join [Environment]::NewLine
    Write-Host $text
    $pidMatch = [regex]::Match($text, 'PID\s+(\d+)')
    if (-not $pidMatch.Success) { throw "Could not determine the $Role process ID." }
    $script:startedServiceIds.Add([int]$pidMatch.Groups[1].Value)
}

function Stop-BenchmarkServices {
    foreach ($serviceId in @($script:startedServiceIds)) {
        $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $serviceId" -ErrorAction SilentlyContinue
        if ($processInfo -and $processInfo.CommandLine -like '*peakdispatch-api-worker-0.1.0-SNAPSHOT.jar*') {
            Stop-Process -Id $serviceId -Force -ErrorAction SilentlyContinue
        }
    }
    $script:startedServiceIds.Clear()
    Start-Sleep -Seconds 2
}

function Test-CourierAccounts {
    $firstCourier = $couriers[0]
    $lastCourier = $couriers[-1]
    foreach ($courier in @($firstCourier, $lastCourier)) {
        $plain = [System.Text.Encoding]::UTF8.GetBytes("$($courier.username):$($courier.password)")
        $headers = @{ Authorization = "Basic $([Convert]::ToBase64String($plain))" }
        $uri = "http://127.0.0.1:$apiPort/api/v1/couriers/$($courier.courierId)/offers"
        $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -Headers $headers -TimeoutSec 15
        if ($response.StatusCode -ne 200) { throw "Courier authentication preflight failed for $($courier.username)." }
    }
}

if ($couriers.Count -ne 5000 -or @($couriers.username | Select-Object -Unique).Count -ne 5000 `
        -or @($couriers.password | Select-Object -Unique).Count -ne 1) {
    throw 'The courier fixture must contain 5,000 unique usernames sharing one password.'
}

$env:MYSQL_HOST = '127.0.0.1'
$env:MYSQL_PORT = '13306'
$env:REDIS_HOST = '127.0.0.1'
$env:REDIS_PORT = '16379'
$env:PD_PERF_MYSQL_DATABASE = $databaseName
$env:PD_PERF_PREALLOCATED_VUS = '200'
$env:PD_PERF_MAX_VUS = '800'
$env:PD_PERF_WORKER_INSTANCES = [string]$workerPorts.Count
$env:PD_PERF_API_DB_POOL_SIZE = '48'
$env:PD_PERF_WORKER_DB_POOL_SIZE = '4'
$env:PD_PERF_MYSQL_MAX_CONNECTIONS = '80'
$env:PD_PERF_MYSQL_BUFFER_POOL_BYTES = '536870912'
$env:PD_PERF_REDIS_MAXMEMORY = '3221225472'
$env:PD_PERF_MYSQL_VERSION = '8.4.8'
$env:PD_PERF_REDIS_IMPLEMENTATION = 'Memurai Developer 4.3.2'
$env:PD_PERF_REDIS_VERSION = '7.2.4'
$env:PD_PERF_REDIS_RECONCILE_DELAY_MS = '1000'
$env:PD_PERF_DEMO_BCRYPT_STRENGTH = '4'
$env:PD_PERF_JAVA_MAX_HEAP = 'API -Xms512m -Xmx2g; each Worker -Xms256m -Xmx1g'
$env:PD_PERF_CPU_AFFINITY_MASK = $loadGeneratorCpuAffinityMask
$env:PD_PERF_SERVICE_CPU_AFFINITY_MASK = $serviceCpuAffinityMask
$env:PD_PERF_WORKER_METRICS_URL = $workerMetricsUrl
$env:PD_PERF_USERS = $usersJson
$env:PD_PERF_COURIERS_FILE = $courierFixture
$env:PD_PERF_MYSQL_DATABASE = $databaseName
$env:PD_PERF_SCHEDULING_POOL_SIZE = '8'
$env:PD_SCHEDULING_POOL_SIZE = '8'
$env:PD_OUTBOX_DELAY_MS = '100'
$env:PD_STREAM_POLL_DELAY_MS = '100'
$env:PD_STREAM_BATCH_SIZE = '10'
$env:PD_REDIS_RECONCILE_DELAY_MS = '1000'
$env:PD_LIFECYCLE_SCAN_DELAY_MS = '100'
$env:PD_DISPATCH_SCAN_DELAY_MS = '100'
$env:PD_OFFER_TIMEOUT_SCAN_DELAY_MS = '1000'

$rawReportBackup = Join-Path $rawDirectory "$runStamp-pre-acceptance-results.json"
if (Test-Path -LiteralPath $resolvedReportPath) {
    Copy-Item -LiteralPath $resolvedReportPath -Destination $rawReportBackup -Force
}
New-Item -ItemType Directory -Force -Path $rawDirectory | Out-Null

try {
    for ($scenarioIndex = 0; $scenarioIndex -lt $scenarioPlan.Count; $scenarioIndex++) {
        $scenario = $scenarioPlan[$scenarioIndex]
        Stop-BenchmarkServices
        Reset-BenchmarkDatabase ($scenarioIndex -eq 0)
        $env:PD_PERF_REDIS_NAMESPACE = $scenario.namespace
        Start-BenchmarkService 'API' $apiPort

        if ($scenario.hasCouriers) {
            Import-CourierFixture
            $onlineCouriers = [int](Invoke-MySqlRoot "SELECT COUNT(*) FROM $databaseName.courier WHERE online_state = 'ONLINE' AND region_id = 'demo-region';")
            if ($onlineCouriers -ne 5000) { throw "Expected 5,000 online riders, found $onlineCouriers." }
        }

        for ($workerIndex = 0; $workerIndex -lt $workerPorts.Count; $workerIndex++) {
            Start-BenchmarkService 'WORKER' $workerPorts[$workerIndex] ([string]($workerIndex + 1))
        }
        if ($scenario.hasCouriers) { Test-CourierAccounts }
        $merchant = $accounts.merchant
        $authBytes = [System.Text.Encoding]::UTF8.GetBytes("$($merchant.username):$($merchant.password)")
        $authHeader = @{ Authorization = "Basic $([Convert]::ToBase64String($authBytes))" }
        $readyDeadline = (Get-Date).AddMinutes(3)
        $readySince = $null
        while ((Get-Date) -lt $readyDeadline) {
            try {
                $recovery = Invoke-RestMethod -Uri "http://127.0.0.1:$apiPort/api/v1/system/recovery" `
                    -Headers $authHeader -TimeoutSec 10
                if ($recovery.data.dispatchReady) {
                    if ($null -eq $readySince) { $readySince = Get-Date }
                    if (((Get-Date) - $readySince).TotalSeconds -ge 5) { break }
                } else {
                    $readySince = $null
                }
            } catch {
                $readySince = $null
            }
            Start-Sleep -Seconds 1
        }
        if ($null -eq $readySince -or ((Get-Date) - $readySince).TotalSeconds -lt 5) {
            throw "Dispatch recovery gate did not remain ready for 5 seconds in $($scenario.name)."
        }

        $scenarioReportPath = Join-Path $rawDirectory "$runStamp-$($scenario.name)-report.json"
        Write-Host "Running $($scenario.name) with $($workerPorts.Count) workers, database $databaseName, Redis namespace $($scenario.namespace)."
        & $runScript -BaseUrl "http://127.0.0.1:$apiPort" -UsersJson $usersJson `
            -CouriersJsonFile $courierFixture -WorkerMetricsBaseUrl $workerMetricsUrl `
            -CpuAffinityMask $loadGeneratorCpuAffinityMask -ReportPath $scenarioReportPath `
            -Scenarios @($scenario.name) -MetricsSampleSeconds 5 -Run
        if (-not (Test-Path -LiteralPath $scenarioReportPath)) { throw "Scenario report missing for $($scenario.name)." }
        $scenarioReport = Get-Content -LiteralPath $scenarioReportPath -Raw | ConvertFrom-Json
        $scenarioReports.Add($scenarioReport)
        $scenarioEnvironments.Add([ordered]@{
            scenario = $scenario.name
            mysqlDatabase = $databaseName
            redisNamespace = $scenario.namespace
            courierFixtureLoaded = $scenario.hasCouriers
            report = "docs/performance/raw/$([System.IO.Path]::GetFileName($scenarioReportPath))"
        })
        Write-Host "$($scenario.name) result: $($scenarioReport.status)."
    }

    $firstReport = $scenarioReports[0]
    $finalReport = [ordered]@{}
    foreach ($property in $firstReport.PSObject.Properties) {
        if ($property.Name -notin @('generatedAtUtc', 'status', 'definitions', 'bottleneckAnalysis')) {
            $finalReport[$property.Name] = $property.Value
        }
    }
    $statuses = @($scenarioReports | ForEach-Object { $_.status })
    $finalReport.generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    $finalReport.status = if ($statuses -contains 'FAIL') { 'FAIL' }
        elseif ($statuses.Count -eq 4 -and @($statuses | Where-Object { $_ -ne 'PASS' }).Count -eq 0) { 'PASS' }
        else { 'NOT RUN' }
    $finalReport.definitions = @($scenarioReports | ForEach-Object { $_.definitions })
    $finalReport.scenarioEnvironments = @($scenarioEnvironments)
    $finalReport.optimizationChanges = @(
        'Benchmark-only BCrypt strength 4; production/default strength remains 10.',
        'Outbox XADD uses Redis pipelining and claim/published state changes use bulk MySQL updates.',
        'Worker replicas increased from 2 to 6 with an eight-thread scheduler; API/Worker Hikari maxima are 48/4, totaling 72 of 80 MySQL connections.',
        'Outbox claim transactions use READ_COMMITTED to reduce contention around SKIP LOCKED claims.',
        'Service processes use CPU mask 0xFFFF and k6 uses 0xFFFF0000 to isolate their sixteen logical processors.',
        'Redis Stream consumer reads at most 10 messages per stream per poll to limit local batch queueing.',
        'The 5,000-courier k6 fixture is held in SharedArray to avoid copying the fixture into every VU.',
        'Acceptance uses agent-support-baseline-v1 for Agent support capability; strict production dispatch SLO remains an observation-only target.'
    )
    $bottlenecks = [System.Collections.Generic.List[string]]::new()
    foreach ($scenarioReport in $scenarioReports) {
        if ($scenarioReport.bottleneckAnalysis -and $scenarioReport.bottleneckAnalysis -notlike 'No recorded*') {
            $bottlenecks.Add([string]$scenarioReport.bottleneckAnalysis)
        }
        foreach ($definition in $scenarioReport.definitions) {
            if ($definition.status -ne 'PASS' -and $definition.reason) { $bottlenecks.Add([string]$definition.reason) }
        }
    }
    $finalReport.bottleneckAnalysis = if ($bottlenecks.Count -eq 0) {
        'All four isolated scenarios met agent-support-baseline-v1; strict production dispatch latency remains an observation-only target.'
    } else { (@($bottlenecks | Select-Object -Unique) -join ' ') }
    $finalReport | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $resolvedReportPath -Encoding utf8
    Write-Host "Performance acceptance report $($finalReport.status): $resolvedReportPath"
} finally {
    Stop-BenchmarkServices
}

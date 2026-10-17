[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$UsersJson = $env:PD_PERF_USERS,
    [string]$CouriersJsonFile = $env:PD_PERF_COURIERS_FILE,
    [string]$WorkerMetricsBaseUrl = $(if ($env:PD_PERF_WORKER_METRICS_URL) { $env:PD_PERF_WORKER_METRICS_URL } else { 'http://localhost:8081' }),
    [string]$CpuAffinityMask = $(if ($env:PD_PERF_CPU_AFFINITY_MASK) { $env:PD_PERF_CPU_AFFINITY_MASK } else { '0xFF' }),
    [string]$OutputDirectory = 'docs\performance\raw',
    [string]$ReportPath = 'docs\performance\performance-results.json',
    [string[]]$Scenarios = @(),
    [int]$MetricsSampleSeconds = 5,
    [switch]$Run
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$baseUri = $BaseUrl.TrimEnd('/')
$workerMetricsUri = $WorkerMetricsBaseUrl.TrimEnd('/')
$script:workerMetricsUris = @($WorkerMetricsBaseUrl -split '[,;]' | ForEach-Object { $_.Trim().TrimEnd('/') } | Where-Object { $_ })
$outputPath = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    [System.IO.Path]::GetFullPath($OutputDirectory)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $workspace $OutputDirectory))
}
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null

function Test-TcpEndpoint([string]$HostName, [int]$Port) {
    try {
        return [bool](Test-NetConnection -ComputerName $HostName -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue)
    } catch {
        return $false
    }
}

function Get-BasicAuthHeader($Identity) {
    $plain = [System.Text.Encoding]::UTF8.GetBytes("$($Identity.username):$($Identity.password)")
    return @{ Authorization = "Basic $([Convert]::ToBase64String($plain))" }
}

function Get-MetricLines([string]$MetricsBaseUri) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "$MetricsBaseUri/actuator/prometheus" -Headers $script:authHeaders -TimeoutSec 10
        return @($response.Content -split [char]10)
    } catch {
        return $null
    }
}

function Get-MetricValue([string[]]$Lines, [string]$MetricName) {
    if ($null -eq $Lines) { return $null }
    $pattern = '^' + [regex]::Escape($MetricName) + '(?:\{([^}]*)\})?\s+([-+0-9.eE]+)$'
    foreach ($line in $Lines) {
        $match = [regex]::Match($line.Trim(), $pattern)
        if ($match.Success) {
            return [double]::Parse($match.Groups[2].Value, [Globalization.CultureInfo]::InvariantCulture)
        }
    }
    return $null
}

function Get-MetricSum([string[]]$Lines, [string]$MetricName) {
    if ($null -eq $Lines) { return $null }
    $pattern = '^' + [regex]::Escape($MetricName) + '(?:\{([^}]*)\})?\s+([-+0-9.eE]+)$'
    $total = 0.0
    $found = $false
    foreach ($line in $Lines) {
        $match = [regex]::Match($line.Trim(), $pattern)
        if ($match.Success) {
            $total += [double]::Parse($match.Groups[2].Value, [Globalization.CultureInfo]::InvariantCulture)
            $found = $true
        }
    }
    if ($found) { return $total }
    return $null
}

function Get-SystemCpuUtilizationPercent {
    try {
        $loads = @(Get-CimInstance Win32_Processor | ForEach-Object { [double]$_.LoadPercentage })
        if ($loads.Count -gt 0) { return ($loads | Measure-Object -Average).Average }
    } catch {
        return $null
    }
    return $null
}

function Get-MetricHistogramCount([string[]]$Lines, [string]$MetricName) {
    if ($null -eq $Lines) { return $null }
    $pattern = '^' + [regex]::Escape($MetricName + '_count') + '(?:\{([^}]*)\})?\s+([-+0-9.eE]+)$'
    $total = 0.0
    $found = $false
    foreach ($line in $Lines) {
        $match = [regex]::Match($line.Trim(), $pattern)
        if ($match.Success -and [string]::IsNullOrEmpty($match.Groups[1].Value)) {
            $total += [double]::Parse($match.Groups[2].Value, [Globalization.CultureInfo]::InvariantCulture)
            $found = $true
        }
    }
    if ($found) { return $total }
    return Get-MetricValue $Lines ($MetricName + '_count')
}

$script:previousMetricHistogramBuckets = @{}
$script:metricQuantileCache = @{}

function Get-MetricQuantile([string[]]$Lines, [string]$MetricName, [double]$Quantile) {
    if ($null -eq $Lines) { return $null }
    $pattern = '^' + [regex]::Escape($MetricName) + '\{([^}]*)\}\s+([-+0-9.eE]+)$'
    $quantileKey = $Quantile.ToString('0.##', [Globalization.CultureInfo]::InvariantCulture)
    if ($script:metricQuantileCache.ContainsKey($MetricName)) {
        return $script:metricQuantileCache[$MetricName][$quantileKey]
    }

    $bucketPattern = '^' + [regex]::Escape($MetricName) + '_bucket\{([^}]*)\}\s+([-+0-9.eE]+)$'
    $currentBuckets = @{}
    $taggedBuckets = @{}
    foreach ($line in $Lines) {
        $match = [regex]::Match($line.Trim(), $bucketPattern)
        if (-not $match.Success) { continue }
        $labels = @{}
        foreach ($label in [regex]::Matches($match.Groups[1].Value, '([A-Za-z_][A-Za-z0-9_]*)="([^"]*)"')) {
            $labels[$label.Groups[1].Value] = $label.Groups[2].Value
        }
        if ($labels.ContainsKey('le') -and $labels['le'] -ne '+Inf') {
            $bucketSet = if ($labels.Count -eq 1) { $currentBuckets } else { $taggedBuckets }
            if (-not $bucketSet.ContainsKey($labels['le'])) { $bucketSet[$labels['le']] = 0.0 }
            $bucketSet[$labels['le']] += [double]::Parse($match.Groups[2].Value, [Globalization.CultureInfo]::InvariantCulture)
        }
    }
    if ($currentBuckets.Count -eq 0) { $currentBuckets = $taggedBuckets }

    $currentCount = Get-MetricHistogramCount $Lines $MetricName
    if ($currentBuckets.Count -eq 0 -or $null -eq $currentCount) {
        return $null
    }
    $previous = $script:previousMetricHistogramBuckets[$MetricName]
    $counterReset = $null -eq $previous -or $currentCount -lt [double]$previous.total
    $sampleCount = if ($counterReset) { [double]$currentCount } else { [double]$currentCount - [double]$previous.total }
    $bucketDeltas = foreach ($boundary in $currentBuckets.Keys) {
        $previousValue = if ($counterReset -or -not $previous.buckets.ContainsKey($boundary)) { 0.0 }
            else { [double]$previous.buckets[$boundary] }
        [pscustomobject]@{
            upperBoundSeconds = [double]::Parse($boundary, [Globalization.CultureInfo]::InvariantCulture)
            count = [Math]::Max(0.0, [double]$currentBuckets[$boundary] - $previousValue)
        }
    }
    $script:previousMetricHistogramBuckets[$MetricName] = @{ total = [double]$currentCount; buckets = $currentBuckets }
    $sortedBuckets = @($bucketDeltas | Sort-Object upperBoundSeconds)
    $quantiles = @{}
    if ($sampleCount -gt 0) {
        foreach ($requestedQuantile in @(0.5, 0.95, 0.99)) {
            $targetCount = [Math]::Ceiling($sampleCount * $requestedQuantile)
            foreach ($bucket in $sortedBuckets) {
                if ($bucket.count -ge $targetCount) {
                    $key = $requestedQuantile.ToString('0.##', [Globalization.CultureInfo]::InvariantCulture)
                    $quantiles[$key] = 1000 * [double]$bucket.upperBoundSeconds
                    break
                }
            }
        }
    }
    $script:metricQuantileCache[$MetricName] = $quantiles
    return $quantiles[$quantileKey]
}

function Get-ServiceMetricSnapshot {
    $script:metricQuantileCache = @{}
    $apiLines = Get-MetricLines $baseUri
    $workerLines = @()
    foreach ($metricsUri in $script:workerMetricsUris) {
        $instanceLines = Get-MetricLines $metricsUri
        if ($null -ne $instanceLines) { $workerLines += $instanceLines }
    }
    if ($workerLines.Count -eq 0) { $workerLines = $null }
    if ($null -eq $apiLines -and $null -eq $workerLines) { return $null }
    $preferredLines = if ($null -ne $workerLines) { $workerLines } else { $apiLines }
    $fallbackLines = if ($null -ne $workerLines) { $apiLines } else { $null }
    function Get-PreferredMetric([string[]]$Preferred, [string[]]$Fallback, [string]$Name) {
        $value = Get-MetricValue $Preferred $Name
        if ($null -eq $value) { $value = Get-MetricValue $Fallback $Name }
        return $value
    }
    function Get-PreferredQuantile([string[]]$Preferred, [string[]]$Fallback, [string]$Name, [double]$Quantile) {
        $value = Get-MetricQuantile $Preferred $Name $Quantile
        if ($null -eq $value) { $value = Get-MetricQuantile $Fallback $Name $Quantile }
        return $value
    }
    $apiConnectionsActive = Get-MetricSum $apiLines 'hikaricp_connections_active'
    $workerConnectionsActive = Get-MetricSum $workerLines 'hikaricp_connections_active'
    $apiConnectionsPending = Get-MetricSum $apiLines 'hikaricp_connections_pending'
    $workerConnectionsPending = Get-MetricSum $workerLines 'hikaricp_connections_pending'
    $connectionsActive = if ($null -eq $apiConnectionsActive -and $null -eq $workerConnectionsActive) { $null }
        else { [double]$apiConnectionsActive + [double]$workerConnectionsActive }
    $connectionsPending = if ($null -eq $apiConnectionsPending -and $null -eq $workerConnectionsPending) { $null }
        else { [double]$apiConnectionsPending + [double]$workerConnectionsPending }
    return [pscustomobject]@{
        outboxPending = Get-PreferredMetric $preferredLines $fallbackLines 'peakdispatch_outbox_pending'
        outboxOldestAgeSeconds = Get-PreferredMetric $preferredLines $fallbackLines 'peakdispatch_outbox_oldest_age'
        streamPending = Get-PreferredMetric $preferredLines $fallbackLines 'peakdispatch_stream_pending'
        streamBacklog = Get-PreferredMetric $preferredLines $fallbackLines 'peakdispatch_stream_backlog'
        databaseConnectionsActive = $connectionsActive
        databaseConnectionsPending = $connectionsPending
        dispatchDecisionP50Ms = Get-PreferredQuantile $preferredLines $fallbackLines 'peakdispatch_dispatch_decision_seconds' 0.5
        dispatchDecisionP95Ms = Get-PreferredQuantile $preferredLines $fallbackLines 'peakdispatch_dispatch_decision_seconds' 0.95
        dispatchDecisionP99Ms = Get-PreferredQuantile $preferredLines $fallbackLines 'peakdispatch_dispatch_decision_seconds' 0.99
        outboxToResultP95Ms = Get-PreferredQuantile $preferredLines $fallbackLines 'peakdispatch_dispatch_event_to_result_seconds' 0.95
        streamQueueWaitP95Ms = Get-PreferredQuantile $preferredLines $fallbackLines 'peakdispatch_stream_queue_wait_seconds' 0.95
    }
}

function Get-K6Value($Summary, [string]$MetricName, [string]$Statistic) {
    if ($null -eq $Summary -or $null -eq $Summary.metrics) { return $null }
    $metric = $Summary.metrics.PSObject.Properties[$MetricName]
    if ($null -eq $metric) { return $null }
    $valuesProperty = $metric.Value.PSObject.Properties['values']
    $valueSource = if ($null -ne $valuesProperty) { $valuesProperty.Value } else { $metric.Value }
    if ($null -eq $valueSource) { return $null }
    $value = $valueSource.PSObject.Properties[$Statistic]
    if ($null -eq $value) { return $null }
    return [double]$value.Value
}

function Update-Extrema($Extrema, $Snapshot, $SystemCpuUtilizationPercent = $null) {
    if ($null -eq $Snapshot) { return }
    $backlog = [double]$Snapshot.outboxPending + [double]$Snapshot.streamBacklog
    $Extrema.maxBacklog = [Math]::Max($Extrema.maxBacklog, $backlog)
    $Extrema.maxOutboxPending = [Math]::Max($Extrema.maxOutboxPending, [double]$Snapshot.outboxPending)
    $Extrema.maxOutboxOldestAgeSeconds = [Math]::Max($Extrema.maxOutboxOldestAgeSeconds, [double]$Snapshot.outboxOldestAgeSeconds)
    $Extrema.maxRedisStreamPending = [Math]::Max($Extrema.maxRedisStreamPending, [double]$Snapshot.streamPending)
    if ($null -ne $Snapshot.databaseConnectionsPending) {
        $Extrema.maxDatabaseConnectionsPending = if ($null -eq $Extrema.maxDatabaseConnectionsPending) {
            [double]$Snapshot.databaseConnectionsPending
        } else { [Math]::Max([double]$Extrema.maxDatabaseConnectionsPending, [double]$Snapshot.databaseConnectionsPending) }
    }
    if ($null -ne $Snapshot.databaseConnectionsActive) {
        $Extrema.maxDatabaseConnectionsActive = if ($null -eq $Extrema.maxDatabaseConnectionsActive) {
            [double]$Snapshot.databaseConnectionsActive
        } else { [Math]::Max([double]$Extrema.maxDatabaseConnectionsActive, [double]$Snapshot.databaseConnectionsActive) }
    }
    if ($null -ne $SystemCpuUtilizationPercent) {
        $Extrema.maxSystemCpuUtilizationPercent = if ($null -eq $Extrema.maxSystemCpuUtilizationPercent) {
            [double]$SystemCpuUtilizationPercent
        } else { [Math]::Max([double]$Extrema.maxSystemCpuUtilizationPercent, [double]$SystemCpuUtilizationPercent) }
    }
    foreach ($metric in @('dispatchDecisionP50Ms', 'dispatchDecisionP95Ms', 'dispatchDecisionP99Ms', 'outboxToResultP95Ms', 'streamQueueWaitP95Ms')) {
        if ($null -ne $Snapshot.$metric) {
            $Extrema.$metric = if ($null -eq $Extrema.$metric) { [double]$Snapshot.$metric }
                else { [Math]::Max([double]$Extrema.$metric, [double]$Snapshot.$metric) }
        }
    }
}

$users = @()
$usersValid = $false
if (-not [string]::IsNullOrWhiteSpace($UsersJson)) {
    try {
        $users = @($UsersJson | ConvertFrom-Json)
        $usersValid = $users.Count -gt 0
        foreach ($identity in $users) {
            if (-not $identity.username -or -not $identity.password -or -not $identity.merchantId -or -not $identity.regionId) {
                $usersValid = $false
                break
            }
        }
    } catch {
        $usersValid = $false
    }
}

$couriers = @()
$couriersValid = $false
$resolvedCouriersFile = $null
if (-not [string]::IsNullOrWhiteSpace($CouriersJsonFile)) {
    try {
        $resolvedCouriersFile = (Resolve-Path -LiteralPath $CouriersJsonFile).Path
        $couriers = Get-Content -LiteralPath $resolvedCouriersFile -Raw | ConvertFrom-Json
        if ($couriers -isnot [array]) { $couriers = @($couriers) }
        $sample = @($couriers | Select-Object -First 5000)
        $uniqueCourierIds = @($sample | ForEach-Object { $_.courierId } | Select-Object -Unique)
        $uniqueCourierUsernames = @($sample | ForEach-Object { $_.username } | Select-Object -Unique)
        $courierRegions = @($sample | ForEach-Object { $_.regionId } | Select-Object -Unique)
        $couriersValid = $couriers.Count -eq 5000 -and $sample.Count -eq 5000 -and $uniqueCourierIds.Count -eq 5000 `
            -and $uniqueCourierUsernames.Count -eq 5000 -and $courierRegions.Count -eq 1
        if ($couriersValid) {
            $firstUsername = [string]$sample[0].username
            $prefixMatch = [regex]::Match($firstUsername, '^(.+?)(\d{5})$')
            $couriersValid = $prefixMatch.Success
            $usernamePrefix = if ($prefixMatch.Success) { $prefixMatch.Groups[1].Value } else { '' }
            $sharedPassword = [string]$sample[0].password
            if ($env:PD_DEMO_COURIER_ACCOUNT_PASSWORD -and $sharedPassword -cne $env:PD_DEMO_COURIER_ACCOUNT_PASSWORD) {
                $couriersValid = $false
            }
            for ($index = 0; $couriersValid -and $index -lt $sample.Count; $index++) {
                $courier = $sample[$index]
                $expectedUsername = '{0}{1:D5}' -f $usernamePrefix, ($index + 1)
                if (-not $courier.username -or -not $courier.password -or -not $courier.courierId -or -not $courier.regionId -or [long]$courier.presenceGeneration -lt 1 -or [long]$courier.nextSequence -lt 1) {
                    $couriersValid = $false
                    break
                }
                if ($courier.username -cne $expectedUsername -or $courier.password -cne $sharedPassword) {
                    $couriersValid = $false
                    break
                }
            }
        }
    } catch {
        $couriersValid = $false
    }
}

$machine = Get-CimInstance Win32_ComputerSystem
$os = Get-CimInstance Win32_OperatingSystem
$processor = Get-CimInstance Win32_Processor | Select-Object -First 1
$javaLine = 'NOT AVAILABLE'
$javaCommand = Get-Command java -ErrorAction SilentlyContinue
if ($javaCommand) {
    $javaInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $javaInfo.FileName = $javaCommand.Source
    $javaInfo.Arguments = '-version'
    $javaInfo.UseShellExecute = $false
    $javaInfo.RedirectStandardError = $true
    $javaProcess = [System.Diagnostics.Process]::new()
    $javaProcess.StartInfo = $javaInfo
    [void]$javaProcess.Start()
    $javaLine = $javaProcess.StandardError.ReadLine()
    $javaProcess.WaitForExit()
    $javaProcess.Dispose()
}

$mysqlHost = if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { 'localhost' }
$mysqlPort = if ($env:MYSQL_PORT) { [int]$env:MYSQL_PORT } else { 3306 }
$redisHost = if ($env:REDIS_HOST) { $env:REDIS_HOST } else { 'localhost' }
$redisPort = if ($env:REDIS_PORT) { [int]$env:REDIS_PORT } else { 6379 }
$mysqlReachable = Test-TcpEndpoint $mysqlHost $mysqlPort
$redisReachable = Test-TcpEndpoint $redisHost $redisPort
$k6 = Get-Command k6 -ErrorAction SilentlyContinue
if (-not $k6) {
    $bundledK6 = 'E:\perf\peakdispatch\k6-v0.54.0\k6-v0.54.0-windows-amd64\k6.exe'
    if (Test-Path -LiteralPath $bundledK6) { $k6 = [pscustomobject]@{ Source = $bundledK6 } }
}
$docker = Get-Command docker -ErrorAction SilentlyContinue
$appDataMode = $null
$appRuntimeRole = $null
$appJavaVersion = $null
$appRedisEnvironment = $null
$activeProfiles = @()
$rateLimitConfig = $null
$dispatchReady = $false
$authHeaders = if ($usersValid) { Get-BasicAuthHeader $users[0] } else { @{} }
if ($usersValid) {
    try {
        $systemInfo = Invoke-RestMethod -Uri "$baseUri/api/v1/system/info" -Headers $authHeaders -TimeoutSec 10
        $appDataMode = $systemInfo.data.dataMode
        $appRuntimeRole = $systemInfo.data.runtimeRole
        $recovery = Invoke-RestMethod -Uri "$baseUri/api/v1/system/recovery" -Headers $authHeaders -TimeoutSec 10
        $appDataMode = $recovery.data.dataMode
        $appJavaVersion = $recovery.data.javaVersion
        $appRedisEnvironment = $recovery.data.redisEnvironment
        $activeProfiles = @($recovery.data.activeProfiles)
        $dispatchReady = [bool]$recovery.data.dispatchReady
        $rateLimitConfig = [ordered]@{
            enabled = [bool]$recovery.data.rateLimitEnabled
            userPerWindow = [int]$recovery.data.userRateLimitPerWindow
            rolePerWindow = [int]$recovery.data.roleRateLimitPerWindow
            regionPerWindow = [int]$recovery.data.regionRateLimitPerWindow
            apiPerWindow = [int]$recovery.data.apiRateLimitPerWindow
        }
    } catch {
        $appDataMode = $null
    }
}
$apiMetricsInitiallyAvailable = $null -ne (Get-MetricLines $baseUri)
$workerMetricsInitiallyAvailable = $false
foreach ($metricsUri in $script:workerMetricsUris) {
    if ($null -ne (Get-MetricLines $metricsUri)) { $workerMetricsInitiallyAvailable = $true; break }
}
$metricsInitiallyAvailable = $apiMetricsInitiallyAvailable -or $workerMetricsInitiallyAvailable

$definitions = @(
    [ordered]@{ id = 'steady-100-orders-per-second-10-minutes'; scenario = 'steady'; targetRate = 100; durationSeconds = 600; couriers = 0; events = 60000 },
    [ordered]@{ id = 'burst-200-orders-per-second-1-minute'; scenario = 'burst'; targetRate = 200; durationSeconds = 60; couriers = 0; events = 12000; drainSeconds = 300 },
    [ordered]@{ id = 'scale-5000-couriers-20000-events-uniform'; scenario = 'scale-uniform'; targetRate = 200; durationSeconds = 100; couriers = 5000; events = 20000; heartbeatRate = 167 },
    [ordered]@{ id = 'scale-5000-couriers-20000-events-80-percent-hotspot'; scenario = 'scale-hotspot'; targetRate = 200; durationSeconds = 100; couriers = 5000; events = 20000; heartbeatRate = 167 }
)
if ($Scenarios.Count -gt 0) {
    $unknownScenarios = @($Scenarios | Where-Object { $_ -notin @($definitions | ForEach-Object { $_.scenario }) -and $_ -notin @($definitions | ForEach-Object { $_.id }) })
    if ($unknownScenarios.Count -gt 0) { throw "Unknown performance scenario(s): $($unknownScenarios -join ', ')" }
    $definitions = @($definitions | Where-Object { $_.scenario -in $Scenarios -or $_.id -in $Scenarios })
}
$acceptanceProfile = [ordered]@{
    name = 'agent-support-baseline-v1'
    purpose = 'Validate that the backend provides a usable asynchronous foundation for the Agent; this is not a production dispatch SLO.'
    maxErrorRate = 0.01
    steadyOrderRateFraction = 0.99
    scaleOrderRateFraction = 0.99
    burstMinimumOrdersPerSecond = 140
    heartbeatRateFraction = 0.99
    dispatchDecisionP95MaxMs = 30000
    outboxToResultP95MaxMs = 30000
    streamQueueWaitP95MaxMs = 30000
    burstDrainMaxSeconds = 300
    strictProductionSlo = [ordered]@{
        dispatchDecisionP95MaxMs = 1000
        outboxToResultP95MaxMs = 2000
        streamQueueWaitP95MaxMs = 1000
    }
}
$results = [System.Collections.Generic.List[object]]::new()
$bottlenecks = [System.Collections.Generic.List[string]]::new()
$applicationVersion = ([xml](Get-Content -LiteralPath (Join-Path $workspace 'pom.xml') -Raw)).project.version
$k6Script = Join-Path $PSScriptRoot 'performance\peakdispatch-load.js'
$sampleInterval = [Math]::Max(5, $MetricsSampleSeconds)
$preAllocatedVus = if ($env:PD_PERF_PREALLOCATED_VUS) { [int]$env:PD_PERF_PREALLOCATED_VUS } else { 200 }
$maxVus = if ($env:PD_PERF_MAX_VUS) { [int]$env:PD_PERF_MAX_VUS } else { 1000 }
$workerInstanceCount = if ($env:PD_PERF_WORKER_INSTANCES) { [int]$env:PD_PERF_WORKER_INSTANCES } else { 1 }
$apiPoolSize = if ($env:PD_PERF_API_DB_POOL_SIZE) { [int]$env:PD_PERF_API_DB_POOL_SIZE } else { 20 }
$workerPoolSize = if ($env:PD_PERF_WORKER_DB_POOL_SIZE) { [int]$env:PD_PERF_WORKER_DB_POOL_SIZE } else { 20 }
$demoBcryptStrength = if ($env:PD_PERF_DEMO_BCRYPT_STRENGTH) { [int]$env:PD_PERF_DEMO_BCRYPT_STRENGTH } else { 10 }
if ($preAllocatedVus -lt 1 -or $maxVus -lt $preAllocatedVus) {
    throw 'k6 VU limits must be positive and max VUs must be at least preallocated VUs.'
}
$maskDigits = $CpuAffinityMask -replace '^0[xX]', ''
if ($maskDigits -notmatch '^[0-9A-Fa-f]+$') {
    throw 'CpuAffinityMask must be a hexadecimal Windows processor mask, for example 0xFF.'
}
$script:cpuAffinityMaskValue = [Convert]::ToInt64($maskDigits, 16)
if ($script:cpuAffinityMaskValue -le 0) {
    throw 'CpuAffinityMask must select at least one logical processor.'
}

function Set-ProcessCpuAffinity([System.Diagnostics.Process]$TargetProcess) {
    $TargetProcess.ProcessorAffinity = [IntPtr]$script:cpuAffinityMaskValue
}

function Quote-NativeArgument([string]$Argument) {
    return '"' + $Argument.Replace('"', '\"') + '"'
}

foreach ($definition in $definitions) {
    $reasons = [System.Collections.Generic.List[string]]::new()
    if (-not $Run) { $reasons.Add('Pass -Run to execute real load against an isolated simulation environment.') }
    if (-not $mysqlReachable) { $reasons.Add("MySQL $($mysqlHost):$($mysqlPort) is not reachable.") }
    if (-not $redisReachable) { $reasons.Add("Redis $($redisHost):$($redisPort) is not reachable.") }
    if (-not $k6) { $reasons.Add('k6 executable is not installed.') }
    if (-not $usersValid) { $reasons.Add('PD_PERF_USERS must contain simulation merchant username, password, merchantId, and regionId.') }
    if ($appDataMode -ne 'simulation') { $reasons.Add('The authenticated service recovery endpoint did not confirm dataMode=simulation.') }
    if (-not $dispatchReady) { $reasons.Add('The service dispatch recovery gate is not ready.') }
    if (-not $metricsInitiallyAvailable) { $reasons.Add('Authenticated /actuator/prometheus metrics are unavailable.') }
    if ($definition.couriers -gt 0 -and -not $couriersValid) {
        $reasons.Add('Scale scenarios require a local fixture with 5,000 unique online couriers in one region.')
    }
    if ($usersValid -and $definition.scenario -like 'scale-*' -and @($users | ForEach-Object { $_.regionId } | Select-Object -Unique).Count -ne 1) {
        $reasons.Add('Scale order identities must target one simulation region.')
    }
    if ($rateLimitConfig -and $rateLimitConfig.enabled) {
        $userCapacity = $users.Count * $rateLimitConfig.userPerWindow
        if ($rateLimitConfig.apiPerWindow -lt $definition.targetRate) {
            $reasons.Add("API rate limit $($rateLimitConfig.apiPerWindow)/s is below target $($definition.targetRate)/s.")
        }
        if ($rateLimitConfig.rolePerWindow -lt $definition.targetRate) {
            $reasons.Add("Role rate limit $($rateLimitConfig.rolePerWindow)/s is below target $($definition.targetRate)/s.")
        }
        if ($rateLimitConfig.regionPerWindow -lt $definition.targetRate) {
            $reasons.Add("Region rate limit $($rateLimitConfig.regionPerWindow)/s is below target $($definition.targetRate)/s.")
        }
        if ($userCapacity -lt $definition.targetRate) {
            $reasons.Add("Merchant user budgets total $userCapacity/s, below target $($definition.targetRate)/s.")
        }
    }

    $status = 'NOT RUN'
    $acceptance = 'NOT RUN'
    $rawResult = $null
    $summaryResult = $null
    $serviceMetricsRaw = $null
    $reason = $reasons -join ' '
    $measurements = [ordered]@{
        throughputPerSecond = $null
        ordersPerSecond = $null
        heartbeatsPerSecond = $null
        latencyMs = [ordered]@{ p50 = $null; p95 = $null; p99 = $null }
        errorRate = $null
        dispatchDecisionMs = [ordered]@{ p50 = $null; p95 = $null; p99 = $null }
        outboxToResultP95Ms = $null
        streamQueueWaitP95Ms = $null
        maxBacklog = $null
        maxOutboxPending = $null
        maxOutboxOldestAgeSeconds = $null
        maxRedisStreamPending = $null
        maxDatabaseConnectionsPending = $null
        maxDatabaseConnectionsActive = $null
        maxSystemCpuUtilizationPercent = $null
        backlogClearedWithinSeconds = $null
    }

    if ($reasons.Count -eq 0) {
        $stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
        $rawFile = "$stamp-$($definition.scenario).json"
        $summaryFile = "$stamp-$($definition.scenario)-summary.json"
        $consoleFile = "$stamp-$($definition.scenario)-console.txt"
        $metricsFile = "$stamp-$($definition.scenario)-service-metrics.jsonl"
        $rawPath = Join-Path $outputPath $rawFile
        $summaryPath = Join-Path $outputPath $summaryFile
        $consolePath = Join-Path $outputPath $consoleFile
        $metricsPath = Join-Path $outputPath $metricsFile
        $rawResult = "docs/performance/raw/$rawFile"
        $summaryResult = "docs/performance/raw/$summaryFile"
        $serviceMetricsRaw = "docs/performance/raw/$metricsFile"
        $env:PD_PERF_SCENARIO = $definition.scenario
        $env:PD_PERF_BASE_URL = $baseUri
        $env:PD_PERF_USERS = $UsersJson
        $env:PD_PERF_COURIERS_FILE = if ($resolvedCouriersFile) { $resolvedCouriersFile } else { '' }
        $env:PD_PERF_PREALLOCATED_VUS = [string]$preAllocatedVus
        $env:PD_PERF_MAX_VUS = [string]$maxVus

        $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $k6.Source
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $arguments = @('run', '--out', "json=$rawPath", '--summary-export', $summaryPath, $k6Script)
        $startInfo.Arguments = ($arguments | ForEach-Object { Quote-NativeArgument $_ }) -join ' '
        $process = [System.Diagnostics.Process]::new()
        $process.StartInfo = $startInfo
        [void]$process.Start()
        Set-ProcessCpuAffinity $process
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $extrema = @{ maxBacklog = 0.0; maxOutboxPending = 0.0; maxOutboxOldestAgeSeconds = 0.0; maxRedisStreamPending = 0.0;
            maxDatabaseConnectionsPending = $null; maxDatabaseConnectionsActive = $null; maxSystemCpuUtilizationPercent = $null;
            dispatchDecisionP50Ms = $null; dispatchDecisionP95Ms = $null; dispatchDecisionP99Ms = $null;
            outboxToResultP95Ms = $null; streamQueueWaitP95Ms = $null }
        $samples = 0
        while (-not $process.HasExited) {
            $snapshot = Get-ServiceMetricSnapshot
            if ($null -ne $snapshot) {
                $cpuPercent = Get-SystemCpuUtilizationPercent
                Update-Extrema $extrema $snapshot $cpuPercent
                $samples++
                $metricSample = [ordered]@{ timestampUtc = [DateTime]::UtcNow.ToString('o'); scenario = $definition.scenario;
                    systemCpuUtilizationPercent = $cpuPercent; metrics = $snapshot }
                [System.IO.File]::AppendAllText($metricsPath, ($metricSample | ConvertTo-Json -Compress -Depth 5) + [Environment]::NewLine)
            }
            Start-Sleep -Seconds $sampleInterval
        }
        $process.WaitForExit()
        $exitCode = $process.ExitCode
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        $process.Dispose()
        [System.IO.File]::WriteAllText($consolePath, $stdout + [Environment]::NewLine + $stderr)
        $summary = if (Test-Path -LiteralPath $summaryPath) {
            Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
        } else { $null }
        $orderP50 = Get-K6Value $summary 'peakdispatch_order_request_ms' 'p(50)'
        $orderP95 = Get-K6Value $summary 'peakdispatch_order_request_ms' 'p(95)'
        $orderP99 = Get-K6Value $summary 'peakdispatch_order_request_ms' 'p(99)'
        $errorRate = Get-K6Value $summary 'peakdispatch_load_error_rate' 'value'
        $totalRate = Get-K6Value $summary 'http_reqs' 'rate'
        $orderCount = Get-K6Value $summary 'peakdispatch_orders_accepted' 'count'
        $heartbeatCount = Get-K6Value $summary 'peakdispatch_heartbeats_accepted' 'count'
        $droppedIterations = Get-K6Value $summary 'dropped_iterations' 'count'
        $runMetrics = Get-ServiceMetricSnapshot
        if ($null -ne $runMetrics) {
            $cpuPercent = Get-SystemCpuUtilizationPercent
            Update-Extrema $extrema $runMetrics $cpuPercent
            $samples++
        }
        $ordersPerSecond = if ($orderCount -ne $null) { $orderCount / $definition.durationSeconds } else { $null }
        $heartbeatsPerSecond = if ($heartbeatCount -ne $null) { $heartbeatCount / $definition.durationSeconds } else { $null }
        $measurements.throughputPerSecond = $totalRate
        $measurements.ordersPerSecond = $ordersPerSecond
        $measurements.heartbeatsPerSecond = $heartbeatsPerSecond
        $measurements.latencyMs = [ordered]@{ p50 = $orderP50; p95 = $orderP95; p99 = $orderP99 }
        $measurements.errorRate = $errorRate
        $measurements.dispatchDecisionMs = [ordered]@{
            p50 = $extrema.dispatchDecisionP50Ms
            p95 = $extrema.dispatchDecisionP95Ms
            p99 = $extrema.dispatchDecisionP99Ms
        }
        $measurements.outboxToResultP95Ms = $extrema.outboxToResultP95Ms
        $measurements.streamQueueWaitP95Ms = $extrema.streamQueueWaitP95Ms

        $drainedSeconds = $null
        if ($definition.scenario -eq 'burst' -and $samples -gt 0) {
            $drainWatch = [Diagnostics.Stopwatch]::StartNew()
            $zeroSamples = 0
            while ($drainWatch.Elapsed.TotalSeconds -lt $definition.drainSeconds -and $zeroSamples -lt 3) {
                Start-Sleep -Seconds $sampleInterval
                $snapshot = Get-ServiceMetricSnapshot
                if ($null -eq $snapshot) { break }
                $cpuPercent = Get-SystemCpuUtilizationPercent
                Update-Extrema $extrema $snapshot $cpuPercent
                $metricSample = [ordered]@{ timestampUtc = [DateTime]::UtcNow.ToString('o'); scenario = $definition.scenario;
                    phase = 'drain'; systemCpuUtilizationPercent = $cpuPercent; metrics = $snapshot }
                [System.IO.File]::AppendAllText($metricsPath, ($metricSample | ConvertTo-Json -Compress -Depth 5) + [Environment]::NewLine)
                if ($snapshot.outboxPending -eq 0 -and $snapshot.streamBacklog -eq 0 -and $snapshot.streamPending -eq 0) {
                    $zeroSamples++
                } else {
                    $zeroSamples = 0
                }
            }
            $drainWatch.Stop()
            if ($zeroSamples -ge 3) { $drainedSeconds = [Math]::Round($drainWatch.Elapsed.TotalSeconds, 1) }
            $measurements.backlogClearedWithinSeconds = $drainedSeconds
            $measurements.maxDatabaseConnectionsPending = $extrema.maxDatabaseConnectionsPending
            $measurements.maxDatabaseConnectionsActive = $extrema.maxDatabaseConnectionsActive
            $measurements.maxSystemCpuUtilizationPercent = $extrema.maxSystemCpuUtilizationPercent
        }

        $measurements.maxBacklog = [long]$extrema.maxBacklog
        $measurements.maxOutboxPending = [long]$extrema.maxOutboxPending
        $measurements.maxOutboxOldestAgeSeconds = [long]$extrema.maxOutboxOldestAgeSeconds
        $measurements.maxRedisStreamPending = [long]$extrema.maxRedisStreamPending
        $measurements.maxDatabaseConnectionsPending = $extrema.maxDatabaseConnectionsPending
        $measurements.maxDatabaseConnectionsActive = $extrema.maxDatabaseConnectionsActive
        $measurements.maxSystemCpuUtilizationPercent = $extrema.maxSystemCpuUtilizationPercent
        $minimumOrderRate = if ($definition.scenario -eq 'burst') {
            $acceptanceProfile.burstMinimumOrdersPerSecond
        } elseif ($definition.scenario -like 'scale-*') {
            $definition.targetRate * $acceptanceProfile.scaleOrderRateFraction
        } else {
            $definition.targetRate * $acceptanceProfile.steadyOrderRateFraction
        }
        $loadPassed = $errorRate -ne $null -and $errorRate -lt $acceptanceProfile.maxErrorRate -and $ordersPerSecond -ge $minimumOrderRate
        if ($definition.heartbeatRate) {
            $loadPassed = $loadPassed -and $heartbeatsPerSecond -ge ($definition.heartbeatRate * $acceptanceProfile.heartbeatRateFraction)
        }
        $serviceMetricsComplete = $samples -gt 0 -and $null -ne $runMetrics -and $measurements.dispatchDecisionMs.p50 -ne $null -and $measurements.dispatchDecisionMs.p95 -ne $null -and $measurements.dispatchDecisionMs.p99 -ne $null -and $measurements.outboxToResultP95Ms -ne $null -and $measurements.streamQueueWaitP95Ms -ne $null
        if (-not $loadPassed) {
            $acceptance = 'FAIL'
            $reason = "Agent support load gate failed: errorRate=$errorRate, ordersPerSecond=$ordersPerSecond, requiredOrderRate=$minimumOrderRate, heartbeatsPerSecond=$heartbeatsPerSecond, k6Exit=$exitCode, droppedIterations=$droppedIterations."
        } elseif (-not $serviceMetricsComplete) {
            $acceptance = 'NOT RUN'
            $reason = 'Load ran, but service-side percentile metrics were missing; no performance threshold is claimed.'
        } else {
            $withinLatency = $measurements.dispatchDecisionMs.p95 -le $acceptanceProfile.dispatchDecisionP95MaxMs -and $measurements.outboxToResultP95Ms -le $acceptanceProfile.outboxToResultP95MaxMs -and $measurements.streamQueueWaitP95Ms -le $acceptanceProfile.streamQueueWaitP95MaxMs
            $withinDrain = $definition.scenario -ne 'burst' -or ($drainedSeconds -ne $null -and $drainedSeconds -le $acceptanceProfile.burstDrainMaxSeconds)
            $acceptance = if ($withinLatency -and $withinDrain) { 'PASS' } else { 'FAIL' }
            $reason = "Agent support baseline evaluated; sampled $samples service metric windows; latencyThresholds=$withinLatency; backlogDrain=$drainedSeconds; strictProductionSlo=observation-only."
        }
        $status = $acceptance
        if ($measurements.dispatchDecisionMs.p95 -gt $acceptanceProfile.strictProductionSlo.dispatchDecisionP95MaxMs) {
            $bottlenecks.Add("Strict production dispatch decision P95 remains above $($acceptanceProfile.strictProductionSlo.dispatchDecisionP95MaxMs) ms; Agent support baseline permits up to $($acceptanceProfile.dispatchDecisionP95MaxMs) ms.")
        }
        if ($measurements.outboxToResultP95Ms -gt $acceptanceProfile.strictProductionSlo.outboxToResultP95MaxMs) {
            $bottlenecks.Add("Strict production Outbox-to-result P95 remains above $($acceptanceProfile.strictProductionSlo.outboxToResultP95MaxMs) ms; measured $([Math]::Round($measurements.outboxToResultP95Ms, 1)) ms.")
        }
        if ($measurements.streamQueueWaitP95Ms -gt $acceptanceProfile.strictProductionSlo.streamQueueWaitP95MaxMs) {
            $bottlenecks.Add("Strict production Stream-to-Worker P95 remains above $($acceptanceProfile.strictProductionSlo.streamQueueWaitP95MaxMs) ms; measured $([Math]::Round($measurements.streamQueueWaitP95Ms, 1)) ms.")
        }
        if ($definition.scenario -eq 'burst' -and $drainedSeconds -eq $null) {
            $bottlenecks.Add("Burst backlog did not clear within the configured $($acceptanceProfile.burstDrainMaxSeconds)-second drain window.")
        }
        if ($extrema.maxDatabaseConnectionsPending -gt 0) {
            $bottlenecks.Add("MySQL Hikari connection pool pending reached $([long]$extrema.maxDatabaseConnectionsPending) connections.")
        }
        if ($extrema.maxSystemCpuUtilizationPercent -ge 90) {
            $bottlenecks.Add("Host CPU utilization reached $([Math]::Round($extrema.maxSystemCpuUtilizationPercent, 1))%.")
        }
        if ($measurements.maxBacklog -gt 0 -and ($measurements.outboxToResultP95Ms -gt $acceptanceProfile.outboxToResultP95MaxMs -or $measurements.streamQueueWaitP95Ms -gt $acceptanceProfile.streamQueueWaitP95MaxMs)) {
            $bottlenecks.Add("Outbox and Stream backlog peaked at $($measurements.maxBacklog) messages.")
        }
    }

    $results.Add([ordered]@{
        id = $definition.id
        status = $status
        target = $definition
        rawResult = $rawResult
        summaryResult = $summaryResult
        serviceMetricsRaw = $serviceMetricsRaw
        reason = $reason
        measurements = $measurements
        acceptance = $acceptance
    })
}

$version = [ordered]@{
    application = $applicationVersion
    javaClientRuntime = $javaLine
    javaServiceRuntime = $appJavaVersion
    activeProfiles = $activeProfiles
}
$runStatuses = @($results | ForEach-Object { $_.status })
$overallStatus = if ($runStatuses -contains 'FAIL') { 'FAIL' }
    elseif ($runStatuses.Count -gt 0 -and @($runStatuses | Where-Object { $_ -ne 'PASS' }).Count -eq 0) { 'PASS' }
    else { 'NOT RUN' }
$bottleneckAnalysis = if ($overallStatus -eq 'NOT RUN' -and $bottlenecks.Count -eq 0) {
    'NOT RUN: real MySQL/Redis load and service percentile metrics were unavailable.'
} elseif ($bottlenecks.Count -eq 0) {
    'All scenarios met the Agent support baseline; strict production dispatch latency remains an observation-only target.'
} else {
    $bottlenecks -join ' '
}
$report = [ordered]@{
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    status = $overallStatus
    acceptanceProfile = $acceptanceProfile
    environment = [ordered]@{
        machineName = $machine.Name
        cpu = $processor.Name.Trim()
        logicalProcessors = $machine.NumberOfLogicalProcessors
        memoryBytes = $machine.TotalPhysicalMemory
        os = "$($os.Caption) $($os.Version) build $($os.BuildNumber)"
        version = $version
        runtimeRole = $appRuntimeRole
        dataMode = $appDataMode
        redisNamespace = $appRedisEnvironment
        dispatchReady = $dispatchReady
        rateLimitConfiguration = $rateLimitConfig
        resourceConfiguration = [ordered]@{
            cpuAffinityMask = $CpuAffinityMask
            loadGeneratorCpuAffinityMask = $CpuAffinityMask
            serviceCpuAffinityMask = $env:PD_PERF_SERVICE_CPU_AFFINITY_MASK
            workerInstanceCount = $workerInstanceCount
            javaMaxHeap = $env:PD_PERF_JAVA_MAX_HEAP
            springTaskSchedulerPoolSize = if ($env:PD_SCHEDULING_POOL_SIZE) { [int]$env:PD_SCHEDULING_POOL_SIZE } else { 4 }
            demoBcryptStrength = $demoBcryptStrength
            redisProjectionRecoveryDelayMs = if ($env:PD_PERF_REDIS_RECONCILE_DELAY_MS) { [int]$env:PD_PERF_REDIS_RECONCILE_DELAY_MS } else { $null }
            lifecycleScanDelayMs = if ($env:PD_LIFECYCLE_SCAN_DELAY_MS) { [int]$env:PD_LIFECYCLE_SCAN_DELAY_MS } else { $null }
            dispatchScanDelayMs = if ($env:PD_DISPATCH_SCAN_DELAY_MS) { [int]$env:PD_DISPATCH_SCAN_DELAY_MS } else { $null }
            outboxPublishDelayMs = if ($env:PD_OUTBOX_DELAY_MS) { [int]$env:PD_OUTBOX_DELAY_MS } else { 500 }
            streamPollDelayMs = if ($env:PD_STREAM_POLL_DELAY_MS) { [int]$env:PD_STREAM_POLL_DELAY_MS } else { 250 }
            redisStreamConsumerBatchSize = if ($env:PD_STREAM_BATCH_SIZE) { [int]$env:PD_STREAM_BATCH_SIZE } else { 10 }
            mysqlApiPoolMaximum = $apiPoolSize
            mysqlWorkerPoolMaximum = $workerPoolSize
            mysqlConnectionPoolMaximum = $apiPoolSize + ($workerInstanceCount * $workerPoolSize)
            mysqlMaxConnections = if ($env:PD_PERF_MYSQL_MAX_CONNECTIONS) { [int]$env:PD_PERF_MYSQL_MAX_CONNECTIONS } else { $null }
            mysqlInnoDbBufferPoolBytes = if ($env:PD_PERF_MYSQL_BUFFER_POOL_BYTES) { [long]$env:PD_PERF_MYSQL_BUFFER_POOL_BYTES } else { $null }
            redisMaxMemory = $env:PD_PERF_REDIS_MAXMEMORY
            k6PreAllocatedVus = $preAllocatedVus
            k6MaxVus = $maxVus
            outboxPublishBatchSize = 100
        }
        mysql = [ordered]@{
            host = $mysqlHost
            port = $mysqlPort
            database = $env:MYSQL_DATABASE
            version = $env:PD_PERF_MYSQL_VERSION
            reachable = $mysqlReachable
            boundToLoopback = $mysqlHost -in @('localhost', '127.0.0.1', '::1')
            target = 'MySQL 8.4.x'
        }
        redis = [ordered]@{
            host = $redisHost
            port = $redisPort
            reachable = $redisReachable
            target = 'Redis 7.x'
            implementation = $env:PD_PERF_REDIS_IMPLEMENTATION
            redisVersion = $env:PD_PERF_REDIS_VERSION
            valkeyApiVersion = $env:PD_PERF_VALKEY_API_VERSION
            compatibilityNote = if ($env:PD_PERF_REDIS_IMPLEMENTATION -like 'Memurai*') {
                'Memurai provides a Redis-compatible API; this is not the upstream Redis server.'
            } else { $null }
        }
        workerMetricsBaseUrl = $workerMetricsUri
        dockerAvailable = [bool]$docker
        k6Version = if ($k6) { (& $k6.Source version 2>$null | Select-Object -First 1).ToString() } else { $null }
        metricsInitiallyAvailable = $metricsInitiallyAvailable
        apiMetricsInitiallyAvailable = $apiMetricsInitiallyAvailable
        workerMetricsInitiallyAvailable = $workerMetricsInitiallyAvailable
        metricsSampleSeconds = $sampleInterval
        streamBacklogDefinition = 'Sum of Redis consumer-group lag and pending messages for the dispatch and events streams; acknowledged history is excluded.'
        cpuAffinityMask = $CpuAffinityMask
        servicePercentileAggregation = 'Maximum of per-sample P50/P95/P99 snapshots during load and burst drain; histogram percentiles use the upper bound of the first bucket meeting the quantile, based on bucket-count deltas since the prior sample.'
    }
    workloadData = [ordered]@{
        orders = 'Synthetic authorized order requests with a unique Idempotency-Key per event.'
        courierScale = 'Scale runs require 5,000 unique online riders in one simulation region; nextSequence and presenceGeneration are loaded from the fixture.'
        scaleEvents = '20,000 orders per distribution plus approximately 16,700 staggered heartbeats at 167/s over 100 seconds.'
        authentication = "Generated in-memory demo rider accounts share a fixture password; benchmark-only BCrypt strength is $demoBcryptStrength."
        eventDistributions = @('uniform 100x100 pickup grid', '80% hotspot-cell and 20% uniform pickup grid')
        seed = 'Deterministic scenario iteration index; no random data generator.'
        courierFixture = if ($couriersValid) { '5,000 unique ordered usernames and courier IDs, one shared password, one region; fixture contents are not copied to the report.' } else { 'NOT RUN: no valid 5,000-rider fixture loaded.' }
    }
    definitions = $results
    bottleneckAnalysis = $bottleneckAnalysis
}
$resolvedReportPath = if ([System.IO.Path]::IsPathRooted($ReportPath)) { [System.IO.Path]::GetFullPath($ReportPath) } else { [System.IO.Path]::GetFullPath((Join-Path $workspace $ReportPath)) }
$reportDirectory = Split-Path -Parent $resolvedReportPath
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $resolvedReportPath -Encoding utf8
Write-Output "Performance report $($overallStatus): $resolvedReportPath"

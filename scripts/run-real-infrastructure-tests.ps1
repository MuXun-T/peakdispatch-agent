param(
    [switch]$RunMaven,
    [switch]$ReadExistingReports
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$resultDirectory = Join-Path $workspace 'docs/test-results'
New-Item -ItemType Directory -Force -Path $resultDirectory | Out-Null

function Test-Port([int]$Port) {
    return $null -ne (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

function Get-SuiteStatus([string]$ClassName) {
    if (-not $script:RunMaven) { return 'NOT RUN' }
    $reportPath = Join-Path $workspace "peakdispatch-api-worker/target/surefire-reports/TEST-$ClassName.xml"
    if (-not (Test-Path $reportPath)) { return 'NOT RUN' }
    [xml]$report = Get-Content -Raw $reportPath
    if ([int]$report.testsuite.failures -gt 0 -or [int]$report.testsuite.errors -gt 0) { return 'FAIL' }
    if ([int]$report.testsuite.tests -eq 0 -or [int]$report.testsuite.tests -eq [int]$report.testsuite.skipped) { return 'NOT RUN' }
    if ([int]$report.testsuite.skipped -gt 0) { return 'NOT RUN' }
    return 'PASS'
}

function Get-CaseStatus([string]$ClassName, [string]$MethodName) {
    if (-not $script:RunMaven) { return 'NOT RUN' }
    $reportPath = Join-Path $workspace "peakdispatch-api-worker/target/surefire-reports/TEST-$ClassName.xml"
    if (-not (Test-Path $reportPath)) { return 'NOT RUN' }
    [xml]$report = Get-Content -Raw $reportPath
    $case = @($report.testsuite.testcase | Where-Object { $_.name -eq $MethodName }) | Select-Object -First 1
    if ($null -eq $case) { return 'NOT RUN' }
    if ($case.failure -or $case.error) { return 'FAIL' }
    if ($case.skipped) { return 'NOT RUN' }
    return 'PASS'
}

function New-TestCase([string]$Id, [string]$ClassName, [string]$MethodName) {
    return [ordered]@{
        id = $Id
        method = $MethodName
        status = Get-CaseStatus $ClassName $MethodName
    }
}

$dockerCommand = $null -ne (Get-Command docker -ErrorAction SilentlyContinue)
$docker = $false
if ($dockerCommand) {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & docker info --format '{{.ServerVersion}}' *> $null
    $docker = $LASTEXITCODE -eq 0
    $ErrorActionPreference = $previousErrorAction
}
$infrastructure = [ordered]@{
    docker = if ($docker) { 'AVAILABLE' } elseif ($dockerCommand) { 'DAEMON_UNAVAILABLE' } else { 'NOT_INSTALLED' }
    mysql = if (Test-Port 3306) { 'TCP_LISTENING' } else { 'NOT_AVAILABLE' }
    redis = if (Test-Port 6379) { 'TCP_LISTENING' } else { 'NOT_AVAILABLE' }
    redisCluster = if ($env:PD_REDIS_CLUSTER_HOST -and $env:PD_REDIS_CLUSTER_PORT) { 'CONFIGURED' } else { 'NOT_CONFIGURED' }
    elasticsearch = if (Test-Port 9200) { 'TCP_LISTENING' } else { 'NOT_AVAILABLE' }
}

$maven = [ordered]@{ command = '.\mvnw.cmd -B -ntp clean verify'; status = 'NOT RUN'; exitCode = $null }
$script:RunMaven = [bool]($RunMaven -or $ReadExistingReports)
if ($RunMaven) {
    Push-Location $workspace
    try {
        $previousErrorAction = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        & .\mvnw.cmd -B -ntp clean verify *> (Join-Path $resultDirectory 'clean-verify.log')
        $maven.exitCode = $LASTEXITCODE
        $maven.status = if ($LASTEXITCODE -eq 0) { 'PASS' } else { 'FAIL' }
        $ErrorActionPreference = $previousErrorAction
    } finally {
        Pop-Location
    }
}
elseif ($ReadExistingReports) {
    $logPath = Join-Path $resultDirectory 'clean-verify.log'
    if (Test-Path $logPath) {
        $log = Get-Content -Raw $logPath
        if ($log.Contains('[INFO] BUILD SUCCESS')) {
            $maven.status = 'PASS'
            $maven.exitCode = 0
        } elseif ($log.Contains('[INFO] BUILD FAILURE')) {
            $maven.status = 'FAIL'
            $maven.exitCode = 1
        }
    }
}

$javaVersion = 'NOT AVAILABLE'
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
    $javaVersion = $javaProcess.StandardError.ReadLine()
    $javaProcess.WaitForExit()
    $javaProcess.Dispose()
}
$runtime = [ordered]@{ status = 'NOT RUN'; javaVersion = $javaVersion; javaHome = $env:JAVA_HOME }
if ($javaVersion -match '"21(?:\.|"|-)') {
    $runtime.status = $maven.status
    $runtime.evidence = 'Full Maven clean verify executed with the active Java 21 runtime'
} else {
    $runtime.evidence = 'A Java 21 runtime is required; --release 21 compilation on another JDK is not runtime verification'
}

$realMySqlRedis = Get-SuiteStatus 'com.peakdispatch.api.infra.RealMySqlRedisConcurrencyIntegrationTest'
$redisRecovery = Get-SuiteStatus 'com.peakdispatch.api.redis.RedisRecoveryIntegrationTest'
$redisCluster = Get-CaseStatus 'com.peakdispatch.api.redis.RedisClusterScriptContractTest' `
    'everyLuaKeyGroupHasOneActualClusterSlotAndRunsWithoutCrossSlot'
$tests = @(
    [ordered]@{ id = 'REAL-MYSQL-REDIS-CONCURRENCY'; status = $realMySqlRedis; evidence = 'RealMySqlRedisConcurrencyIntegrationTest (MySQL 8.4.4 + Redis 7.4.2 Testcontainers)' },
    [ordered]@{ id = 'REAL-REDIS-RECOVERY'; status = $redisRecovery; evidence = 'RedisRecoveryIntegrationTest (Redis 7.4.2 Testcontainers; H2 database, not a MySQL result)' },
    [ordered]@{ id = 'REAL-REDIS-CLUSTER-SLOT'; status = $redisCluster; evidence = 'RedisClusterScriptContractTest with PD_REDIS_CLUSTER_HOST/PORT' },
    [ordered]@{ id = 'REAL-ELASTICSEARCH'; status = 'NOT RUN'; evidence = 'No live Elasticsearch test is configured' }
)
$mysqlCaseClass = 'com.peakdispatch.api.infra.RealMySqlRedisConcurrencyIntegrationTest'
$redisRecoveryClass = 'com.peakdispatch.api.redis.RedisRecoveryIntegrationTest'
$mysqlRedisCases = @(
    (New-TestCase 'MYSQL-ROW-LOCK-CAS-LAST-CAPACITY' $mysqlCaseClass 'mysqlRowLockAndVersionCas_allowOnlyOneLastCapacityOffer'),
    (New-TestCase 'MYSQL-DUPLICATE-OFFER-ACCEPT' $mysqlCaseClass 'duplicateAccepts_createOneAssignmentAndOneCapacityCommit'),
    (New-TestCase 'MYSQL-OFFER-ACCEPT-TIMEOUT-RACE' $mysqlCaseClass 'acceptAndTimeoutAtExpiry_competeThroughOfferRowLock'),
    (New-TestCase 'MYSQL-GENERATION-ACCEPT-RACE' $mysqlCaseClass 'generationChange_racingAccept_neverAcceptsOldGenerationAfterFence'),
    (New-TestCase 'MYSQL-DUAL-WORKER-SAME-ORDER' $mysqlCaseClass 'twoWorkersForOneOrder_createAtMostOneOffer'),
    (New-TestCase 'REDIS-LEASE-DB-ROLLBACK' $mysqlCaseClass 'redisLeaseRollback_releasesProvisionalLease_andCapacityRemainsDbAuthoritative'),
    (New-TestCase 'MYSQL-REBUILD-LOST-CAPACITY-KEY' $mysqlCaseClass 'capacityKeyLossIsRebuiltFromMysqlAndFirstReserveFailsClosed'),
    (New-TestCase 'REDIS-OUTAGE-RECOVERY-AND-DISPATCH-GATE' $mysqlCaseClass 'databaseCommitSurvivesRedisNotificationFailureAndOutboxReplaysAfterRedisReturns'),
    (New-TestCase 'STREAM-DUPLICATE-CONSUMPTION-INBOX' $mysqlCaseClass 'duplicateStreamDeliveryHasOneDatabaseEffectAndOneInboxCompletion'),
    (New-TestCase 'OLD-COURIER-GENERATION-FENCE' $mysqlCaseClass 'newCourierGenerationRejectsOldOfferAndCannotRestoreOldGeoIndex'),
    (New-TestCase 'MYSQL-COMMIT-BEFORE-ACK-RECOVERY' $mysqlCaseClass 'dbCommitBeforeAckFailureIsRecoveredByInboxWithoutRepeatingOrderEffect'),
    (New-TestCase 'MYSQL-INBOX-DLQ-REPLAY' $mysqlCaseClass 'mysqlInboxAndRedisDlqReplayAreIdempotentAfterHandlerFailure'),
    (New-TestCase 'MYSQL-DLQ-FAILURE-PENDING-RECOVERY' $mysqlCaseClass 'failedDlqWriteLeavesMysqlInboxAndStreamPendingUntilDlqRecovers'),
    (New-TestCase 'MYSQL-OUTBOX-DUPLICATE-XADD' $mysqlCaseClass 'outboxXaddBeforePublishedMarkCanDuplicateStreamEntryButInboxEffectIsOnce')
)
$redisRecoveryCases = @(
    (New-TestCase 'REDIS-PRESENCE-GEO-HEARTBEAT-CAPACITY-LEASE-LUA' $redisRecoveryClass 'luaPresenceGeoFenceCapacityAndLeaseRecoveryAreAtomic'),
    (New-TestCase 'REDIS-DELAYED-OFFER-COMPLETE-IDEMPOTENT' $redisRecoveryClass 'redisOfferDelayQueueClaimsAndCompletesDueOfferIdempotently'),
    (New-TestCase 'REDIS-DELAYED-OFFER-CLAIM-FENCING' $redisRecoveryClass 'expiredOfferDelayClaimCanBeReclaimedAndReleaseIsAtomicAndTokenFenced'),
    (New-TestCase 'REDIS-DELAYED-OFFER-BATCH-LIMIT' $redisRecoveryClass 'delayedOfferClaimsRespectBatchLimitAcrossDueAndExpiredItems'),
    (New-TestCase 'REDIS-OLD-GENERATION-GEO-FENCE' $redisRecoveryClass 'oldGenerationCannotRecreateGeoAfterSessionFence'),
    (New-TestCase 'REDIS-HEARTBEAT-EXPIRY-GEO-FENCE' $redisRecoveryClass 'expiredHeartbeatIsRemovedFromGeoAndFenceRejectsDelayedIndexWrite'),
    (New-TestCase 'REDIS-PROJECTION-RECONCILIATION' $redisRecoveryClass 'reconciliationRepairsSameSequenceCoordinateDrift'),
    (New-TestCase 'REDIS-INBOX-ACK-FAILURE-XAUTOCLAIM' $redisRecoveryClass 'duplicateEventIdProcessesOnceAndAckFailureRecoversThroughXAutoClaim'),
    (New-TestCase 'REDIS-HANDLER-FAILURE-XAUTOCLAIM' $redisRecoveryClass 'failedHandlerRemainsPendingThenSucceedsOnXAUTOCLAIMRetry'),
    (New-TestCase 'REDIS-DLQ-ACK-ORDER' $redisRecoveryClass 'failedHandlerIsAckedOnlyAfterConsumerDlqWriteSucceeds'),
    (New-TestCase 'REDIS-DLQ-REPLAY-INBOX-DEDUPE' $redisRecoveryClass 'consumerDlqReplayRequeuesTheEventAndDeduplicatesTheInboxRecord'),
    (New-TestCase 'REDIS-DLQ-UNAVAILABLE-PENDING' $redisRecoveryClass 'unavailableConsumerDlqLeavesMalformedRecordPending'),
    (New-TestCase 'REDIS-XAUTOCLAIM-PAGING' $redisRecoveryClass 'xAutoClaimContinuesAcrossPendingPages'),
    (New-TestCase 'REDIS-AVAILABILITY-FAIL-CLOSED' $redisRecoveryClass 'redisAvailabilityProbeFailsClosedWhenRedisIsUnavailable')
)

$faults = @(
    [ordered]@{ scenario = 'Redis unavailable; dispatch gate pauses'; status = Get-CaseStatus 'com.peakdispatch.api.infra.RealMySqlRedisConcurrencyIntegrationTest' 'databaseCommitSurvivesRedisNotificationFailureAndOutboxReplaysAfterRedisReturns'; evidence = 'RealMySqlRedisConcurrencyIntegrationTest#databaseCommitSurvivesRedisNotificationFailureAndOutboxReplaysAfterRedisReturns'; assertions = @('dispatch readiness is false while Redis is stopped', 'no Offer is created during outage', 'unpublished Outbox data survives and is delivered after recovery', 'projection rebuild restores readiness', 'capacity and Offer/Assignment ledger invariants hold') },
    [ordered]@{ scenario = 'Consumer process crash'; status = 'NOT RUN'; evidence = 'No forked Worker process-kill test is configured'; assertions = @() },
    [ordered]@{ scenario = 'DB commit before XACK process crash'; status = 'NOT RUN'; evidence = 'The ACK failure window is injected, but no Worker process is killed'; assertions = @('Inbox DB commit precedes ACK attempt', 'failed ACK leaves the entry Pending', 'redelivery does not repeat the order effect', 'Pending entry is ACKed after recovery') },
    [ordered]@{ scenario = 'XADD before Outbox published mark process crash'; status = 'NOT RUN'; evidence = 'Duplicate-XADD window is reproduced, but no Worker process is killed'; assertions = @('the event can appear twice in the Stream', 'Outbox eventually becomes published', 'Inbox applies the event once') },
    [ordered]@{ scenario = 'Outbox duplicate XADD has one Inbox effect'; status = Get-CaseStatus 'com.peakdispatch.api.infra.RealMySqlRedisConcurrencyIntegrationTest' 'outboxXaddBeforePublishedMarkCanDuplicateStreamEntryButInboxEffectIsOnce'; evidence = 'RealMySqlRedisConcurrencyIntegrationTest#outboxXaddBeforePublishedMarkCanDuplicateStreamEntryButInboxEffectIsOnce'; assertions = @('the event appears twice in the Stream', 'Outbox becomes published', 'Inbox attempt count remains one', 'capacity and Offer/Assignment ledger invariants hold') },
    [ordered]@{ scenario = 'Handler failure remains Pending until DLQ recovers'; status = Get-CaseStatus 'com.peakdispatch.api.infra.RealMySqlRedisConcurrencyIntegrationTest' 'failedDlqWriteLeavesMysqlInboxAndStreamPendingUntilDlqRecovers'; evidence = 'RealMySqlRedisConcurrencyIntegrationTest#failedDlqWriteLeavesMysqlInboxAndStreamPendingUntilDlqRecovers'; assertions = @('Inbox stays PROCESSING and source stays Pending while DLQ write fails', 'source is ACKed only after DLQ write and Inbox DEAD_LETTER commit', 'capacity and Offer/Assignment ledger invariants hold') },
    [ordered]@{ scenario = 'XAUTOCLAIM recovery'; status = Get-CaseStatus 'com.peakdispatch.api.infra.RealMySqlRedisConcurrencyIntegrationTest' 'failedDlqWriteLeavesMysqlInboxAndStreamPendingUntilDlqRecovers'; evidence = 'The test makes the Pending entry idle, then the consumer recovery path reclaims it'; assertions = @('idle Pending entry is reclaimed', 'recovered handler completes DLQ and Inbox state', 'source Pending entry is cleared') },
    [ordered]@{ scenario = 'DLQ write failure'; status = Get-CaseStatus 'com.peakdispatch.api.infra.RealMySqlRedisConcurrencyIntegrationTest' 'failedDlqWriteLeavesMysqlInboxAndStreamPendingUntilDlqRecovers'; evidence = 'RealMySqlRedisConcurrencyIntegrationTest#failedDlqWriteLeavesMysqlInboxAndStreamPendingUntilDlqRecovers'; assertions = @('failed DLQ write leaves Inbox PROCESSING', 'source remains Pending and is not ACKed', 'retry writes one DLQ entry before ACK') },
    [ordered]@{ scenario = 'DLQ replay'; status = Get-CaseStatus 'com.peakdispatch.api.infra.RealMySqlRedisConcurrencyIntegrationTest' 'mysqlInboxAndRedisDlqReplayAreIdempotentAfterHandlerFailure'; evidence = 'RealMySqlRedisConcurrencyIntegrationTest#mysqlInboxAndRedisDlqReplayAreIdempotentAfterHandlerFailure'; assertions = @('first replay is accepted and duplicate replay is a no-op', 'Inbox reaches PROCESSED', 'source Pending entry is cleared', 'repeated replay has no extra DLQ side effect') },
    [ordered]@{ scenario = 'Capacity key loss and MySQL rebuild'; status = Get-CaseStatus 'com.peakdispatch.api.infra.RealMySqlRedisConcurrencyIntegrationTest' 'capacityKeyLossIsRebuiltFromMysqlAndFirstReserveFailsClosed'; evidence = 'RealMySqlRedisConcurrencyIntegrationTest#capacityKeyLossIsRebuiltFromMysqlAndFirstReserveFailsClosed'; assertions = @('first reserve fails closed after key loss', 'capacity projection is rebuilt from MySQL', 'later reserve succeeds and repeated release is idempotent', 'capacity limit and ledger invariants hold') },
    [ordered]@{ scenario = 'Redis recovery pauses dispatch, rebuilds, then resumes'; status = Get-CaseStatus 'com.peakdispatch.api.infra.RealMySqlRedisConcurrencyIntegrationTest' 'databaseCommitSurvivesRedisNotificationFailureAndOutboxReplaysAfterRedisReturns'; evidence = 'RealMySqlRedisConcurrencyIntegrationTest#databaseCommitSurvivesRedisNotificationFailureAndOutboxReplaysAfterRedisReturns'; assertions = @('dispatch pauses during Redis loss', 'derived presence/capacity state is rebuilt before readiness returns', 'dispatch resumes after rebuild', 'capacity and Offer/Assignment ledger invariants hold') }
) | ForEach-Object {
    $_
}

$testSummary = [ordered]@{ tests = $null; passed = $null; failures = $null; errors = $null; skipped = $null }
if ($script:RunMaven) {
    $reports = @()
    foreach ($module in @('peakdispatch-domain', 'peakdispatch-application', 'peakdispatch-infrastructure', 'peakdispatch-api-worker')) {
        $reports += @(Get-ChildItem -Path (Join-Path $workspace "$module/target/surefire-reports") `
            -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue)
    }
    if ($reports.Count -gt 0) {
        $suites = foreach ($reportFile in $reports) {
            [xml]$document = Get-Content -Raw $reportFile.FullName
            $root = $document.DocumentElement
            [pscustomobject]@{
                tests = [int]$root.GetAttribute('tests')
                failures = [int]$root.GetAttribute('failures')
                errors = [int]$root.GetAttribute('errors')
                skipped = [int]$root.GetAttribute('skipped')
            }
        }
        $testSummary.tests = ($suites | Measure-Object -Property tests -Sum).Sum
        $testSummary.failures = ($suites | Measure-Object -Property failures -Sum).Sum
        $testSummary.errors = ($suites | Measure-Object -Property errors -Sum).Sum
        $testSummary.skipped = ($suites | Measure-Object -Property skipped -Sum).Sum
        $testSummary.passed = $testSummary.tests - $testSummary.failures - $testSummary.errors - $testSummary.skipped
    }
}

[ordered]@{
    generatedAt = [DateTime]::UtcNow.ToString('o')
    workspace = $workspace
    runtime = [ordered]@{ java = $javaVersion }
    infrastructure = $infrastructure
    maven = $maven
    surefire = $testSummary
    tests = $tests
    java21RuntimeVerification = $runtime
    mysqlRedisCases = $mysqlRedisCases
    redisRecoveryCases = $redisRecoveryCases
    policy = 'H2, Mockito, and standalone unit tests are never counted as real MySQL + Redis PASS.'
} | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 (Join-Path $resultDirectory 'real-infrastructure-results.json')

[ordered]@{
    generatedAt = [DateTime]::UtcNow.ToString('o')
    infrastructure = $infrastructure
    scenarios = $faults
} | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 (Join-Path $resultDirectory 'fault-matrix.json')

Write-Output (Join-Path $resultDirectory 'real-infrastructure-results.json')
Write-Output (Join-Path $resultDirectory 'fault-matrix.json')

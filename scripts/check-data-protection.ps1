[CmdletBinding()]
param(
    [string]$ReportPath = 'docs\test-results\data-protection-scan.json'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$extensions = @('.yaml', '.yml', '.json', '.ps1', '.js', '.cmd', '.bat', '.sh', '.properties', '.xml',
    '.toml', '.conf', '.java', '.sql', '.md')
$findings = [System.Collections.Generic.List[object]]::new()
$checked = [System.Collections.Generic.List[string]]::new()
$secretPattern = '(?i)(AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{30,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|sk-[A-Za-z0-9_-]{24,})'
$assignmentPattern = '(?<![\w:$])\b[A-Z][A-Z0-9_]*(?:PASSWORD|PASSWD|TOKEN|SECRET|CREDENTIAL|API[_-]?KEY|PRIVATE[_-]?KEY|SIGNING[_-]?KEY|ENCRYPTION[_-]?KEY|AUTHORIZATION)\b[ \t]*[:=](?!=)[ \t]*(?:(?i:bearer|basic)[ \t]+)?["'']?([^ \t"'',;]+)'
$placeholderPattern = '(?i)(\$\{|replace-with|test-only|demo-only|example|dummy|changeme|change-this|your[-_]|-test[-_]?|^test[-_]|^raw[-_]|^fake[-_]|^synthetic[-_]|redacted)'

$items = Get-ChildItem -LiteralPath $repoRoot -File -Recurse -Force | Where-Object {
    $relative = $_.FullName.Substring($repoRoot.Length).TrimStart([char]92, [char]47)
    $ignored = $relative -match '(^|[\\/])(target|node_modules|dist|coverage|backups|\.git|\.idea|performance[\\/](raw|fixtures))([\\/]|$)'
    $isEnvironmentFile = $_.Name -match '^\.env(?:\..+)?$'
    -not $ignored -and ($isEnvironmentFile -or $extensions -contains $_.Extension.ToLowerInvariant())
}
foreach ($item in $items) {
    $relative = $item.FullName.Substring($repoRoot.Length).TrimStart([char]92, [char]47)
    $checked.Add($relative)
    $lineNumber = 0
    foreach ($line in [System.IO.File]::ReadLines($item.FullName)) {
        $lineNumber++
        if ($line -match $secretPattern) {
            $findings.Add([ordered]@{ file = $relative; line = $lineNumber; type = 'known-credential-pattern' })
        }
        if ($line -notmatch '\$\{' -and $line -match $assignmentPattern) {
            $literal = $Matches[1]
            $isVariableReference = $literal -and $literal -match '^(?:\$\{|[A-Za-z_$][A-Za-z0-9_$]*\.)'
            if ($literal -and $literal -notmatch $placeholderPattern -and -not $literal.StartsWith('$') -and -not $isVariableReference) {
                $findings.Add([ordered]@{ file = $relative; line = $lineNumber; type = 'literal-secret-assignment' })
            }
        }
    }
}

$prodConfigPath = Join-Path $repoRoot 'peakdispatch-api-worker\src\main\resources\application-prod.yml'
$demoConfigPath = Join-Path $repoRoot 'peakdispatch-api-worker\src\main\resources\application-demo.yml'
$composeText = [System.IO.File]::ReadAllText((Join-Path $repoRoot 'compose.yaml'))
$prodText = if (Test-Path $prodConfigPath) { [System.IO.File]::ReadAllText($prodConfigPath) } else { '' }
$demoText = if (Test-Path $demoConfigPath) { [System.IO.File]::ReadAllText($demoConfigPath) } else { '' }
$isolationChecks = [ordered]@{
    productionProfile = $prodText -match 'on-profile:\s*prod' -and $prodText -match 'data-mode:\s*production'
    dedicatedProductionDatabase = $prodText -match 'MYSQL_DATABASE:peakdispatch_prod'
    productionRedisNamespace = $prodText -match 'PD_ENV:production'
    simulationProfile = $demoText -match 'data-mode:\s*simulation'
    composeUsesSimulationNamespace = $composeText -match 'PD_ENV:\s*\$\{PD_ENV:-demo\}'
}
foreach ($check in $isolationChecks.GetEnumerator()) {
    if (-not $check.Value) {
        $findings.Add([ordered]@{ file = 'environment-isolation'; line = 0; type = "failed:$($check.Key)" })
    }
}

$status = if ($findings.Count -eq 0) { 'PASS' } else { 'FAIL' }
$report = [ordered]@{
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    status = $status
    filesChecked = $checked.Count
    isolationChecks = $isolationChecks
    findings = @($findings)
}
$resolvedReport = if ([System.IO.Path]::IsPathRooted($ReportPath)) {
    [System.IO.Path]::GetFullPath($ReportPath)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repoRoot $ReportPath))
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resolvedReport) | Out-Null
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resolvedReport -Encoding utf8
Write-Output "Data-protection scan: $status ($($checked.Count) files checked). Report: $resolvedReport"
if ($status -ne 'PASS') { exit 1 }

[CmdletBinding()]
param(
    [string]$TaskName = 'PeakDispatch-MySQL-Daily-Backup',
    [ValidatePattern('^([01][0-9]|2[0-3]):[0-5][0-9]$')]
    [string]$At = '02:00'
)

$ErrorActionPreference = 'Stop'
$backupScript = (Resolve-Path (Join-Path $PSScriptRoot 'backup-mysql.ps1')).Path
$powershell = Get-Command pwsh.exe -ErrorAction SilentlyContinue
if (-not $powershell) { $powershell = Get-Command powershell.exe -ErrorAction Stop }
$quotedScript = '"' + $backupScript + '"'
$action = New-ScheduledTaskAction -Execute $powershell.Source `
    -Argument "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File $quotedScript" `
    -WorkingDirectory (Split-Path -Parent $backupScript)
$trigger = New-ScheduledTaskTrigger -Daily -At ([DateTime]::ParseExact(
    $At, 'HH:mm', [Globalization.CultureInfo]::InvariantCulture))
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 2) `
    -MultipleInstances IgnoreNew

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal `
    -Settings $settings -Description 'Daily PeakDispatch MySQL backup with seven-day retention and SHA-256 verification.' `
    -Force | Out-Null
Write-Output "Registered $TaskName for daily execution at $At. Configure MYSQL_DATABASE, MYSQL_USER, MYSQL_PASSWORD and mysqldump for the task service account before enabling production use."

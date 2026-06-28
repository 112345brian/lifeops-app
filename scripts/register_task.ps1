# Registers a Windows Scheduled Task that runs the life-ops app ~3x/day.
# Run this once in PowerShell from the project root. Edit $py if your python path differs.

$py   = (Get-Command python).Source
$proj = (Resolve-Path "$PSScriptRoot\..").Path
$action  = New-ScheduledTaskAction -Execute $py -Argument "-m lifeops.runner" -WorkingDirectory $proj
$triggers = @(
  New-ScheduledTaskTrigger -Daily -At 7:10am
  New-ScheduledTaskTrigger -Daily -At 2:10pm
  New-ScheduledTaskTrigger -Daily -At 9:10pm
)
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopOnIdleEnd `
              -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName "LifeOps" -Action $action -Trigger $triggers `
  -Settings $settings -Description "Personal life-ops scheduler (FlowSavvy/YNAB/ntfy)" -Force

Write-Host "Registered 'LifeOps' to run at 7:10, 14:10, 21:10 daily."
Write-Host "It runs whether or not Claude is open; only requires the PC to be on."

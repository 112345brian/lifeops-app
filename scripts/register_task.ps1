# Registers two Windows Scheduled Tasks for LifeOps. Run once from the project root.
#   LifeOps-tick   — every 10 min, the cheap/deterministic loop (gym, catchup, spend)
#   LifeOps-daily  — once each morning, the heavier/LLM work (ynab, homework, social, chores, meal)
# Both run whether or not Claude is open; they only need the PC on.

$py   = (Get-Command python).Source
$proj = (Resolve-Path "$PSScriptRoot\..").Path
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew

# --- tick: every 10 minutes, all day ---
$tickAction  = New-ScheduledTaskAction -Execute $py -Argument "-m lifeops.runner tick" -WorkingDirectory $proj
$tickTrigger = New-ScheduledTaskTrigger -Once -At (Get-Date) `
                 -RepetitionInterval (New-TimeSpan -Minutes 10) `
                 -RepetitionDuration (New-TimeSpan -Days 3650)
Register-ScheduledTask -TaskName "LifeOps-tick" -Action $tickAction -Trigger $tickTrigger `
  -Settings $settings -Description "LifeOps fast loop (deterministic, ~every 10 min)" -Force

# --- daily: once in the morning ---
$dailyAction  = New-ScheduledTaskAction -Execute $py -Argument "-m lifeops.runner daily" -WorkingDirectory $proj
$dailyTrigger = New-ScheduledTaskTrigger -Daily -At 7:10am
Register-ScheduledTask -TaskName "LifeOps-daily" -Action $dailyAction -Trigger $dailyTrigger `
  -Settings $settings -Description "LifeOps daily loop (heavier / LLM work)" -Force

Write-Host "Registered LifeOps-tick (every 10 min) and LifeOps-daily (7:10am)."

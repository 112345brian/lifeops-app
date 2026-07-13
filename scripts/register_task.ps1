# Registers three Windows Scheduled Tasks for LifeOps. Run once from the project root.
#   LifeOps-signal — every 2 min, the interactive path (catchup tap → re-pack the day)
#   LifeOps-tick   — every 10 min, the all-day deterministic loop (catchup, meal, gym)
#   LifeOps-daily  — once each morning, heavier work (ynab, homework, social, chores,
#                    meal, spend, digest, canvas)
# All run whether or not Claude is open; they only need the PC on. A single global
# run-lock (lock.py) serializes them, so overlapping fires can't race FlowSavvy.

# Fail loudly instead of silently: without this, a transient Task Scheduler/
# WMI hiccup on any ONE of the three Register-ScheduledTask calls below just
# prints a non-terminating error and the script carries on to the final
# "Registered all three" banner anyway -- confirmed as the exact mechanism
# that let "LifeOps-signal" go missing on this machine for an unknown
# stretch, discovered only by chance during unrelated debugging (2026-07-12).
$ErrorActionPreference = "Stop"

# pythonw.exe = no console window (silent/inconspicuous); fall back to python.exe
$py = (Get-Command python).Source -replace 'python\.exe$', 'pythonw.exe'
if (-not (Test-Path $py)) { $py = (Get-Command python).Source }
$proj = (Resolve-Path "$PSScriptRoot\..").Path
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -Hidden

# --- signal: every 2 minutes, all day (low-latency phone-tap response) ---
$sigAction  = New-ScheduledTaskAction -Execute $py -Argument "-m lifeops.runner signal" -WorkingDirectory $proj
$sigTrigger = New-ScheduledTaskTrigger -Once -At (Get-Date) `
                 -RepetitionInterval (New-TimeSpan -Minutes 2) `
                 -RepetitionDuration (New-TimeSpan -Days 3650)
Register-ScheduledTask -TaskName "LifeOps-signal" -Action $sigAction -Trigger $sigTrigger `
  -Settings $settings -Description "LifeOps signal loop (ntfy poll + catchup, ~every 2 min)" -Force

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

# Verify, don't just assume: print a clear PASS/FAIL per task rather than
# one unconditional success banner regardless of what actually happened.
$expected = "LifeOps-signal", "LifeOps-tick", "LifeOps-daily"
$missing = $expected | Where-Object { -not (Get-ScheduledTask -TaskName $_ -ErrorAction SilentlyContinue) }
if ($missing) {
    Write-Error "Registration verification FAILED -- missing: $($missing -join ', ')"
    exit 1
}
Write-Host "Verified: LifeOps-signal (every 2 min), LifeOps-tick (every 10 min), LifeOps-daily (7:10am) all registered."

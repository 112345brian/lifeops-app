# Registers the LifeOps control panel as an always-on service and exposes it
# PRIVATELY over Tailscale (HTTPS, your tailnet only — never the public internet).
# Run once from the project root.

$ErrorActionPreference = "Stop"

# pythonw.exe = no console window
$pyw = (Get-Command python).Source -replace 'python\.exe$', 'pythonw.exe'
if (-not (Test-Path $pyw)) { $pyw = (Get-Command python).Source }
$proj = (Resolve-Path "$PSScriptRoot\..").Path
$me   = "$env:USERDOMAIN\$env:USERNAME"

# Serve via `-m lifeops.web`, NOT `-m uvicorn ...`: pythonw has no console, so the
# uvicorn CLI's default stderr logging crashes it on startup. lifeops.web.main()
# redirects stdout/stderr to logs/web.log first, so it runs cleanly windowless.
# Bound to localhost only; Tailscale proxies to it (so it's never on the LAN).
$action  = New-ScheduledTaskAction -Execute $pyw -Argument "-m lifeops.web" -WorkingDirectory $proj
# Scope the logon trigger + principal to the current user — an unscoped -AtLogOn
# ("any user") requires admin to register; per-user does not.
$logonTrigger = New-ScheduledTaskTrigger -AtLogOn -User $me
# Backstop: RestartCount only covers 3 restarts within 1-minute intervals --
# once exhausted, an AtLogOn-only task stays dead until the NEXT logon/reboot
# (could be days on a machine left signed in). This periodic trigger re-fires
# every 10 min indefinitely; MultipleInstances=IgnoreNew makes it a safe no-op
# whenever the panel is already running, and a real relaunch whenever it's
# dead -- unbounded self-healing instead of "3 tries then wait for a reboot."
$backstopTrigger = New-ScheduledTaskTrigger -Once -At (Get-Date) `
                 -RepetitionInterval (New-TimeSpan -Minutes 10) `
                 -RepetitionDuration (New-TimeSpan -Days 3650)
$principal = New-ScheduledTaskPrincipal -UserId $me -LogonType Interactive -RunLevel Limited
# ExecutionTimeLimit 0 = no time limit (long-running). RestartCount/Interval
# relaunch it if it dies silently instead of leaving Task Scheduler reporting
# a stale "Running" state forever.
$settings  = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
             -Hidden -ExecutionTimeLimit (New-TimeSpan -Seconds 0) `
             -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName "LifeOps-web" -Action $action -Trigger $logonTrigger, $backstopTrigger `
  -Principal $principal -Settings $settings `
  -Description "LifeOps control panel (uvicorn 127.0.0.1:8765)" -Force
Start-ScheduledTask -TaskName "LifeOps-web"
Write-Host "LifeOps-web service registered + started."

# Expose to your tailnet over HTTPS (persists across reboots). Falls back to full path.
$ts = (Get-Command tailscale -ErrorAction SilentlyContinue).Source
if (-not $ts) { $ts = "C:\Program Files\Tailscale\tailscale.exe" }
& $ts serve --bg 8765
& $ts serve status
Write-Host "Panel is now at your machine's HTTPS tailnet URL (see 'serve status' above)."

# Registers the LifeOps control panel as an always-on service and exposes it
# PRIVATELY over Tailscale (HTTPS, your tailnet only — never the public internet).
# Run once from the project root.

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
$trigger   = New-ScheduledTaskTrigger -AtLogOn -User $me
$principal = New-ScheduledTaskPrincipal -UserId $me -LogonType Interactive -RunLevel Limited
$settings  = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
             -Hidden -ExecutionTimeLimit (New-TimeSpan -Seconds 0)   # no time limit (long-running)
Register-ScheduledTask -TaskName "LifeOps-web" -Action $action -Trigger $trigger `
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

# Registers the LifeOps control panel as an always-on service and exposes it
# PRIVATELY over Tailscale (HTTPS, your tailnet only — never the public internet).
# Run once from the project root.

# pythonw.exe = no console window
$pyw = (Get-Command python).Source -replace 'python\.exe$', 'pythonw.exe'
if (-not (Test-Path $pyw)) { $pyw = (Get-Command python).Source }
$proj = (Resolve-Path "$PSScriptRoot\..").Path

# uvicorn bound to localhost only; Tailscale proxies to it (so it's never on the LAN)
$action  = New-ScheduledTaskAction -Execute $pyw `
             -Argument "-m uvicorn lifeops.web:app --host 127.0.0.1 --port 8765" `
             -WorkingDirectory $proj
$trigger = New-ScheduledTaskTrigger -AtLogOn
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
             -Hidden -ExecutionTimeLimit (New-TimeSpan -Seconds 0)   # no time limit (long-running)
Register-ScheduledTask -TaskName "LifeOps-web" -Action $action -Trigger $trigger `
  -Settings $settings -Description "LifeOps control panel (uvicorn 127.0.0.1:8765)" -Force
Start-ScheduledTask -TaskName "LifeOps-web"
Write-Host "LifeOps-web service registered + started."

# Expose to your tailnet over HTTPS (persists across reboots). Falls back to full path.
$ts = (Get-Command tailscale -ErrorAction SilentlyContinue).Source
if (-not $ts) { $ts = "C:\Program Files\Tailscale\tailscale.exe" }
& $ts serve --bg 8765
& $ts serve status
Write-Host "Panel is now at your machine's HTTPS tailnet URL (see 'serve status' above)."

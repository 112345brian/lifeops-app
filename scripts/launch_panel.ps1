# One-click launcher for the LifeOps control panel.
# - Bootstraps the LifeOps-web scheduled task if it isn't registered yet
#   (installs deps if missing, then runs register_web.ps1).
# - Starts the service if it's registered but not currently listening.
# - Focuses an already-open "LifeOps" browser tab instead of opening a
#   duplicate one, when a matching window can be found.

$ErrorActionPreference = "Stop"
$proj = (Resolve-Path "$PSScriptRoot\..").Path
$url  = "http://127.0.0.1:8765"

function Test-PortOpen($portNum) {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $iar = $client.BeginConnect("127.0.0.1", $portNum, $null, $null)
        $ok = $iar.AsyncWaitHandle.WaitOne(500, $false)
        if ($ok -and $client.Connected) { $client.EndConnect($iar); $client.Close(); return $true }
        $client.Close()
        return $false
    } catch { return $false }
}

# 1. Bootstrap: register the service if the scheduled task doesn't exist yet.
$task = Get-ScheduledTask -TaskName "LifeOps-web" -ErrorAction SilentlyContinue
if (-not $task) {
    Write-Host "LifeOps-web task not found — bootstrapping..."
    python -c "import fastapi, uvicorn" 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Installing dependencies..."
        pip install -r "$proj\requirements.txt"
    }
    & "$PSScriptRoot\register_web.ps1"
    $task = Get-ScheduledTask -TaskName "LifeOps-web" -ErrorAction SilentlyContinue
}

# 2. Make sure it's actually running.
if (-not (Test-PortOpen 8765)) {
    if ($task -and $task.State -ne "Running") {
        Write-Host "Starting LifeOps-web..."
        Start-ScheduledTask -TaskName "LifeOps-web"
    }
    $waited = 0
    while (-not (Test-PortOpen 8765) -and $waited -lt 15) {
        Start-Sleep -Milliseconds 500
        $waited += 0.5
    }
    if (-not (Test-PortOpen 8765)) {
        Write-Warning "Panel didn't come up on port 8765 within 15s. Check logs\web.log."
    }
}

# 3. Focus an existing "LifeOps" tab instead of opening a duplicate, if we can find one.
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public class WinFinder {
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    public static IntPtr Found = IntPtr.Zero;
    public static bool Callback(IntPtr hWnd, IntPtr lParam) {
        if (!IsWindowVisible(hWnd)) return true;
        var sb = new StringBuilder(256);
        GetWindowText(hWnd, sb, 256);
        string title = sb.ToString();
        if (title.StartsWith("LifeOps") &&
            (title.Contains("Chrome") || title.Contains("Edge") || title.Contains("Firefox") || title.Contains("Mozilla"))) {
            Found = hWnd;
            return false;
        }
        return true;
    }
    public static IntPtr FindLifeOpsTab() {
        Found = IntPtr.Zero;
        EnumWindows(new EnumWindowsProc(Callback), IntPtr.Zero);
        return Found;
    }
}
"@

$existing = [WinFinder]::FindLifeOpsTab()
if ($existing -ne [IntPtr]::Zero) {
    Write-Host "LifeOps tab already open — bringing it to front."
    [WinFinder]::ShowWindow($existing, 9) | Out-Null   # SW_RESTORE, in case minimized
    [WinFinder]::SetForegroundWindow($existing) | Out-Null
} else {
    Write-Host "Opening $url ..."
    Start-Process $url
}

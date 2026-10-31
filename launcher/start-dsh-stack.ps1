# Starts the whole dsh remote stack for this user session.
#
# Idempotent: each service is started only when its port is not already
# listening, so running it by hand is safe and the logon task can fire freely.
#
#   dsh web   (3080)  → the host the phone talks to
#   bridge    (3084)  → legacy 0.1.x protocol translator for DshRemote
#
# Caddy (8080/8443) runs as the Windows service dsh-remote-caddy.

$ErrorActionPreference = 'SilentlyContinue'

$node = 'C:\Program Files\nodejs\node.exe'
$dshBin = 'E:\npm-global\node_modules\@deepseek-ai\dsh\lib\bin.js'
$bridgeDir = 'D:\dsh-app-bridge'
$bridgeJs = Join-Path $bridgeDir 'bridge.js'
$remoteViewJs = Join-Path $bridgeDir 'remote-view.js'
$log = Join-Path $bridgeDir 'stack.log'

function Write-StackLog([string]$message) {
    try {
        "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $message" | Out-File -FilePath $log -Append -Encoding UTF8
    } catch { }
}

function Test-Port([int]$port) {
    return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

Write-StackLog 'stack start requested'

# ---- 1. dsh web ----
if (Test-Port 3080) {
    Write-StackLog 'dsh already listening on 3080, skip'
} else {
    Write-StackLog 'starting dsh web'
    Start-Process -FilePath $node `
        -ArgumentList "`"$dshBin`"", 'web' `
        -WorkingDirectory $env:USERPROFILE `
        -WindowStyle Hidden
    # dsh needs a moment before its HTTP server (and credential file) are ready.
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Milliseconds 500
        if (Test-Port 3080) { break }
    }
    Write-StackLog ("dsh listening: " + (Test-Port 3080))
}

# ---- 2. bridge ----
# Started through a watchdog loop: the phone cannot talk to the host at all while
# this process is down, and it has silently died before.
if (Test-Port 3084) {
    Write-StackLog 'bridge already listening on 3084, skip'
} else {
    Write-StackLog 'starting bridge watchdog'
    Start-Process -FilePath 'powershell.exe' `
        -ArgumentList '-NoProfile', '-WindowStyle', 'Hidden', '-ExecutionPolicy', 'Bypass', '-File', "`"$(Join-Path $bridgeDir 'watch-bridge.ps1')`"" `
        -WorkingDirectory $bridgeDir `
        -WindowStyle Hidden
    Start-Sleep -Seconds 4
    Write-StackLog ("bridge listening: " + (Test-Port 3084))
}

# ---- 3. remote-view (headless Edge rendering for the phone) ----
if (Test-Port 3085) {
    Write-StackLog 'remote-view already listening on 3085, skip'
} else {
    Write-StackLog 'starting remote-view'
    Start-Process -FilePath $node `
        -ArgumentList "`"$remoteViewJs`"" `
        -WorkingDirectory $bridgeDir `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $bridgeDir 'remote-view.out.log') `
        -RedirectStandardError (Join-Path $bridgeDir 'remote-view.err.log')
    Start-Sleep -Seconds 3
    Write-StackLog ("remote-view listening: " + (Test-Port 3085))
}

# ---- 4. Cloudflare quick tunnel (public HTTPS entry, optional) ----
$cf = 'C:\Program Files (x86)\cloudflared\cloudflared.exe'
$cfRunning = $false
try {
    $cfRunning = [bool](Get-CimInstance Win32_Process -Filter "Name='cloudflared.exe'" -ErrorAction SilentlyContinue)
} catch { }
if (-not (Test-Path $cf)) {
    Write-StackLog 'cloudflared not installed, skip tunnel'
} elseif ($cfRunning) {
    Write-StackLog 'cloudflare tunnel already running, skip'
} else {
    Write-StackLog 'starting cloudflare quick tunnel'
    Start-Process -FilePath $cf `
        -ArgumentList 'tunnel', '--url', 'http://127.0.0.1:8080', '--no-autoupdate' `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $bridgeDir 'tunnel.out.log') `
        -RedirectStandardError (Join-Path $bridgeDir 'tunnel.err.log')
    Write-StackLog 'cloudflare tunnel started (address appears in tunnel.err.log)'
}

Write-StackLog 'stack start done'
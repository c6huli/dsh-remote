# Keeps the legacy protocol bridge (127.0.0.1:3084) alive.
#
# The bridge is a single point of failure for the Android app: it is the only
# thing that translates the app's dotted RPC names (`session.list`) into the
# host's native `ns/method` form. When it dies, every request from the phone
# fails while the Web UI — which the browser UA routes down the native path —
# keeps working perfectly, so nothing looks broken from the host side. Run the
# bridge under a loop so a crash costs three seconds, not an "连接失败" hunt.

$node = 'C:\Program Files\nodejs\node.exe'
$dir = 'D:\dsh-app-bridge'
$js = Join-Path $dir 'bridge.js'
$log = Join-Path $dir 'bridge-watchdog.log'

function Write-Watchdog([string]$message) {
    try {
        Add-Content -Path $log -Value ("{0} {1}" -f (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'), $message)
    } catch { }
}

if (-not (Test-Path $node)) { Write-Watchdog "node missing at $node"; exit 1 }
if (-not (Test-Path $js)) { Write-Watchdog "bridge.js missing at $js"; exit 1 }

while ($true) {
    Write-Watchdog 'starting bridge'
    try {
        & $node $js *>> $log
        Write-Watchdog "bridge exited with $LASTEXITCODE"
    } catch {
        Write-Watchdog ("bridge threw: " + $_.Exception.Message)
    }
    Start-Sleep -Seconds 3
}

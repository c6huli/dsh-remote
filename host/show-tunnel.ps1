# Prints the current Cloudflare quick-tunnel public address.
#
# A quick tunnel gets a NEW random hostname every time cloudflared restarts, so
# the address has to be read back instead of being hard-coded. Point the app at
# it, or switch to a named tunnel (needs a Cloudflare account + domain) for a
# stable address.
$logFiles = @(
    'D:\dsh-app-bridge\tunnel.err.log',
    'D:\dsh-app-bridge\tunnel.out.log'
)

$found = $null
foreach ($f in $logFiles) {
    if (-not (Test-Path $f)) { continue }
    $m = Select-String -Path $f -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' -AllMatches -ErrorAction SilentlyContinue |
        ForEach-Object { $_.Matches.Value } |
        Select-Object -Unique -First 1
    if ($m) { $found = $m; break }
}

if ($found) {
    Write-Host ''
    Write-Host '  Current public address (use this on the phone):' -ForegroundColor Cyan
    Write-Host "  $found" -ForegroundColor Green
    Write-Host ''
    Write-Host '  Note: the address changes whenever cloudflared restarts.' -ForegroundColor DarkGray
} else {
    Write-Host 'No tunnel address found. Start start-dsh-stack.ps1 first.' -ForegroundColor Yellow
}

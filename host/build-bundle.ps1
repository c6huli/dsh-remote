# Builds the Web bundle that the app imports.
#
# Grabs the live index.html, resolves every referenced asset, downloads them and
# packs a zip next to the Caddy static root so the phone can fetch it at
#   http://<host>:8080/mini/assets.zip
#
# Re-run this whenever dsh's frontend changes; asset filenames are
# content-hashed, so the app simply replaces its copy.
$ErrorActionPreference = 'Stop'

$host8080 = 'http://127.0.0.1:8080'
$stage    = 'D:\dsh-remote-deploy\bundle\assets'
$zipPath  = 'D:\dsh-remote-deploy\bundle\assets.zip'
$publish  = 'C:\Users\YM\dsh-remote-deploy\mini\assets.zip'

Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $stage -Force | Out-Null

$ua = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36'

Write-Host 'fetching index.html ...'
$html = (Invoke-WebRequest -Uri "$host8080/" -UseBasicParsing -Headers @{ 'User-Agent' = $ua } -TimeoutSec 20).Content
Write-Host ("  html bytes: " + $html.Length)

$refs = [regex]::Matches($html, '(?:src|href)\s*=\s*"([^"]+\.(?:js|css|woff2?|ttf|svg|png))"') |
    ForEach-Object { $_.Groups[1].Value } |
    Select-Object -Unique

Write-Host ("  assets referenced: " + $refs.Count)

$ok = 0
foreach ($r in $refs) {
    if ($r.StartsWith('http')) { continue }
    $path = if ($r.StartsWith('/')) { $r } else { '/' + ($r -replace '^\./', '') }
    $dest = Join-Path $stage ($path.TrimStart('/') -replace '/', '\')
    $dir = Split-Path $dest -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    try {
        Invoke-WebRequest -Uri ($host8080 + $path) -OutFile $dest -UseBasicParsing -Headers @{ 'User-Agent' = $ua } -TimeoutSec 60
        $ok++
    } catch {
        Write-Host ("  skip " + $path)
    }
}
Write-Host ("  downloaded: " + $ok)

Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zipPath -Force
Copy-Item $zipPath $publish -Force

$z = Get-Item $zipPath
Write-Host ''
Write-Host ("  bundle: " + $z.FullName + "  " + [math]::Round($z.Length / 1MB, 2) + " MB")
Write-Host ("  published at " + $publish)
Write-Host ''
Write-Host '  App import URL:' -ForegroundColor Cyan
Write-Host "  $host8080/mini/assets.zip" -ForegroundColor Green

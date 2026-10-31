# 用法（在能访问 GitHub 的机器上）：
#   $env:GH_TOKEN = "github_pat_xxx"
#   .\push-to-github.ps1 -Owner 你的用户名 -Repo dsh-remote
param(
  [Parameter(Mandatory=$true)][string]$Owner,
  [string]$Repo = 'dsh-remote',
  [string]$Branch = 'main'
)
$ErrorActionPreference = 'Stop'
if (-not $env:GH_TOKEN) { throw '请先设置环境变量 GH_TOKEN（GitHub 个人访问令牌）' }
$api = "https://api.github.com"
$h = @{ Authorization = "Bearer $env:GH_TOKEN"; Accept = 'application/vnd.github+json'; 'User-Agent' = 'dsh-remote-push' }

# 1) 创建仓库（已存在则跳过）
try {
  Invoke-RestMethod -Method Post -Uri "$api/user/repos" -Headers $h -Body (@{
    name = $Repo; private = $false; description = 'DSH Remote - 把 DeepSeek Harness 搬到手机上'
  } | ConvertTo-Json) | Out-Null
  Write-Host "已创建仓库 $Owner/$Repo"
} catch {
  Write-Host "仓库已存在或创建失败，继续尝试推送：$($_.Exception.Message)"
}

# 2) 推送（令牌只在命令行内使用，不写入 .git/config）
Set-Location (Split-Path -Parent $MyInvocation.MyCommand.Path)
git remote remove origin 2>$null
git remote add origin "https://$($env:GH_TOKEN)@github.com/$Owner/$Repo.git"
git branch -M $Branch
git push -u origin $Branch
git remote set-url origin "https://github.com/$Owner/$Repo.git"
Write-Host "完成：https://github.com/$Owner/$Repo"
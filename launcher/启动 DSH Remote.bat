@echo off
rem 双击即可启动 DSH Remote 全套：dsh web / 协议桥 / Caddy / 隧道
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-dsh-stack.ps1"
pause
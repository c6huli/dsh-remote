' Starts the dsh remote stack (dsh web + bridge) at logon, hidden.
' The PowerShell script is idempotent and checks ports first, so this is safe
' to run even when something is already up.
Set sh = CreateObject("WScript.Shell")
sh.Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""D:\dsh-app-bridge\start-dsh-stack.ps1""", 0, False

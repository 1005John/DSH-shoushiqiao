' 隐藏启动 DSH PC 守护脚本（计划任务调用，不弹窗）
Set sh = CreateObject("WScript.Shell")
sh.Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""D:\DSH\tools\pc\pc_services.ps1""", 0, False

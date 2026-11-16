# 守护脚本的守护 —— 每分钟检查 pc_services.ps1 是否还活着，不在就拉起来
# 由计划任务 DSH-PC-Watchdog 每分钟调用
$ErrorActionPreference = 'SilentlyContinue'
$Base = 'D:\DSH\tools\pc'
$Log  = Join-Path $Base 'pc_watchdog.log'

function WLog($m) {
    Add-Content -Path $Log -Value (('{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $m)) -Encoding UTF8
    try { if ((Get-Item $Log).Length -gt 512KB) { Get-Content $Log -Tail 200 | Set-Content $Log -Encoding UTF8 } } catch { }
}

$alive = Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
    Where-Object { $_.CommandLine -match 'pc_services\.ps1' }

if (-not $alive) {
    WLog 'supervisor not running -> starting'
    Start-Process -FilePath 'wscript.exe' -ArgumentList @('//nologo', (Join-Path $Base 'pc_services.vbs')) -WindowStyle Hidden
} else {
    # 正常时静默，不写日志刷屏
}
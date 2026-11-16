# DSH PC 服务守护脚本 v2
#
# 相比 v1 的改进：
#   1. 不只检查进程是否存在，还**实际验证隧道是否通**（在 VPS 上查监听端口）
#   2. 隧道僵死（进程在但转发失效）也能识别并重启
#   3. 自身异常退出后，由 pc_watchdog.ps1 每分钟拉起
#
# 日志：pc_services.log

$ErrorActionPreference = 'SilentlyContinue'
$Base  = 'D:\DSH\tools\pc'
$Cfg   = Join-Path $Base 'pc_config.json'
$Log   = Join-Path $Base 'pc_services.log'
$Node  = 'C:\Program Files\nodejs\node.exe'
$Ssh   = 'C:\Windows\System32\OpenSSH\ssh.exe'
$Key   = 'C:\Users\USERNAME\.ssh\moa_tunnel'
$Vps   = 'root@YOUR_VPS_IP'
$FilePort  = 8094
$AgentPort = 8095

function Write-Log($msg) {
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
    Add-Content -Path $Log -Value $line -Encoding UTF8
    try {
        if ((Get-Item $Log).Length -gt 2MB) {
            Get-Content $Log -Tail 500 | Set-Content $Log -Encoding UTF8
        }
    } catch { }
}

function Test-Port($port) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
}

function Start-Svc($script, $port) {
    Start-Process -FilePath $Node -ArgumentList @($script, '--config', $Cfg) -WindowStyle Hidden
    Write-Log "started $(Split-Path $script -Leaf) on $port"
}

function Get-TunnelProcs {
    Get-CimInstance Win32_Process -Filter "Name='ssh.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match '-R 1809[45]:' }
}

function Start-Tunnel($remotePort, $localPort) {
    $a = @(
        '-i', $Key,
        '-o', 'StrictHostKeyChecking=no', '-o', 'BatchMode=yes',
        '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3',
        '-o', 'ExitOnForwardFailure=yes', '-o', 'TCPKeepAlive=yes',
        '-o', 'ConnectTimeout=15',
        '-N', '-R', "$remotePort`:127.0.0.1:$localPort", $Vps
    )
    Start-Process -FilePath $Ssh -ArgumentList $a -WindowStyle Hidden
    Write-Log "tunnel $remotePort -> $localPort started"
}

# 在 VPS 上核对端口是否真在监听 —— 这是隧道真的通的证据
function Test-TunnelReal {
    $out = & $Ssh -i $Key -o StrictHostKeyChecking=no -o BatchMode=yes -o ConnectTimeout=12 `
        $Vps "ss -lnt 2>/dev/null | grep -c -E '18094|18095'" 2>$null
    $n = 0
    [int]::TryParse(([string]$out).Trim(), [ref]$n) | Out-Null
    return $n -ge 2
}

Write-Log '==== supervisor v2 start ===='

$loop = 0
while ($true) {
    $loop++
    try {
        if (-not (Test-Port $FilePort))  { Start-Svc (Join-Path $Base 'pc_file_server.js')  $FilePort }
        if (-not (Test-Port $AgentPort)) { Start-Svc (Join-Path $Base 'pc_agent_server.js') $AgentPort }

        $procs = Get-TunnelProcs
        $p94 = $procs | Where-Object { $_.CommandLine -match '18094:127\.0\.0\.1:8094' }
        $p95 = $procs | Where-Object { $_.CommandLine -match '18095:127\.0\.0\.1:8095' }
        if (-not $p94) { Start-Tunnel 18094 $FilePort }
        if (-not $p95) { Start-Tunnel 18095 $AgentPort }

        # 每 4 轮（约 2 分钟）实际验证隧道，僵死也重启
        if ($loop % 4 -eq 0) {
            if (-not (Test-TunnelReal)) {
                Write-Log 'tunnel check FAILED -> restarting both tunnels'
                Get-TunnelProcs | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
                Start-Sleep -Seconds 2
                Start-Tunnel 18094 $FilePort
                Start-Tunnel 18095 $AgentPort
            }
        }
    } catch {
        Write-Log ('supervisor error: ' + $_)
    }
    Start-Sleep -Seconds 30
}
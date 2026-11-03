# 启动脚本（自包含，不依赖仓库外的任何文件）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\run.ps1                  # 联网构建
#   powershell -ExecutionPolicy Bypass -File scripts\run.ps1 -Offline         # 离线（~/.m2 已预热）
#   powershell -ExecutionPolicy Bypass -File scripts\run.ps1 -Port 8082 -Profile demo
#
# 逻辑：Maven 编译 -> 从 `mvn -X` 日志解析运行时 classpath -> java -cp 启动。
# 离线机器加 -Offline 参数，脚本会追加 -o 与 scripts\mvn-settings-offline.xml。

param(
    [int]$Port = 0,
    [string]$Profile = "",
    [switch]$Offline,
    [string[]]$AppArgs = @()
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$settings = Join-Path $root 'scripts\mvn-settings-offline.xml'
$mainClass = 'com.zhouzihang.devflow.DevFlowApplication'
$log = Join-Path $root 'target\mvn-classpath.log'

Push-Location $root
try {
    $mvn = if (Test-Path (Join-Path $root 'mvnw.cmd')) { '.\mvnw.cmd' } else { 'mvn' }
    # 默认联网构建；离线机器（~/.m2 已预热）加 -Offline
    $offlineArgs = @()
    if ($Offline) {
        if (-not (Test-Path $settings)) { throw "找不到 $settings，无法离线构建" }
        $offlineArgs = @('-o', '-s', "`"$settings`"")
    }

    Write-Host "[1/3] 编译（$mvn $($offlineArgs -join ' ')）" -ForegroundColor Cyan
    $cmd = "$mvn $($offlineArgs -join ' ') -X test-compile > `"$log`" 2>&1"
    cmd /c $cmd
    if ($LASTEXITCODE -ne 0) {
        Write-Host "编译失败，日志：$log" -ForegroundColor Red
        Get-Content $log -Tail 30
        exit 1
    }

    Write-Host "[2/3] 解析运行时 classpath" -ForegroundColor Cyan
    $line = (Get-Content $log | Select-String -Pattern '\(f\) testPath = \[' | Select-Object -First 1).Line
    $pattern = '\(f\) testPath = \[(.*)\]'
    if (-not $line) {
        $line = (Get-Content $log | Select-String -Pattern '\(f\) compilePath = \[' | Select-Object -First 1).Line
        $pattern = '\(f\) compilePath = \[(.*)\]'
    }
    if (-not $line) { throw "未能从 Maven 日志解析 classpath：$log" }
    $raw = [regex]::Match($line, $pattern).Groups[1].Value
    $entries = $raw -split ',\s*' | ForEach-Object { $_.Trim() } | Where-Object { $_ }
    Write-Host ("      {0} 个 classpath 条目" -f $entries.Count)

    Write-Host "[3/3] 启动 $mainClass" -ForegroundColor Cyan
    $args = @('-cp', ($entries -join ';'), $mainClass)
    if ($Port -gt 0) { $args += "--server.port=$Port" }
    if ($Profile) { $args += "--spring.profiles.active=$Profile" }
    if ($AppArgs.Count -gt 0) { $args += $AppArgs }
    & java @args
}
finally {
    Pop-Location
}

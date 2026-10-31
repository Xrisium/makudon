#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$backend = Join-Path $PSScriptRoot 'android.ps1'

function Invoke-AndroidAction([string]$Action, [string]$TargetSerial = '') {
    $parameters = @{ Action = $Action }
    if ($TargetSerial) { $parameters.Serial = $TargetSerial }
    & $backend @parameters
}

function Select-PhysicalDevice {
    Write-Host "`n正在读取连接的设备..."
    $lines = @(Invoke-AndroidAction 'Devices')
    $lines | ForEach-Object { Write-Host $_ }
    $phones = @($lines | ForEach-Object {
        if ($_ -match '^(\S+)\s+device(?:\s|$)' -and $Matches[1] -notlike 'emulator-*') {
            [pscustomobject]@{ Serial = $Matches[1]; Description = $_ }
        }
    })
    if ($phones.Count -eq 0) {
        Write-Host '没有发现在线且已授权的真机。请检查连接和 USB 调试授权。'
        return $null
    }
    if ($phones.Count -eq 1) {
        Write-Host "`n已选择唯一在线真机：$($phones[0].Description)"
        return $phones[0].Serial
    }
    Write-Host "`n选择要安装的真机："
    for ($i = 0; $i -lt $phones.Count; $i++) { Write-Host "  $($i + 1). $($phones[$i].Description)" }
    Write-Host '  0. 取消'
    $choice = Read-Host '输入编号'
    $number = 0
    if (![int]::TryParse($choice, [ref]$number) -or $number -lt 1 -or $number -gt $phones.Count) {
        Write-Host '已取消安装。'
        return $null
    }
    return $phones[$number - 1].Serial
}

while ($true) {
    Write-Host @'

========== Makudon 调试工具 ==========
  1. 构建 debug APK
  2. 完整检查（构建、单元测试、Lint）
  3. 查看连接设备
  4. 启动 / 复用模拟器
  5. 构建并在模拟器运行应用
  6. 模拟器截图
  7. 模拟器实时日志（Ctrl+C 停止并关闭工具）
  8. 向真机覆盖安装已有 APK
  9. 环境检查
 10. 初始化本机工具路径
  0. 退出
'@
    $choice = Read-Host '选择操作'
    if ($null -eq $choice -or $choice.Trim() -eq '0') { break }
    try {
        switch ($choice.Trim()) {
            '1' { Invoke-AndroidAction 'Build' }
            '2' { Invoke-AndroidAction 'Check' }
            '3' { Invoke-AndroidAction 'Devices' }
            '4' { Invoke-AndroidAction 'Emulator' }
            '5' { Write-Host '将构建、覆盖安装并重启模拟器中的应用。'; Invoke-AndroidAction 'Run' }
            '6' { Invoke-AndroidAction 'Screenshot' }
            '7' { Write-Host '正在显示日志；按 Ctrl+C 停止。'; Invoke-AndroidAction 'Logcat' }
            '8' {
                $target = Select-PhysicalDevice
                if ($target) {
                    Write-Host '将使用已有 APK 保留数据覆盖安装；应用可能重启，手机可能需要确认。'
                    Invoke-AndroidAction 'InstallDevice' $target
                }
            }
            '9' { Invoke-AndroidAction 'Doctor' }
            '10' { Invoke-AndroidAction 'Setup' }
            default { Write-Host '无效编号，请重新选择。' }
        }
    } catch {
        Write-Host "`n操作失败：$($_.Exception.Message)" -ForegroundColor Red
    }
    $continue = Read-Host "`n按 Enter 返回菜单"
    if ($null -eq $continue) { break }
}

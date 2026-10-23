#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Setup', 'Doctor', 'Build', 'Check', 'Run', 'Emulator', 'Devices', 'Logcat', 'Screenshot')]
    [string]$Action = 'Run',
    [string]$SdkPath,
    [string]$JdkPath,
    [string]$AvdName,
    [string]$Serial,
    [ValidateRange(30, 600)][int]$BootTimeoutSeconds = 180,
    [switch]$ColdBoot,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = Split-Path $PSScriptRoot -Parent
$localDir = Join-Path $projectRoot '.local'
$configPath = Join-Path $localDir 'android.json'
$config = if (Test-Path $configPath) { Get-Content $configPath -Raw | ConvertFrom-Json } else { $null }

function Resolve-Setting($Explicit, $Key, $EnvironmentValue, $Fallback) {
    if ($Explicit) { return $Explicit }
    if ($EnvironmentValue) { return $EnvironmentValue }
    if ($config -and $config.PSObject.Properties[$Key]) { return $config.$Key }
    return $Fallback
}

$sharedRoot = Join-Path $env:USERPROFILE 'DevTools\Android'
$SdkPath = Resolve-Setting $SdkPath 'sdk' $env:MAKUDON_SDK (Join-Path $sharedRoot 'Sdk')
$JdkPath = Resolve-Setting $JdkPath 'jdk' $env:MAKUDON_JDK (Join-Path $sharedRoot 'Jdk\17.0.18+8')
$AvdName = Resolve-Setting $AvdName 'avd' $env:MAKUDON_AVD 'Android_API_36'

function Assert-File([string]$Path) {
    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing file: $Path. Run Setup with -SdkPath and -JdkPath pointing to existing tools."
    }
}

function Invoke-Native([string]$File, [string[]]$Arguments) {
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$File failed (exit $LASTEXITCODE): $($Arguments -join ' ')" }
}

Assert-File (Join-Path $JdkPath 'bin\java.exe')
Assert-File (Join-Path $SdkPath 'platform-tools\adb.exe')
$env:JAVA_HOME = $JdkPath
$env:ANDROID_HOME = $SdkPath
$env:ANDROID_SDK_ROOT = $SdkPath
# Some sandbox hosts do not supply HOME; adb must use the real user directory.
if (!$env:ANDROID_USER_HOME) { $env:ANDROID_USER_HOME = Join-Path $env:USERPROFILE '.android' }
$env:Path = "$JdkPath\bin;$SdkPath\platform-tools;$env:Path"
$adb = Join-Path $SdkPath 'platform-tools\adb.exe'
$emulator = Join-Path $SdkPath 'emulator\emulator.exe'
$packageName = 'io.github.xrisium.makudon.debug'
$component = "$packageName/io.github.xrisium.makudon.MainActivity"

function Get-Devices {
    $lines = @(Invoke-Native $adb @('devices'))
    foreach ($line in $lines) {
        if ($line -match '^(\S+)\s+(device|offline|unauthorized)\s*$') {
            [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] }
        }
    }
}

function Wait-Device([string]$Target) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt $BootTimeoutSeconds) {
        $device = @(Get-Devices | Where-Object Serial -EQ $Target)
        if ($device.Count -gt 0 -and $device[0].State -eq 'unauthorized') {
            throw "Authorize USB debugging on device $Target."
        }
        if ($device.Count -gt 0 -and $device[0].State -eq 'device') {
            $boot = & $adb -s $Target shell getprop sys.boot_completed 2>$null
            if ($LASTEXITCODE -eq 0 -and "$boot".Trim() -eq '1') { return $Target }
        }
        Start-Sleep -Seconds 2
    }
    throw "Device $Target did not finish booting within $BootTimeoutSeconds seconds. See .local/logs."
}

function Get-TestDevice {
    if ($Serial) { return Wait-Device $Serial }
    foreach ($device in @(Get-Devices)) {
        if ($device.Serial -like 'emulator-*' -and $device.State -eq 'device') {
            $name = @(Invoke-Native $adb @('-s', $device.Serial, 'emu', 'avd', 'name'))
            if ($name.Count -gt 0 -and $name[0].Trim() -eq $AvdName) {
                if ($ColdBoot) { throw 'ColdBoot requires closing this AVD first. No running AVD was stopped.' }
                Write-Host "Reusing $AvdName ($($device.Serial))."
                return Wait-Device $device.Serial
            }
        }
    }
    Assert-File $emulator
    $available = @(Invoke-Native $emulator @('-list-avds'))
    if ($AvdName -notin $available) { throw "AVD '$AvdName' not found. Available: $($available -join ', '). No image will be downloaded automatically." }
    # Never start a second copy of an AVD whose console is not ready yet.
    $running = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^(emulator|qemu-system.*)\.exe$' -and $_.CommandLine -and
        $_.CommandLine -match ('(?:-avd\s+"?' + [regex]::Escape($AvdName) + '(?:"|\s|$)|@' + [regex]::Escape($AvdName) + '(?:\s|$))')
    })
    if ($running.Count -gt 0) { throw 'This AVD is already starting or offline. Wait and retry, or inspect Devices; a second instance was not started.' }
    Invoke-Native $emulator @('-accel-check') | Out-Host
    $used = @(Get-Devices | ForEach-Object Serial)
    $port = $null
    for ($candidate = 5554; $candidate -le 5680; $candidate += 2) {
        if ("emulator-$candidate" -in $used) { continue }
        $listeners = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
        if (@($listeners | Where-Object { $_.Port -in @($candidate, ($candidate + 1)) }).Count -eq 0) {
            $port = $candidate
            break
        }
    }
    if (!$port) { throw 'No free Android emulator port found.' }
    $logs = Join-Path $localDir 'logs'
    New-Item -ItemType Directory -Force $logs | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    if ($AvdName -notmatch '^[A-Za-z0-9_.-]+$') { throw 'AVD name must use letters, digits, underscores, dots or hyphens.' }
    $arguments = @('-avd', $AvdName, '-port', "$port", '-gpu', 'auto', '-no-boot-anim')
    if ($ColdBoot) { $arguments += '-no-snapshot-load' }
    Write-Host "Starting $AvdName on emulator-$port."
    # Visible emulator requested by the test workflow; hide only the helper console.
    Start-Process -FilePath $emulator -ArgumentList $arguments -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logs "emulator-$stamp.out.log") `
        -RedirectStandardError (Join-Path $logs "emulator-$stamp.err.log") | Out-Null
    return Wait-Device "emulator-$port"
}

function Invoke-Build([string[]]$Tasks) {
    Assert-File (Join-Path $projectRoot 'local.properties')
    $arguments = @("-Dorg.gradle.java.home=$JdkPath", '--console=plain') + $Tasks
    if ($Offline) { $arguments += '--offline' }
    Invoke-Native (Join-Path $projectRoot 'gradlew.bat') $arguments
}

Push-Location $projectRoot
try {
    switch ($Action) {
        'Setup' {
            Assert-File (Join-Path $SdkPath 'platforms\android-36\android.jar')
            Assert-File (Join-Path $SdkPath 'build-tools\36.0.0\aapt2.exe')
            New-Item -ItemType Directory -Force $localDir | Out-Null
            @{ sdk = $SdkPath; jdk = $JdkPath; avd = $AvdName } | ConvertTo-Json | Set-Content $configPath -Encoding utf8
            $propertiesPath = Join-Path $projectRoot 'local.properties'
            $lines = if (Test-Path $propertiesPath) { @(Get-Content $propertiesPath | Where-Object { $_ -notmatch '^\s*sdk\.dir\s*=' }) } else { @() }
            $lines += 'sdk.dir=' + $SdkPath.Replace('\', '/').Replace(':', '\:')
            $lines | Set-Content $propertiesPath -Encoding utf8
            # Machine-specific editor file stays ignored; tracked settings stay portable.
            $editor = @{
                folders = @(@{ path = '..' })
                settings = @{
                    'intellij.jdkForSymbolResolution' = $JdkPath
                    'terminal.integrated.env.windows' = @{
                        JAVA_HOME = $JdkPath; ANDROID_HOME = $SdkPath
                        ANDROID_USER_HOME = $env:ANDROID_USER_HOME
                    }
                }
            }
            $editor | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $localDir 'Makudon.code-workspace') -Encoding utf8
            Write-Host 'Setup complete. Open .local/Makudon.code-workspace in VS Code, or use the repository folder tasks.'
        }
        'Doctor' {
            Write-Host "SDK: $SdkPath`nJDK: $JdkPath`nAVD: $AvdName"
            Invoke-Native (Join-Path $JdkPath 'bin\java.exe') @('-version')
            Assert-File (Join-Path $SdkPath 'platforms\android-36\android.jar')
            Assert-File (Join-Path $SdkPath 'build-tools\36.0.0\aapt2.exe')
            Invoke-Native $emulator @('-accel-check')
            Invoke-Native $emulator @('-list-avds')
            Invoke-Native $adb @('devices', '-l')
            Invoke-Native (Join-Path $projectRoot 'gradlew.bat') @("-Dorg.gradle.java.home=$JdkPath", '--version')
            if (!(Test-Path (Join-Path $projectRoot 'local.properties'))) { throw 'Run Setup first.' }
        }
        'Build' { Invoke-Build @(':app:assembleDebug') }
        'Check' { Invoke-Build @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug') }
        'Devices' { Invoke-Native $adb @('devices', '-l') }
        'Emulator' { $target = Get-TestDevice; Write-Host "Ready: $target" }
        'Run' {
            Invoke-Build @(':app:assembleDebug')
            $target = Get-TestDevice
            $apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
            Assert-File $apk
            Invoke-Native $adb @('-s', $target, 'install', '-r', $apk)
            Invoke-Native $adb @('-s', $target, 'shell', 'am', 'force-stop', $packageName)
            $launch = @(Invoke-Native $adb @('-s', $target, 'shell', 'am', 'start', '-W', '-n', $component))
            $launch | Out-Host
            if (($launch -join "`n") -notmatch 'Status: ok') { throw 'Activity did not start successfully.' }
            Start-Sleep -Seconds 2
            Invoke-Native $adb @('-s', $target, 'shell', 'pidof', $packageName) | Out-Host
            New-Item -ItemType Directory -Force $localDir | Out-Null
            $gitHead = & git rev-parse HEAD
            $dirty = @(& git status --porcelain).Count -gt 0
            @{
                time = (Get-Date).ToString('o'); serial = $target; package = $packageName
                apk = $apk; sha256 = (Get-FileHash $apk -Algorithm SHA256).Hash
                gitHead = "$gitHead"; dirty = $dirty
            } | ConvertTo-Json | Set-Content (Join-Path $localDir 'last-run.json') -Encoding utf8
            Write-Host "Ready for manual testing: $target. App data retained; build identity: .local/last-run.json"
        }
        'Logcat' {
            $target = Get-TestDevice
            $processId = @(Invoke-Native $adb @('-s', $target, 'shell', 'pidof', '-s', $packageName))
            if ($processId.Count -eq 0) { throw 'App is not running. Use Run first.' }
            Invoke-Native $adb @('-s', $target, 'logcat', "--pid=$($processId[0].Trim())", '-v', 'threadtime')
        }
        'Screenshot' {
            $target = Get-TestDevice
            $outputDir = Join-Path $localDir 'screenshots'
            New-Item -ItemType Directory -Force $outputDir | Out-Null
            $outputPath = Join-Path $outputDir ("makudon-" + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.png')
            $remotePath = '/sdcard/makudon-dev-screenshot.png'
            Invoke-Native $adb @('-s', $target, 'shell', 'screencap', '-p', $remotePath)
            Invoke-Native $adb @('-s', $target, 'pull', $remotePath, $outputPath)
            Invoke-Native $adb @('-s', $target, 'shell', 'rm', $remotePath)
            Write-Host $outputPath
        }
    }
} finally {
    Pop-Location
}

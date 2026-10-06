# 上课啦 · 便捷构建脚本（Windows / PowerShell）
#
# 为什么需要它：本机有三个环境差异，直接敲 gradlew 会踩坑
#   1. 项目路径含中文（f:\上课啦）→ AGP 报错、Gradle 测试 worker 直接崩
#   2. C:\Users\<中文用户名>\.gradle → 同上，worker 类路径编码错乱
#   3. dl.google.com 不可达 → 仓库必须走阿里云镜像（已写进 settings.gradle.kts）
#
# 脚本做的事：切到 ASCII 联接路径、指向 ASCII 的 GRADLE_USER_HOME、补齐 SDK 环境变量。
#
# 用法：
#   .\build.ps1 :app:assembleDebug
#   .\build.ps1 :core:common:test
#   .\build.ps1 clean :app:assembleDebug     # 清产物再构建（改动多时必须，否则 Kotlin 增量编译会报假错）
#   .\build.ps1 fresh :app:assembleRelease   # 只重启守护进程不删产物（文件锁住时用）

# 注意：这里刻意不用 $ErrorActionPreference = 'Stop'。
# PowerShell 5.1 下，原生命令（gradlew.bat）只要往 stderr 写一行警告就会被当成
# NativeCommandError 终止脚本，导致「构建明明成功了脚本却报失败」。
# 改为只看 $LASTEXITCODE。
$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# 另一个坑：`powershell -File build.ps1 :app:assembleRelease` 时，`-File` 会把
# 冒号开头的参数按 key:value 语法拆开，args 变成 ":" 和 "app:assembleRelease"。
# 这里把被拆出来的裸冒号过滤掉；Gradle 接受不带前导冒号的任务名。
$taskArgs = @($args | Where-Object { $_ -and $_.Trim() -ne '' -and $_.Trim() -ne ':' })

# fresh：只重启守护进程不删构建产物。
# 遇到 "FileSystemException: 另一个程序正在使用此文件" 时用它 ——
# Windows 上 Kotlin/Gradle 守护进程会锁住 classes.jar，重启即解。
$needDaemonRestart = $false
if ($taskArgs -contains 'fresh') {
    $taskArgs = @($taskArgs | Where-Object { $_ -ne 'fresh' })
    $needDaemonRestart = $true
}

$projectReal = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectAscii = 'F:\ShangKeLe'
$gradleHomeAscii = 'F:\gradle-user-home'

$workDir = if (Test-Path $projectAscii) { $projectAscii } else { $projectReal }
if (-not (Test-Path $workDir)) { throw "work dir not found: $workDir" }

$env:GRADLE_USER_HOME = if (Test-Path $gradleHomeAscii) {
    $gradleHomeAscii
} else {
    Write-Warning 'ASCII GRADLE_USER_HOME not found; unit tests may fail on a non-ASCII path'
    Join-Path $env:USERPROFILE '.gradle'
}

if (-not $env:ANDROID_HOME) {
    foreach ($candidate in @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
        'F:\android-sdk'
    )) {
        if (Test-Path $candidate) { $env:ANDROID_HOME = $candidate; break }
    }
}
if (-not $env:ANDROID_HOME) { throw 'Android SDK not found; please set ANDROID_HOME' }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

Write-Host "workDir       = $workDir"
Write-Host "GRADLE_USER_HOME = $env:GRADLE_USER_HOME"
Write-Host "ANDROID_HOME  = $env:ANDROID_HOME"

Set-Location $workDir
$tasks = if ($taskArgs.Count -gt 0) { $taskArgs } else { @(':app:assembleDebug') }

# 第三个坑：PowerShell 把带冒号的任务名传给 .bat 时会被拆开，
# Gradle 收到一个孤零零的 ":" 然后报 "Cannot locate tasks that match ':'"。
# 交给 cmd 走一整条命令串，参数原样透传，最可控。
# 第四个小坑：Windows 上 Kotlin/Gradle 守护进程会锁住 build 目录，
# 直接跑 clean 会 "Unable to delete directory"。所以 clean 之前先停守护进程。
if ($needDaemonRestart -or $tasks -contains 'clean' -or $taskArgs -contains ':clean') {
    Write-Host '前置：停止 Gradle 守护进程以释放文件锁'
    cmd /c "`"$workDir\gradlew.bat`" --stop" | Out-Null
    Start-Sleep -Seconds 3
}

$argLine = (@($tasks) + @('--console=plain')) -join ' '
Write-Host "gradle args   = $argLine"
cmd /c "`"$workDir\gradlew.bat`" $argLine"
$code = $LASTEXITCODE

# 脚本自己的提示信息统一用 ASCII，避免在不同控制台代码页下变成乱码
if ($code -eq 0) {
    Write-Host ''
    Write-Host 'BUILD OK - artifacts:' -ForegroundColor Green
    Get-ChildItem (Join-Path $workDir 'app\build\outputs\apk') -Recurse -Filter '*.apk' -ErrorAction SilentlyContinue |
        ForEach-Object { Write-Host ("  " + $_.FullName + "  (" + [math]::Round($_.Length / 1MB, 2) + " MB)") }
} else {
    Write-Host "BUILD FAILED - exit code $code" -ForegroundColor Red
}
exit $code

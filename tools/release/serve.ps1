<#
  在局域网里把 dist 目录发出去，用来测「自动更新」这条链路 ——
  不必真的发一个 GitHub release。

  做法：先按当前 versionName 生成一份 dist/latest.json，
  再用 Python 自带的 http.server 起服务（不需要管理员权限，也不需要装任何东西）。

  用法：
      pwsh tools/release/serve.ps1              # 默认 8080 端口
      pwsh tools/release/serve.ps1 -Port 9000

  然后在 App 里：设置 → 检查更新 → 把更新源填成脚本结尾打印的那个地址。

  想验证「有新版本」的效果，用 -FakeVersion 报一个更高的版本号，
  这样 App 会提示升级到那个版本（装的其实还是同一个包，用来走通流程）。
#>
param(
    [int]$Port = 8080,
    [string]$FakeVersion = ""
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # 仓库根
$dist = Join-Path $root 'dist'

# 1) 从 app/build.gradle.kts 读真实版本号
$gradle = Get-Content (Join-Path $root 'app\build.gradle.kts') -Encoding UTF8
$realVersion = ([regex]::Match(($gradle -join "`n"), 'versionName\s*=\s*"([^"]+)"')).Groups[1].Value
if (-not $realVersion) { throw "读不到 app/build.gradle.kts 里的 versionName" }

$version = if ($FakeVersion) { $FakeVersion } else { $realVersion }

# 2) 找 release 包
$apk = Get-ChildItem $dist -Filter "ShangKeLe-$realVersion-release.apk" -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $apk) {
    $apk = Get-ChildItem $dist -Filter '*-release.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
}
if (-not $apk) { throw "dist 里没有 release 包，先跑一次 build.ps1 :app:assembleRelease" }

# 3) 生成清单（形状与自建静态 json 一致，见 UpdateManifestParser）
$manifest = [ordered]@{
    version   = $version
    notes     = @"
本机局域网测试用的更新清单。

真实版本 $realVersion，清单里报 $version。
走通「检查 → 下载 → 安装」这条链路用的，装上去的还是同一个包。
"@
    apkUrl    = ""      # 下面按真实 IP 填
    sizeBytes = $apk.Length
}

# 4) 本机局域网 IP
$ip = (Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.InterfaceAlias -like '*WLAN*' } |
    Select-Object -First 1).IPAddress
if (-not $ip) {
    $ip = (Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.IPAddress -like '192.168.*' } | Select-Object -First 1).IPAddress
}
if (-not $ip) { throw "找不到局域网 IP，手机连不上" }

$manifest.apkUrl = "http://${ip}:${Port}/$($apk.Name)"
$manifest | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $dist 'latest.json') -Encoding UTF8

Write-Host ""
Write-Host "清单已生成：$dist\latest.json" -ForegroundColor Green
Write-Host "  版本     $version（真实 $realVersion）"
Write-Host "  APK      $($apk.Name)  $([math]::Round($apk.Length/1MB,2)) MB"
Write-Host ""
Write-Host "在 App 的「设置 → 检查更新」里把更新源填成：" -ForegroundColor Yellow
Write-Host "    http://${ip}:${Port}/latest.json" -ForegroundColor Cyan
Write-Host ""
Write-Host "注意："
Write-Host "  1. 手机要连同一个 Wi-Fi，且**关掉移动数据**（否则可能走外网解析不到这个内网地址）"
Write-Host "  2. 明文 http 需要在 network_security_config.xml 里放行这个 IP，"
Write-Host "     当前放行的是 192.168.0.3；这次的 IP 是 $ip，不一致的话要改那个文件并重新打包"
Write-Host "  3. Ctrl+C 结束服务"
Write-Host ""

# 5) 起服务（Python 自带，不需要管理员权限）
python -m http.server $Port --bind 0.0.0.0 --directory $dist

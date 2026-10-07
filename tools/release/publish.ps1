<#
  发布一个新版本，并让手机上的「检查更新」能发现它。

  脚本自己选模式：

  ── Release 模式（存在 token 时，推荐）─────────────────────────
     APK 作为 GitHub Release 的附件上传，**不进 git**。
     仓库里只多一个几百字节的 release/latest.json，不会随版本变胖。

  ── Git 模式（没有 token 时）───────────────────────────────────
     把 APK 直接提交进 release/ 目录。零配置、零配额，
     代价是**仓库每发一版胖 ~42MB**（历史里的旧包不会消失）。

  两种模式写出的 latest.json 地址完全一样，所以以后从 Git 模式升级到 Release 模式
  **不用改 App**（设置里的更新源地址不用动）。

  Token 放哪都行，二选一：
      tools/release/github-token.txt     一行，只有 token（已 gitignore）
      $env:GITHUB_TOKEN
  生成：GitHub → Settings → Developer settings → Fine-grained token
        → Repository permissions → Contents: Read and write

  仓库名读 tools/release/repo.txt；托管商默认 Gitee。

  用法：
      pwsh tools/release/publish.ps1 -Notes "这次改了什么"
      pwsh tools/release/publish.ps1 -Provider github -Notes "..."  # 改发到 GitHub
      pwsh tools/release/publish.ps1 -ForceGit          # 有 token 也走 git
      pwsh tools/release/publish.ps1 -DryRun            # 只打印，不动任何东西
#>
param(
    [string]$Repo = "",
    # 用哪个托管商。两者的 raw 地址格式不一样，拼错了 App 那边就是 404，
    # 而报出来的错还是「更新源返回 404：仓库名或路径不对」：
    #   Gitee   https://gitee.com/{owner}/{repo}/raw/{branch}/{path}
    #   GitHub  https://raw.githubusercontent.com/{owner}/{repo}/{branch}/{path}
    [ValidateSet('gitee', 'github')][string]$Provider = 'gitee',
    [string]$Notes = "",
    [switch]$ForceGit,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$toolDir = $PSScriptRoot
$releaseDir = Join-Path $root 'release'

function Fail($msg) { Write-Host $msg -ForegroundColor Red; exit 1 }
function Info($msg) { Write-Host $msg }
function Step($msg) { Write-Host ""; Write-Host "── $msg" -ForegroundColor Cyan }

# 所有 git 调用都必须走这两个包装。
#
# 原因：git 的进度输出（`To https://…`、`* [new branch]`）全写在 **stderr** 上，
# 而 PowerShell 默认把原生命令写到 stderr 的内容当成错误记录；
# 在 `$ErrorActionPreference = 'Stop'` 下它会变成**终止错误** ——
# 表现出来就是「推送明明成功了，脚本却报推送失败」。第一次跑就踩了。
# 所以这里只看退出码，不看 stderr。
function Invoke-Git {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GitArgs)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & git -C $root @GitArgs 2>&1 | ForEach-Object { if ("$_".Trim()) { Write-Host "  $_" } }
        return $LASTEXITCODE
    } finally { $ErrorActionPreference = $prev }
}

function Invoke-GitCapture {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GitArgs)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { return (& git -C $root @GitArgs 2>$null) }
    finally { $ErrorActionPreference = $prev }
}

# ── 1. 仓库 ────────────────────────────────────────────────
if (-not $Repo) {
    $repoFile = Join-Path $toolDir 'repo.txt'
    if (Test-Path $repoFile) { $Repo = (Get-Content $repoFile -Encoding UTF8 | Select-Object -First 1).Trim() }
}
if (-not $Repo -or $Repo -notmatch '/') {
    Fail "没找到仓库名。请在 tools/release/repo.txt 里写一行 owner/name"
}
$branch = 'main'
$rawBase = if ($Provider -eq 'gitee') {
    "https://gitee.com/$Repo/raw/$branch/release"
} else {
    "https://raw.githubusercontent.com/$Repo/$branch/release"
}

# ── 2. Token（可选）────────────────────────────────────────
$token = $env:GITHUB_TOKEN
if (-not $token) {
    $tokenFile = Join-Path $toolDir 'github-token.txt'
    if (Test-Path $tokenFile) { $token = (Get-Content $tokenFile -Encoding UTF8 | Select-Object -First 1).Trim() }
}
# Gitee 没有等价于 GitHub Release 附件的那套接口（要用得另配令牌），一律走 git
$mode = if ($Provider -eq 'gitee') { 'Git' } elseif ($token -and -not $ForceGit) { 'Release' } else { 'Git' }

# ── 3. 版本与产物 ──────────────────────────────────────────
$gradle = Get-Content (Join-Path $root 'app\build.gradle.kts') -Encoding UTF8
$version = ([regex]::Match(($gradle -join "`n"), 'versionName\s*=\s*"([^"]+)"')).Groups[1].Value
if (-not $version) { Fail '读不到 app/build.gradle.kts 里的 versionName' }

$dist = Join-Path $root 'dist'
$apk = Get-ChildItem $dist -Filter "ShangKeLe-$version-release.apk" -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $apk) { Fail "dist 里没有 ShangKeLe-$version-release.apk，先跑 build.ps1 :app:assembleRelease" }

if (-not $Notes) {
    $notesFile = Join-Path $toolDir 'notes.md'
    $Notes = if (Test-Path $notesFile) { Get-Content $notesFile -Raw -Encoding UTF8 } else { "发布 $version" }
}

Step "准备发布"
Info "仓库    $Repo"
Info "版本    $version"
Info "模式    $mode$(if ($mode -eq 'Git') { '（没有 token，APK 会进 git）' })"
Info "附件    $($apk.Name)  $([math]::Round($apk.Length/1MB,2)) MB"

if ($DryRun) { Info ''; Info '-DryRun：到此为止'; exit 0 }

# ── 4. 按模式拿到 apkUrl ───────────────────────────────────
$apkUrl = ""
if ($mode -eq 'Release') {
    Step "上传 Release 附件"
    $headers = @{
        'Authorization'        = "Bearer $token"
        'Accept'               = 'application/vnd.github+json'
        'User-Agent'           = 'ShangKeLe-Release'
        'X-GitHub-Api-Version' = '2022-11-28'
    }
    $api = "https://api.github.com/repos/$Repo"

    # 同名 release 先删掉：同一个版本号重发是很常见的操作
    try {
        $old = Invoke-RestMethod -Uri "$api/releases/tags/$version" -Headers $headers -Method Get -TimeoutSec 30
        Info "已存在 $version 的 release，先删再建"
        Invoke-RestMethod -Uri "$api/releases/$($old.id)" -Headers $headers -Method Delete -TimeoutSec 30 | Out-Null
    } catch { }

    $body = @{ tag_name = $version; name = $version; body = $Notes; draft = $false; prerelease = $false } |
        ConvertTo-Json -Depth 4
    try {
        $release = Invoke-RestMethod -Uri "$api/releases" -Headers $headers -Method Post `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
            -ContentType 'application/json; charset=utf-8' -TimeoutSec 60
    } catch {
        Fail "建 release 失败：$($_.Exception.Message)`n（403 多半是 token 权限不够，需要 Contents: Read and write）"
    }

    $uploadUrl = ($release.upload_url -replace '\{\?.*\}', '') + "?name=$($apk.Name)"
    try {
        $asset = Invoke-RestMethod -Uri $uploadUrl -Headers $headers -Method Post `
            -InFile $apk.FullName -ContentType 'application/vnd.android.package-archive' -TimeoutSec 900
    } catch {
        Fail "上传附件失败：$($_.Exception.Message)"
    }
    $apkUrl = $asset.browser_download_url
    Info "附件地址 $apkUrl"

    # Release 模式下 APK 不进 git：把以前误提交过的包清掉
    if (Test-Path $releaseDir) {
        Get-ChildItem $releaseDir -Filter '*.apk' -ErrorAction SilentlyContinue | ForEach-Object {
            Invoke-Git rm -q --ignore-unmatch -- "release/$($_.Name)" | Out-Null
        }
    }
} else {
    Step "把 APK 放进 release/ 目录"
    New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
    # 只留最新那个包在工作区，旧包从 git 里删掉（历史里仍然存在，这一点无法避免）
    Get-ChildItem $releaseDir -Filter '*.apk' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ne $apk.Name } |
        ForEach-Object { Invoke-Git rm -q --ignore-unmatch -- "release/$($_.Name)" | Out-Null }
    Copy-Item $apk.FullName (Join-Path $releaseDir $apk.Name) -Force
    $apkUrl = "$rawBase/$($apk.Name)"
    Info "APK 地址 $apkUrl"
}

# ── 5. 写 latest.json（App 读的就是这个） ─────────────────
Step "写 release/latest.json"
New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
$manifest = [ordered]@{
    version   = $version
    notes     = $Notes
    apkUrl    = $apkUrl
    sizeBytes = $apk.Length
}
$json = $manifest | ConvertTo-Json -Depth 4
$jsonPath = Join-Path $releaseDir 'latest.json'
# 必须**不带 BOM**：带 BOM 的 json 会让 kotlinx 的解析器在第一个字符就失败，
# 而报出来的错是"结构不符合预期"，跟编码毫无关系，极难查
[System.IO.File]::WriteAllText($jsonPath, $json, (New-Object System.Text.UTF8Encoding($false)))
Info "已写 $jsonPath"

# ── 6. 提交并推送 ──────────────────────────────────────────
Step "提交并推送"
Invoke-Git add -A release | Out-Null
$staged = Invoke-GitCapture diff --cached --name-only
if (-not $staged) {
    Info "release/ 没有变化（可能是同一个版本重发），跳过提交"
} else {
    $staged | ForEach-Object { Info "  $_" }
    if ((Invoke-Git commit -q -m "release: $version") -ne 0) { Fail "提交失败" }

    # 推给**所有配了的远端**：Gitee 是新的主更新源，GitHub 也留着 ——
    # 已经装出去的版本读的是 GitHub 那份清单，不推它就等于把老用户留在旧版本上。
    $targets = @()
    foreach ($name in @('gitee', 'origin', 'github')) {
        if (Invoke-GitCapture remote get-url $name) { $targets += $name }
    }
    foreach ($name in ($targets | Select-Object -Unique)) {
        if ((Invoke-Git push $name $branch) -eq 0) {
            Info "  已推送 $name"
        } else {
            # 不让一个远端的网络问题废掉整次发布：本地已经提交好了，
            # 换个时间手动补一次 git push 即可
            Write-Host "  推送 $name 失败（多半是网络）。本地已提交，稍后手动跑：git push $name $branch" -ForegroundColor Yellow
        }
    }
}

Step "完成"
Info "版本    $version"
Info "清单    $rawBase/latest.json"
Info "APK     $apkUrl"
Info ""
Info "手机开 App 会在「设置 → 检查更新」看到新版本。"
if ($mode -eq 'Git') {
    Info ""
    Write-Host "提示：当前是 Git 模式，APK 已提交进仓库。" -ForegroundColor Yellow
    Write-Host "      每发一版仓库会胖 ~$([math]::Round($apk.Length/1MB))MB。想避免的话，" -ForegroundColor Yellow
    Write-Host "      建一个 Fine-grained token 放进 tools/release/github-token.txt，" -ForegroundColor Yellow
    Write-Host "      下次发布就会自动走 Release 模式（App 那边不用改）。" -ForegroundColor Yellow
}

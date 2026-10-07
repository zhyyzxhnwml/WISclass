# 发版与自动更新

仓库：`wis314/wisclass`（Gitee）

App 启动时会静默查一次更新（6 小时一次），设置页有手动入口。
查到新版本弹窗显示更新说明 → 下载 → 跳系统安装界面。

## 数据流

```
App 读 →  https://gitee.com/wis314/wisclass/raw/main/release/latest.json    ← 主源
              │   （读不到时自动再试 GitHub 上同一份清单）
              │
              └─ apkUrl：仓库里的 APK
                   https://gitee.com/wis314/wisclass/raw/main/release/ShangKeLe-<版本>-release.apk
```

**为什么主源是 Gitee 而不是 GitHub**：GitHub 在国内经常连不上，而更新源连不上，
在用户眼里就等于「这个 App 再也更新不了」。GitHub 那份清单保留为备用源
（两个不同域同时挂掉的概率很低）：

- 主源 `https://gitee.com/wis314/wisclass/raw/main/release/latest.json`
- 备用 `https://raw.githubusercontent.com/zhyyzxhnwml/WISclass/main/release/latest.json`

两个平台的 raw 地址**格式不同**，拼错就是 404，而报出来的错还是
「仓库名或路径不对」——所以 `publish.ps1` 用 `-Host` 显式区分，不靠猜：

```
Gitee   https://gitee.com/{owner}/{repo}/raw/{branch}/{path}
GitHub  https://raw.githubusercontent.com/{owner}/{repo}/{branch}/{path}
```

**为什么都不用「Release API」**：那类接口要么按 IP 算匿名配额（本机实测撞到过
`0/60`，一律 403），要么要 token。读一个公开仓库的小文件，raw 最省事。

### 发完版要等几分钟（这条很重要）

raw 走 CDN，响应头是 `Cache-Control: max-age=300`。本机实测：

```
发完版 +  0s ~ +280s   读到的还是旧清单
发完版 +320s           变成新清单
```

App 里那个 `?t=时间戳` 参数**并没能绕开它**（带随机参数照样返回 `X-Cache: HIT`，
说明参数没进缓存键）。所以别指望它 —— **刚发完版就去手机上点检查更新，会看到
「已是最新版」**，然后被当成 bug 来找（已经发生过一次）。等 5 分钟再查。

（清单请求会自动带一个时间戳绕开 CDN 缓存，否则刚发完版手机上会读到旧清单。）

## 发一版

```powershell
# 1. 改 app/build.gradle.kts 里的 versionCode / versionName
# 2. 出包
& .\build.ps1 :app:assembleRelease
# 3. 把产物按版本号命名（现有习惯）
Copy-Item app\build\outputs\apk\release\app-release.apk `
          dist\ShangKeLe-<版本号>-release.apk
# 4. 发布（默认发到 Gitee）
pwsh tools/release/publish.ps1 -Notes "这次改了什么"
```

脚本会把 `release/` 提交，然后**推给所有配了的远端**（`gitee` 与 `github`）。
GitHub 之所以也推，是因为**已经装出去的版本读的还是 GitHub 那份清单** ——
不推它，等于把老用户留在旧版本上。

`publish.ps1` 自己选模式：

| 模式 | 何时用 | 代价 |
|---|---|---|
| **Git** | Gitee（默认）；或没有 token 的 GitHub | 仓库每发一版胖 ~42MB |
| **Release** | GitHub 且有 `tools/release/github-token.txt` | 无（除了要建一次 token） |

Gitee 没有等价于 GitHub Release 附件的那套接口，所以那边一律走 Git 模式。
两者产出的 `latest.json` 结构完全一样，区别只在 `apkUrl` 指向实际托管的那一份。

### 想要 Release 模式（推荐）

```
GitHub → Settings → Developer settings → Personal access tokens → Fine-grained
  权限：Repository permissions → Contents: Read and write
```

把 token 写进 `tools/release/github-token.txt`（一行，只有 token，已 gitignore）。
下次发布自动走 Release 模式，**App 不用改任何配置**。

### 只在本地验证

```powershell
pwsh tools/release/serve.ps1 -FakeVersion 99.0.0
```

按当前版本生成 `dist/latest.json` 并用 Python 起一个局域网静态服务，
把打印出来的地址填到「设置 → 检查更新」即可。用来演练
「发现新版本 → 下载 → 安装」这条链路，不用真的发版。

注意：手机要连**同一个 Wi-Fi 并关掉移动数据**；明文 `http` 需要在
`app/src/main/res/xml/network_security_config.xml` 里放行那个 IP
（目前已放行 `192.168.0.3`）。所以这条路只适合排查，日常走 GitHub。

## 安全红线

- **签名密钥（`keystore/shangkele-release.jks`）绝不入库。**
  自动更新只认签名，谁拿到私钥谁就能签一个恶意 APK 冒充官方更新，
  而手机那边会照装不误。仓库是公开的，所以 `.gitignore` 里
  `keystore/`、`*.jks`、`keystore.properties` 全部排除。
  请**离线**备份这个 jks —— 丢了后续版本就无法覆盖安装。
- token 只存本机 `tools/release/github-token.txt`，已 gitignore。
- 更新只从你配置的地址拉，App 里不内置任何第三方源。

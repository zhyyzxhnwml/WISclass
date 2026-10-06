# 发版与自动更新

仓库：`zhyyzxhnwml/WISclass`

App 开启动时会静默查一次更新（6 小时一次），设置页有手动入口。
查到新版本弹窗显示更新说明 → 下载 → 跳系统安装界面。

## 数据流

```
App 读 →  https://raw.githubusercontent.com/zhyyzxhnwml/WISclass/main/release/latest.json
              │
              └─ apkUrl 指向两个地方之一：
                   Release 附件   （推荐：APK 不进 git，仓库不会变胖）
                   仓库里的 APK   （零配置：不需要 token，但每版胖 ~42MB）
```

清单地址固定在 raw 上，**所以换发布模式不用改 App**。

**为什么清单不走 `api.github.com`**：那个接口的匿名配额按 **IP** 算（每小时 60 次），
共享出口很容易被用光 —— 本机实测就撞上过 `0/60`，一律 403。
`raw.githubusercontent.com` 是 CDN，读公开仓库的小文件既不限额也不要 token。

（清单请求会自动带一个时间戳绕开 CDN 缓存，否则刚发完版手机上会读到旧清单。）

## 发一版

```powershell
# 1. 改 app/build.gradle.kts 里的 versionCode / versionName
# 2. 出包
& .\build.ps1 :app:assembleRelease
# 3. 把产物按版本号命名（现有习惯）
Copy-Item app\build\outputs\apk\release\app-release.apk `
          dist\ShangKeLe-<版本号>-release.apk
# 4. 发布
pwsh tools/release/publish.ps1 -Notes "这次改了什么"
```

`publish.ps1` 会自己选模式：

| 模式 | 何时用 | 代价 |
|---|---|---|
| **Release** | `tools/release/github-token.txt` 里有 token | 无（除了要建一次 token） |
| **Git** | 没有 token | 仓库每发一版胖 ~42MB |

两者产出的 `latest.json` 完全一样。

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

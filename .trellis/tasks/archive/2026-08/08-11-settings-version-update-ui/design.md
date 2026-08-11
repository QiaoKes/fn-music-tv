# Technical Design

## 1. Scope and Boundaries

本任务作为一个端到端更新能力交付，包含三个共享契约、按顺序落地的边界：

1. GitHub Actions 为 sideload Release 产出 APK、校验文件和 `release-metadata.json`。
2. `tool/update-publisher/` 中的 Cloudflare Worker 管理后台读取 GitHub Release，把通过校验的 APK 与 `update.json` 发布到 R2。
3. Android sideload App 只读取 R2 `update.json`，完成检查、忽略、前台下载、APK 校验和系统安装确认。

三部分共享同一版本与文件完整性契约，因此保留在一个任务内。NAS 会话、音乐数据、播放服务协议和 Room 数据库不参与更新能力。`store` flavor 保留现状，不声明侧载安装权限，也不启动自更新。

## 2. Shared Release Contracts

### 2.1 GitHub `release-metadata.json`

CI 生成 schema v1，发布后台严格解析未知/缺失必填字段并拒绝不一致数据：

```json
{
  "schemaVersion": 1,
  "packageName": "com.fnmusic.tv",
  "versionName": "1.0.6",
  "versionCode": 22,
  "apk": {
    "fileName": "fn-music-tv-1.0.6-universal.apk",
    "size": 5560000,
    "sha256": "lowercase-hex",
    "signingCertificateSha256": "lowercase-hex"
  },
  "builtAt": "2026-08-11T12:00:00Z",
  "commitSha": "40-char-git-sha"
}
```

- `versionName` / `versionCode` 必须等于 `version.properties` 和 `aapt dump badging` 的结果。
- `apk.fileName` 必须唯一匹配同一 Release 的 APK asset。
- `size` 和 `sha256` 从最终重命名后的 universal APK 计算。
- 签名证书指纹从 `apksigner verify --print-certs` 的已验证 signer 输出提取并规范化为小写无分隔符 hex。
- `commitSha` 必须等于创建 Release 的 GitHub commit。

### 2.2 R2 `update.json`

发布后台生成 schema v1；App 只接受已知 schema、固定包名、HTTPS 且与清单同 host 的 APK URL：

```json
{
  "schemaVersion": 1,
  "packageName": "com.fnmusic.tv",
  "versionName": "1.0.6",
  "versionCode": 22,
  "title": "v1.0.6",
  "notes": "本次更新内容",
  "apk": {
    "url": "https://download.example.com/releases/22/fn-music-tv-1.0.6-universal.apk",
    "size": 5560000,
    "sha256": "lowercase-hex"
  },
  "publishedAt": "2026-08-11T12:10:00Z",
  "githubReleaseUrl": "https://github.com/QiaoKes/fn-music-tv/releases/tag/v1.0.6"
}
```

- `notes` 作为纯文本展示；发布后台把 GitHub Markdown 正文转换/收敛为可在 TV 弹窗中阅读的文本，并限制异常大输入。
- `update.json` 使用 `Cache-Control: no-store`；版本化 APK 使用 `public, max-age=31536000, immutable`。
- 清单不做独立数字签名。安全执行边界是：固定 HTTPS host、SHA256 文件完整性、包名与版本检查、已安装 App 和候选 APK 的 Android signer 比较，以及系统安装器的最终签名校验。

## 3. Android App Design

### 3.1 Package and dependency boundary

在 app 模块新增 `com.fnmusic.tv.update` 包，不增加新的 Gradle module：

- `UpdateContracts.kt`：清单 DTO、领域模型、检查结果和 UI 状态。
- `UpdateClient.kt`：固定 endpoint 的 OkHttp 请求、JSON 解码、响应大小/状态/host 校验。
- `UpdatePreferences.kt`：独立 SharedPreferences 文件，只保存设备全局 ignored `versionCode` 集合。
- `UpdateDownloader.kt`：前台单任务下载、进度、取消和临时文件生命周期。
- `ApkVerifier.kt`：SHA256、archive 包名/版本、签名证书比较。
- `UpdateInstaller.kt`：`PackageInstaller.Session` 写入、状态回调和系统确认 Intent。
- `UpdateCoordinator.kt`：应用级状态机、定时检查、手动检查、忽略、下载及安装编排。

App 直接声明已有 catalog 中的 OkHttp 与 Kotlin serialization 依赖；更新能力不复用 NAS `TrimMusicApi`，避免把独立公开更新源和账号 API 认证/错误语义混在一起。

### 3.2 Build flavors and manifest

- 增加非敏感构建配置 `UPDATE_MANIFEST_URL` 与 `SELF_UPDATE_ENABLED`。
- `sideload` 为 `SELF_UPDATE_ENABLED=true`；sideload Release 从 Gradle property / CI environment 读取 HTTPS 清单地址，缺失或非法时 fail closed。
- `store` 为 `SELF_UPDATE_ENABLED=false`，隐藏手动检查入口且不启动自动检查。
- 仅 `app/src/sideload/AndroidManifest.xml` 声明 `REQUEST_INSTALL_PACKAGES` 与非导出的安装状态 receiver；主 manifest 和 store flavor 不获得该权限。
- Debug 可显式注入测试 URL；未配置时协调器保持 Disabled，不产生网络请求。

### 3.3 Application ownership and lifecycle

`AppContainer` 创建一个 application-scoped `UpdateCoordinator` 并通过 `AppUiDependencies` / `AuthenticatedAppDependencies` 暴露窄接口。它使用现有 application coroutine scope，不持有 Activity。

`MainActivity` 将可见生命周期传给协调器：

- 首次 `onStart` 触发本进程的启动检查。
- `onStop` 标记 App 不可见并取消仍处于 Downloading 的请求和 `.part` 文件。
- 从未知来源授权页或系统安装页返回时，`onResume` 让协调器重新检查权限/安装状态。
- 显式退出前调用 coordinator shutdown，取消 timer/network 并清理未完成文件。

协调器只在 Activity 可见时发起自动请求。进程因播放服务继续存活但 UI 在后台时不检查、不下载；重新前台后若已到期则检查。所有 12 小时/30 分钟时间点只存内存并使用 monotonic clock。

### 3.4 State machine

主要状态为：

```text
Disabled / Idle
  → Checking(source = Automatic | Manual)
  → UpToDate | Available(ignored) | CheckError
  → Downloading(progress)
  → Verifying
  → AwaitingInstallPermission
  → PreparingInstaller
  → AwaitingSystemConfirmation
  → InstallCancelled | InstallFailed
```

- 自动与手动检查共享一个 single-flight；手动请求若已有检查运行则等待同一结果，但保留“手动结果必须反馈”的来源标记。
- 任意成功检查把下次自动检查设置为当前 monotonic time + 12h。
- 自动失败把下次检查设置为 +30m 且不产生可见错误；手动失败产生可见、可重试状态。
- 当前版本或更旧版本映射为 UpToDate。线上更高版本根据 ignored set 决定自动静默或 Available；手动检查始终发布 Available，并携带 `ignored=true`。
- Available 状态可以持续等待安全展示位置；它不因全屏播放器暂时隐藏而丢失。

### 3.5 Ignore persistence

`UpdatePreferences` 使用与 `AppPreferences` 不同的文件名和 key，不调用 `bindNamespace`，也不写 Room：

- 存储 `Set<String>` 形式的 versionCode 集合，读取时过滤非正整数。
- Ignore 操作只添加当前线上 versionCode。
- 每次进程启动以当前 BuildConfig versionCode 清理所有更低记录；相同/更高记录保留，以正确处理安装取消或异常状态。

### 3.6 Download and verification

- 只允许一个活动下载；目标为 `cacheDir/updates/<versionCode>.apk.part`，校验通过后原子改名为 `.apk`。
- OkHttp call 与 coroutine cancellation 绑定；进度根据清单 size 和实际响应长度发布。
- 禁止跨 host redirect；响应长度、最终字节数和清单 size 必须一致。
- 取消、返回、普通后台切换或失败会关闭 call 并删除 `.part`；不发送系统通知，不使用 DownloadManager，不写断点信息。
- 校验按 SHA256 → archive package/version → signer 的顺序执行，尽早拒绝损坏文件。
- `PackageManager.getPackageArchiveInfo(...GET_SIGNING_CERTIFICATES)` 读取候选 APK；release 包名必须为 `com.fnmusic.tv`，候选 versionCode 必须高于已安装 versionCode，signing certificate 必须匹配当前安装包。
- 启动时清理遗留 `.part` 和已失效 APK；完成安装后新进程也会完成忽略记录与缓存清理。

### 3.7 Permission and installation

- 下载和校验完成后才调用 `canRequestPackageInstalls()`。
- 未授权时 UI 先展示解释，再通过 effect 打开 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 的当前包页面。
- 已授权时把 APK 写入 `PackageInstaller.Session`；session commit 的状态由非导出 receiver 回送。
- `STATUS_PENDING_USER_ACTION` 中的系统 Intent 由可见 Activity 启动，最终确认始终由 Android 系统拥有。
- 只有“正在前往授权/安装系统页”的显式 handoff 状态允许 Activity 暂时后台而保留已校验 APK；普通离开 App、取消或终态失败清理 APK 和未提交 session。

### 3.8 Compose integration and safe prompting

- `SettingsScreen` 保持现有 verticalScroll，新增“关于”分区和手动检查入口，复用 package-local touch-compatible `Button`。
- 版本显示读取 BuildConfig，作者固定为 `Tag mig hånden`，仓库地址为 `github.com/QiaoKes/fn-music-tv`。
- `UpdateAvailableDialog` 和下载/授权状态 UI 由 coordinator state 驱动，不直接拥有网络或持久化。
- 弹窗按钮使用稳定 `FocusRequester` 与显式左右焦点链；初始焦点为“立即更新”。
- SignedOut/Login 场景可显示启动更新提示；SignedIn 场景由 `AuthenticatedApp` 根据当前 route 控制。`LibraryRoute.Player` 时保留 Available 状态但不渲染弹窗，返回其他 route 后自动显示。
- 手动检查位于 Settings route，结果立即呈现；自动错误不显示，手动 UpToDate/Error 使用轻量提示。

## 4. Cloudflare Publisher Design

### 4.1 Project shape

新增独立的 `tool/update-publisher/` TypeScript 工程：

```text
tool/update-publisher/
  public/                 # 无框架中文管理 UI
  src/contracts.ts        # 与 CI/App 对齐的 schema 与校验
  src/github.ts           # Release 列表和 asset 获取
  src/repository.ts       # R2 配置、历史、manifest 读写
  src/publisher.ts        # 导入、发布、撤回编排
  src/index.ts            # Worker API 与静态资源入口
  scripts/setup.mjs       # 浏览器登录、R2/Worker 配置与部署向导
  test/
  package.json
  wrangler.example.jsonc
```

首版使用原生 Worker fetch handler、原生 HTML/CSS/JavaScript 与 Workers Static Assets，不引入前端框架或数据库。测试使用 Cloudflare 官方 Workers Vitest integration，以本地 R2 binding 验证真实读写语义。

### 4.2 R2 object layout

```text
update.json
releases/<versionCode>/<apk-file-name>
manifests/<versionCode>.json
.publisher/config.json
.publisher/history/<timestamp>-<versionCode>.json
```

- `releases/` 与 `manifests/` 对外长期保留；APK key 永不覆盖。
- `update.json` 是 App 唯一入口，最后写入。
- `.publisher/` 只含非敏感配置和发布审计，不含 Token；部署说明要求公共下载域名阻止该前缀，Worker 仍可通过 binding 访问。
- 不引入 KV/D1，避免额外资源和一致性模型。

### 4.3 Admin API and UI

后台提供同源 JSON API：配置读写、Release 列表、Release 详情/预校验、发布、历史和撤回。所有 mutating endpoint：

- 只接受 JSON POST；
- 校验 same-origin / CSRF token；
- 使用请求 id 防止 UI 重复提交；
- 返回结构化错误码与中文安全消息，不返回上游 token、堆栈或完整内部响应。

Cloudflare Access 在 Worker 前验证管理邮箱，并覆盖静态资源和 `/api/*`。Worker 本身不实现账号密码。初始化脚本使用 `wrangler login` 浏览器授权；R2 运行时仅使用 binding，不存在 CF Token 输入框。

### 4.4 GitHub import

- 配置保存公开仓库 owner/repo 和 APK asset 文件规则。
- Worker 调用 GitHub Releases API；默认过滤 prerelease，UI 显式切换后才显示，draft 无论如何都拒绝。
- 生产 Worker 从 `GITHUB_TOKEN` Secret 读取仓库限定、Contents 只读的 Fine-grained PAT，并为所有 GitHub 列表、详情、metadata 与 APK 请求添加 Bearer 认证；Token 不进入 UI、R2 或普通部署配置。
- 用户必须选择明确 Release；不提供“自动发布 latest”。
- Worker 只跟随 GitHub API 返回的 asset URL，不接受任意远程 URL，从入口上避免 SSRF。
- 读取 `release-metadata.json` 后验证 schema、包名、version、文件名、size、SHA256、commit 和 GitHub asset digest；匹配 APK 不唯一时拒绝。
- 将 GitHub APK response 直接写入 R2，并把 metadata SHA256 作为 R2 `put` 的 `sha256` 完整性条件；R2 拒绝不匹配内容。

### 4.5 Publish transaction and rollback

正式发布顺序：

1. 校验选择的 GitHub Release 和 metadata。
2. 写入不可变 APK key；若 key 已存在，只接受 size/checksum 完全相同的幂等重试。
3. 写入 `manifests/<versionCode>.json` 和历史记录。
4. 重新读取当前 `update.json`，验证新版本高于历史最高发布 versionCode。
5. 使用当前 ETag 条件写入新的 `update.json`；并发修改导致条件失败时返回冲突，不覆盖他人的结果。

撤回从历史中选择上一份已发布 manifest，并同样使用 ETag 条件覆盖 `update.json`；不删除任何 APK 或 manifest。历史最高 versionCode 单独保留，撤回后也禁止发布更低/相同版本作为新版本。

## 5. CI Changes

现有 `.github/workflows/android.yml` 在 APK 验证/重命名后：

- 计算最终 APK size 与 SHA256；
- 从已成功的 `apksigner` 输出提取 signer digest；
- 用 `jq` 生成 schema v1 `release-metadata.json`；
- 对 JSON 做字段/格式自检；
- 将 metadata 同 APK/`.sha256` 一起上传 Actions artifact 和 GitHub Release。

Release 构建增加 `UPDATE_MANIFEST_URL` secret/variable 到 Gradle property；日志只能输出 host/已配置状态，不输出无关凭据。现有 release keystore 继续作为 Android APK 的唯一签名身份。

## 6. Error Handling and Observability

- App 将网络、清单格式、文件不一致、权限取消和安装器失败映射为有限用户消息；自动检查错误只记录安全摘要。
- Cancellation 始终重新抛出，不映射为失败弹窗；下载 call、文件和 installer session 在 `finally`/终态清理。
- Worker 错误分为配置、GitHub、metadata、R2、版本冲突和 Access/请求校验；服务端日志记录 request id、release id、versionCode 与阶段，不记录 Access cookie 或任何 token。
- 发布前预览显示最终用户可见内容；成功响应只有在 `update.json` 条件写入完成后返回。

## 7. Compatibility and Migration

- minSdk 29 可直接使用现代 signing info 和 `PackageInstaller.Session`。
- 旧安装没有 ignore 偏好时默认空集合，无数据库迁移。
- 旧 GitHub Release 缺少 metadata 时只读展示，不可发布；从下一次 CI Release 开始完整支持。
- 首次部署可将当前版本发布为基线；App 因线上 versionCode 不高于本机而不会弹窗。
- store flavor 不声明安装权限、不请求 R2、不显示手动自更新入口。

## 8. Risks and Mitigations

- **大陆网络仍不稳定**：R2 自定义域名是唯一已确认来源；通过真实大陆网络和目标电视验证，但不暗中增加备用源。
- **OEM 安装器差异**：覆盖授权未开启、允许、取消、返回和系统 installer callback；代表性 Android TV 设备验证仍是发布门槛。
- **R2 缓存旧清单**：`update.json` 明确 no-store，APK key 永不覆盖；发布成功后从公共域名回读清单做 smoke check。
- **发布并发/重复点击**：UI busy guard、request id 幂等和 R2 ETag 条件写共同防护。
- **GitHub API 共享出口限额/Token 到期**：Cloudflare 共享出口可能提前耗尽匿名每 IP 额度，因此生产环境使用 Worker Secret 中的只读 Fine-grained PAT；到期或撤销时只让后台读取失败并显示安全重试信息，不修改 R2。
- **清单被篡改**：攻击者可能造成虚假提示或拒绝服务，但固定 host、APK signer 比较和系统签名校验阻止非官方 APK 覆盖安装。

## 9. Rollout and Rollback

1. 先合入 CI metadata 与发布工具，并部署 Access/R2；发布当前 App 版本作为 `update.json` 基线。
2. 配置 sideload Release 的 `UPDATE_MANIFEST_URL`，再发布首个包含更新客户端的新 APK。
3. 使用下一更高 versionCode 做真实端到端升级验证。
4. 若某一线上版本有问题，后台撤回清单阻止未升级设备继续收到它；已升级设备通过更高 versionCode 修复，不尝试降级。

代码回滚时可分别撤销 Worker、CI 和 App update package；R2 历史 APK/manifest 保留，不执行破坏性删除。

## 10. Primary References

- Android `PackageInstaller`: https://developer.android.com/reference/android/content/pm/PackageInstaller.html
- Android unknown-source capability: https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()
- Cloudflare Worker R2 binding/checksum: https://developers.cloudflare.com/r2/api/workers/workers-api-reference/
- Cloudflare Worker static assets: https://developers.cloudflare.com/workers/static-assets/binding/
- Cloudflare Workers testing: https://developers.cloudflare.com/workers/testing/vitest-integration/
- Cloudflare Access Worker protection: https://developers.cloudflare.com/cloudflare-one/access-controls/applications/choose-application-type/

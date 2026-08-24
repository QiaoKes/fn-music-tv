# 使用 AppUpdate 重构应用安装链路

## Goal

参考飞牛影视 TV 的 AppUpdate 集成方式，把 sideload 版本从
`PackageInstaller.Session` 状态回调链路改为 AppUpdate 提供的
`FileProvider + ACTION_VIEW` 系统安装跳转，以改善海信 Vidda C3 Pro 上点击 APK
没有反应的问题，同时不降低现有更新包下载与校验强度。

## Requirements

- 静态研究用户提供的 `/Users/saki/Downloads/fntv_1.3.2_dangbei.apk`，记录其版本、
  安装权限、AppUpdate 组件和升级调用路径；不得运行或安装该 APK。
- 使用官方 `io.github.azhon:appupdate:4.3.6` 完成 sideload 安装跳转；`store`
  flavor 使用官方 no-op artifact，保持渠道隔离。
- 只替换安装交接层，不采用 AppUpdate 的下载服务、更新弹窗或 HTTP 下载器。
- 保留当前更新检查、前台下载、精确大小、SHA-256、包名、versionCode 和签名证书集合校验。
- 安装跳转必须发生在 APK 完成全部校验之后，并继续由 Android 系统显示最终安装确认页；
  不实现静默安装或厂商私有安装 API。
- 未允许“安装未知应用”时，仍只打开当前应用的
  `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 设置页；用户拒绝或取消后清理 APK 并允许重新下载。
- 删除旧的 `PackageInstaller.Session`、可变 `PendingIntent`、安装状态 receiver 和状态回调代码。
- sideload 合并清单只保留安装所需的 `REQUEST_INSTALL_PACKAGES` 和 AppUpdate
  `FileProvider`；provider 只授权内部 `cacheDir/updates/`，并移除未使用的 AppUpdate
  下载 service 和 dialog activity。
- store 合并清单不得包含 `REQUEST_INSTALL_PACKAGES`、AppUpdate provider/service/activity
  或旧的安装状态 receiver，自更新入口继续禁用。
- 保持现有 TV 弹窗、遥控器焦点、检查更新与下载体验；只调整安装交接期间的状态和错误文案。
- 安装页无法解析、FileProvider URI 创建失败、权限被拒绝或用户返回未安装时，必须清理临时 APK，
  显示可重试错误，不崩溃、不无限保留文件。
- 正式 sideload APK 必须显式包含 v1+v2 双签名，兼容 Vidda 文件管理器直接打开 APK；
  CI 必须检查最终发布产物，不能只依赖 Gradle 配置。
- 重新发行版本升级为 1.0.8（versionCode 24）；更新日志必须说明安装兼容性修复，
  并采用用户最终编辑的 1.0.7 功能与体验改动摘要。

## Acceptance Criteria

- [x] 参考 APK 研究记录包含包名/版本、`REQUEST_INSTALL_PACKAGES`、两个 FileProvider、
  AppUpdate service/activity 和反编译出的升级调用路径。
- [x] `sideloadImplementation` 使用 `appupdate:4.3.6`，`storeImplementation` 使用
  `appupdate-no-op:4.3.6`，版本由 version catalog 管理。
- [x] 已校验 APK 通过 AppUpdate `ApkUtil.installApk(...)` 交给系统安装器，authority
  与 `${applicationId}.fileProvider` 一致，debug applicationId suffix 也能正确匹配。
- [x] 旧 `UpdateInstallReceiver`、`PackageInstaller.Session` 和安装状态分支全部移除。
- [x] AppUpdate 的下载器、后台 service、完成通知和内置更新对话框均未被调用。
- [x] 下载和校验安全测试继续通过，且安装器单元测试覆盖 provider authority、安装跳转异常和文件清理。
- [x] 协调器测试覆盖未知来源权限允许/拒绝、安装页成功唤起、安装页无法唤起、用户取消返回。
- [x] sideload merged manifest 包含安装权限和非导出的 AppUpdate provider，但不包含未使用的
  AppUpdate service/activity 或旧 receiver；合并后的 paths 资源只允许内部缓存的
  `updates/` 子目录。
- [x] store merged manifest 不包含安装权限、AppUpdate 组件或旧 receiver。
- [x] sideload/store 的 compile、unit test、lint 和 assemble 检查通过。
- [x] 在可用 Android 设备或模拟器上验证系统安装确认页可打开；海信 Vidda C3 Pro
  的旧版覆盖安装由用户完成最终硬件验证并记录结果。
- [x] `.trellis/spec/backend/self-update-distribution.md` 更新为新的安装交接契约，
  不再要求 `PackageInstaller.Session` 回调。
- [x] 正式签名配置显式开启 v1+v2，CI 使用兼容 minSdk 验证最终 APK 的两种签名。
- [x] v1+v2 高版本测试包可在 Vidda C3 Pro 上从文件管理器打开并覆盖旧正式版。
- [x] `version.properties` 已升级为 1.0.8（versionCode 24），`CHANGELOG.md` 将本次安装
  兼容性修复与用户最终编辑的 1.0.7 改动摘要合并为重新发行说明。

## Notes

- 官方项目：https://github.com/azhon/AppUpdate
- 参考 APK 的“未申请白名单”是未申请海信厂商白名单；APK 自身仍声明了 Android
  `REQUEST_INSTALL_PACKAGES` 权限。
- 参考 APK 仅用于行为对照，不作为可信代码或依赖来源。
- 用户已在海信 Vidda C3 Pro 上确认 v1+v2 测试包可以从文件管理器正常打开并覆盖安装，
  验证了 v2-only 是此前点击 APK 无反应的兼容性回归原因。

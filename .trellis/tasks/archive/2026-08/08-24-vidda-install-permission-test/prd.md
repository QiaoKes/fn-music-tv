# 验证 Vidda 覆盖安装权限兼容性

## Goal

产出一个仅用于 Vidda C3 Pro 覆盖安装诊断的正式签名 APK，验证海信安装器是否因
`REQUEST_INSTALL_PACKAGES` 权限而拒绝从 1.0.6 覆盖升级，同时避免修改或发布正式产品版本。

## Background

- Vidda C3 Pro 可以在卸载旧版后安装同一 APK，但存在旧版时点击 APK 没有安装界面。
- 手机可以正常从 1.0.6 覆盖安装新版。
- GitHub 正式版 1.0.5、1.0.6 和 1.0.7 的包名与签名证书一致。
- FNID 构建期加密提交没有改变应用签名或 Android 安装清单。
- 1.0.6 新增了应用内更新能力及 `REQUEST_INSTALL_PACKAGES` 权限。

## Requirements

- 基于当前 1.0.7 正式代码构建，不改变 FNID、账号、媒体库或播放逻辑。
- 测试 APK 使用正式包名 `com.fnmusic.tv` 和现有正式发布证书。
- 测试 APK 使用 `versionCode 24` 和仅用于识别的 `versionName 1.0.8-vidda-test`。
- 测试 APK 的合并清单不得包含 `android.permission.REQUEST_INSTALL_PACKAGES`。
- 除移除该权限和测试版本标识外，不引入其他影响安装资格的差异。
- 构建过程在隔离的临时工作区完成；不修改、提交或发布实验性产品代码。
- 产物保存在被 Git 忽略的本地构建目录，并提供 SHA-256 校验值。

## Acceptance Criteria

- [x] APK 包名为 `com.fnmusic.tv`。
- [x] APK 版本为 `1.0.8-vidda-test (24)`，高于已安装的 1.0.6 (22) 和正式 1.0.7 (23)。
- [x] APK 签名 SHA-256 与正式版一致：`087469b178ef3fff7e0653007197e771905cd687985ae44ad86393920cb51489`。
- [x] APK 清单不包含 `REQUEST_INSTALL_PACKAGES`。
- [x] `apksigner verify` 和 release APK 构建成功。
- [x] 主工作区除 Trellis 任务记录外没有产品代码或版本文件改动。

## Out Of Scope

- 不发布 GitHub Release 或更新线上 `update.json`。
- 不把测试版本作为正式 1.0.8。
- 不修改 Vidda 工厂模式、系统安全策略或用户数据。
- 本任务只生成验证产物；根据电视上的测试结果再决定正式更新方案。

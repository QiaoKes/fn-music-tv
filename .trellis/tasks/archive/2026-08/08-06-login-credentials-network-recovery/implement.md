# 登录凭据与断网恢复实施计划

## Implementation

- [x] 1. 在 `core:data/security` 增加版本化加密会话载荷、历史 CRUD、不可读状态和旧 token/access code 兼容读取；保持 Keystore 操作在 IO dispatcher。
- [x] 2. 在 API 层建立明文密码与 SHA-256 摘要的类型化登录入口，校验摘要格式并补充请求契约测试，禁止二次哈希。
- [x] 3. 扩展 `SessionRepository` 的历史摘要、登录草稿和活动配置模型；实现服务器+账号 upsert、最多 5 条、单删、清空及关闭保持登录语义。
- [x] 4. 实现启动恢复状态机：token 校验、一次摘要回退登录、可重试错误指数退避、终止错误和 generation/cancellation 防竞态。
- [x] 5. 扩展应用协调动作，安全地启动、立即重试和取消恢复任务；确认旧任务不能覆盖手工登录或新账号。
- [x] 6. 更新登录表单：历史选择立即按配置 ID 登录，失败时回填并显示“已保存密码”状态；编辑服务器/账号/协议时解绑摘要，输入新密码时覆盖保存状态。
- [x] 7. 按项目主题实现双行历史弹窗、当前项高亮、独立删除按钮和清空全部；补齐遥控器焦点、触摸、语义和文本溢出约束。
- [x] 8. 增加恢复界面及“立即重试”“切换登录”，网络稍后恢复时无需退出应用或重新输入。
- [x] 9. 完成旧安装迁移路径，确保新格式成功落盘前不清除旧凭据，并处理 Keystore/密文不可读情况。
- [x] 10. 更新 README 登录说明、变更日志和前后端凭据与恢复代码规范。

## Validation

- [x] `./gradlew :core:data:testDebugUnitTest`
- [x] `./gradlew :app:testSideloadDebugUnitTest`
- [x] `./gradlew :app:compileSideloadDebugAndroidTestKotlin`
- [x] `./gradlew :app:lintSideloadDebug`
- [x] `./gradlew :app:assembleSideloadDebug`
- [x] 在可用 Android TV/投影仪上验证：保持登录、进程重启、启动时断网后恢复、多服务器多账号切换、单删、清空、旧 APK 升级。

## Review Gates

- [x] 安全审查：普通偏好、日志、Compose 语义和失败输出中没有摘要、安全码或 token 泄漏。
- [x] 状态审查：网络错误不清凭据，终止鉴权错误不循环登录，取消后的旧任务不发布状态。
- [x] 兼容审查：旧 token 有效可恢复，旧 token 失效可手工登录，新载荷不可读时仍兼容旧 token。
- [x] UI 审查：颜色完全来自项目主题；1920x1080、1280x720 和触摸窗口下无重叠，焦点可达且删除不误触历史选择。

## Risk And Rollback Points

- 加密格式与迁移：在步骤 1、9 后单独验证，失败时保留旧读取路径并停止清理旧键。
- 登录哈希边界：在步骤 2 后先运行 API 测试；任何二次哈希失败都必须在进入仓库/UI 工作前修正。
- 恢复并发：在步骤 4、5 后用可控延迟测试取消和新登录竞态，再接入 UI。
- UI 历史操作：删除与选择使用不同点击/焦点区域；若焦点回归不稳定，回滚弹窗改动而保留数据层。

# 登录凭据与断网恢复设计

## Boundaries

- `core:data/security` 负责加密持久化、格式版本和旧凭据读取，不把明文敏感值写入普通偏好。
- `core:data/api` 负责区分明文密码与已经计算的 SHA-256，保证请求体恰好哈希一次。
- `core:data/repository/SessionRepository` 负责登录配置历史、活动配置、token 恢复、凭据回退登录和恢复状态。
- `app` 协调恢复任务的取消/重启，并渲染恢复状态、登录草稿和历史弹窗。

## Secure Data Model

使用现有 Android Keystore AES-GCM 密钥加密一个带版本的会话历史 JSON。SHA-256 在服务端协议中等同于可重放密码，因此与安全码、token、账号一起作为敏感数据加密。

```text
SecureSessionPayload(version, activeProfileId, profiles[])
StoredLoginProfile(
  id,
  canonicalServer,
  relayMode,
  username,
  passwordSha256,
  accessCode,
  userToken,
  lastUsedAt
)
```

- 历史身份按规范化服务器地址与区分大小写的账号联合匹配；`id` 作为稳定的不透明引用，
  同一服务器与账号组合登录成功时沿用原 `id` 并原位更新。
- 历史按 `lastUsedAt` 降序，沿用当前最多 5 条的边界；加入第 6 条时删除最旧条目的全部持久化凭据。
- `activeProfileId` 标识下次启动优先恢复项。删除活动历史只清除持久化资料，不终止当前内存会话。
- 安全存储读取返回 `Missing`、`Ready` 或 `Unreadable`，不再把 Keystore/密文错误静默合并成“没有记录”。UI 只显示通用恢复失败，不暴露异常或敏感值。

## Password Contract

- 手工输入继续以 `CharArray` 进入仓库，在 API 边界计算一次 SHA-256，提交成功后清零输入数组。
- 已保存登录通过单独的 `loginWithPasswordHash` 契约发送经过严格校验的 64 位小写十六进制摘要，不允许走明文入口再次哈希。
- 登录页持有历史项 ID 和“有已保存密码”的布尔状态，不持有密码摘要。服务器或账号被用户修改后，解绑历史项并要求输入新密码。
- 选择历史项直接按其 ID 从仓库读取摘要并登录，摘要不进入 UI。失败后密码字段以“已保存密码”占位状态呈现；用户开始编辑时清除占位并改用新输入。

## History Flow

1. 启动时在 IO dispatcher 解密历史和兼容旧凭据，发布历史摘要与活动项。
2. 历史弹窗按“账号”与“服务器地址（协议）”双行显示，使用 `FnColors`；当前项使用现有焦点/强调色。
3. 选择一项后关闭弹窗并立即使用保存凭据登录；请求失败时回填表单和错误，允许用户修改后重试。
4. 行尾删除按钮只删除该配置；底部清空按钮删除全部持久化历史。两种操作均更新列表与选中状态。
5. 成功登录且保持登录开启时，写入或更新历史并设为活动项；关闭保持登录时不写历史。

## Restore State Machine

```text
Loading -> Restoring(profile, attempt)
Restoring -- success ----------------------> SignedIn
Restoring -- retryable connection failure -> Recovering(profile, attempt, error) -> Restoring
Restoring -- token invalid + saved hash ---> credential login (once)
credential login -- success --------------> SignedIn + replace token
credential login -- terminal failure -----> SignedOut(prefilled profile, error)
Recovering -- user chooses manual --------> SignedOut(prefilled profile)
```

- 启动优先校验活动项 token。token 明确失效时只清除该 token，并最多执行一次保存摘要登录；不循环提交错误凭据。
- IO、HTTP 408/429/5xx 和现有可重试请求错误采用 `1s, 2s, 4s, 8s, 15s, 30s` 封顶退避，网络恢复后在同一进程继续。
- `401`、业务码 `99999/120001`、账号停用、访问码错误、接口不存在和不可解析响应属于终止结果。
- 恢复界面显示当前服务器、连接状态和尝试次数，并提供“立即重试”“切换登录”操作；切换登录取消当前恢复任务但保留凭据。
- 每次恢复任务使用 generation/job 身份，旧任务取消或用户手动登录后不得再覆盖新会话状态。

## Legacy Compatibility

- 首次读取新格式为空时，继续读取当前 `token`、`access_code`、`session.server` 和 relay 标记。
- 旧 token 有效时保持登录，不伪造未知账号或密码摘要；后续一次成功的手工登录再创建完整历史。
- 旧 token 无效时清除旧 token，保留原最近服务器列表供手工选择；旧格式数据在新历史成功写入后清理。
- 加密载荷使用显式 `version`，未知新版本只进入可恢复错误，不覆盖原密文。

## UI And Accessibility

- 只复用参考截图的信息层级，不使用其配色；颜色来自 `FnColors.Background/Surface/Text/Muted/Coral/FocusFill/Warning`。
- 历史项和删除按钮都有独立焦点、内容描述和稳定高度；遥控器方向键、中心键和触摸均可操作。
- 删除按钮使用项目图标方案和危险色，清空全部使用明确文字命令；弹窗不嵌套额外卡片。
- 密码摘要永不进入 Compose 文本值、语义树或测试输出。

## Test Strategy

- API 契约测试验证明文与摘要两条路径生成相同且仅哈希一次的请求体，并拒绝非法摘要。
- 安全载荷 codec/store 测试覆盖多账号、更新、删除、清空、容量淘汰、损坏密文和版本不兼容。
- 仓库测试使用 fake store 与 MockWebServer 覆盖旧 token 恢复、token 失效后凭据登录、网络退避、终止错误、取消竞态和不保持登录。
- Compose 测试覆盖双行历史、项目配色语义、焦点顺序、选择后立即登录、失败回填、已保存密码状态、单删与清空。

## Rollback

- 新载荷与旧键并存到迁移完成；回滚产品代码仍可使用尚未清除的旧 token。
- 新格式写入失败不得删除旧格式。只有完整历史写入成功后才清理已迁移的旧安全键。

# 路由状态生命周期治理

## Goal

让动态页面的 retained/saveable 状态与返回栈生命周期一致，限制长期内存增长，同时保留 TV 页面返回后的内容和焦点恢复体验。

## Dependency

- 在 `08-03-playback-state-performance` 完成后启动，基于稳定后的页面状态依赖实施。

## Requirements

- R1：为 retained state store 提供按 route key 释放能力。
- R2：动态 route 永久出栈时同步移除 `SaveableStateHolder` 和 retained state；仍在栈内的 route 不释放。
- R3：相同业务实体重新进入时建立正确的新状态，不复用已淘汰详情页的过期加载状态。
- R4：返回、根页面切换、双击退出和焦点恢复控制逻辑保持不变。

## Acceptance Criteria

- [x] 连续打开并退出不同详情页后，状态容器规模不随历史访问量无界增长。
- [x] 返回栈内页面的分页位置、选中项和焦点仍可恢复。
- [x] route push/pop/root 切换与退出行为测试通过。
- [x] UI 布局、路由文案和遥控器交互无变化。

## Verification

- `LibraryRouteStateLifecycle` 仅返回在完整新栈中已不存在的 route key。
- 离栈后调用 `SaveableStateHolder.removeState`，并删除歌单/歌手/专辑详情独占的 retained entries。
- 会话级 `playlists`、Artists/Albums 等共享摘要不属于动态 route 清理集合。
- 新增栈保留/释放和 retained store 选择性清理测试。
- `./gradlew :app:testSideloadDebugUnitTest :app:lintSideloadDebug`：通过。
- `./gradlew test lint --continue`：通过，289 个 Gradle task（18 executed，271 up-to-date）。

## Out of Scope

- 引入第三方导航框架或改变现有导航信息架构。

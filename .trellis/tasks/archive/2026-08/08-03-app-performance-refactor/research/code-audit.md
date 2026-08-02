# Code Audit Evidence

## Quality Baseline

- `core/data/src/main/kotlin/com/fnmusic/tv/core/data/server/ConnectionResolver.kt:84` 使用 `Map.forEach`，lint 判定需 API 24。
- `core/data/src/main/kotlin/com/fnmusic/tv/core/data/api/TrimMusicApi.kt:155` 存在同类问题。
- 审计时执行 `./gradlew test lint --continue`：396 个测试执行、0 失败；`core:data` lint 因上述 2 个错误失败，另有 9 个非阻断 UseKtx 警告。

## Playback Hot Path

- `app/src/main/java/com/fnmusic/tv/ui/FnMusicApp.kt:94` 在根层收集完整 `PlaybackUiState`，并在 `:110` 下传给整个已登录界面。
- `core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:1093` 启动 250 ms ticker。
- `PlaybackController.kt:192` 的 `onEvents` 与专用回调都调用 `project`，存在重复投影机会。
- `PlaybackController.kt:779` 每次投影重建最多 250 项队列。
- `PlaybackController.kt:1162` 的定时快照在构造请求前同步 capture/encode。

## Lifecycle And Storage

- `app/src/main/java/com/fnmusic/tv/ui/AuthenticatedApp.kt:266` 的三类 retained state Map 无容量或释放入口。
- `AuthenticatedApp.kt:365` 使用 `SaveableStateProvider`，动态 route 出栈后没有对应 `removeState`。
- `core/data/src/main/kotlin/com/fnmusic/tv/core/data/repository/ArtworkCache.kt:343` 每次剪枝遍历并按时间排序全部缓存文件。
- `core/data/src/main/kotlin/com/fnmusic/tv/core/data/local/LocalStore.kt:86` 的预算检查重复执行三类聚合统计，并可能在循环中 checkpoint/vacuum。
- `core/data/src/main/kotlin/com/fnmusic/tv/core/data/repository/SessionRepository.kt:48` 在对象构造时同步读取安全 token 和 access code。

## Coupling And Maintainability

- `AuthenticatedApp.kt:324` 直接接收 `AppContainer` 和完整播放状态。
- `AuthenticatedApp.kt:359` 通过错误字符串包含 `BAD_HTTP_STATUS` 触发会话验证。
- UI 内存在退出登录、缓存清理与播放协调等跨层编排。
- `AuthenticatedApp.kt` 约 4416 行，`PlaybackController.kt` 约 1353 行；`FnMusicApp.kt` 还包含无调用的旧 UI 实现。
- baseline profile 模块已存在，但 app 未形成有效消费链；审计生成物中没有 app 自身规则。

## Constraints From Project Specs

- `.trellis/spec/frontend/android-tv-interaction.md`：保持 TV 焦点、遥控器和返回行为稳定。
- `.trellis/spec/frontend/state-management.md`：状态所有权应贴近消费方并避免宽泛状态传播。
- `.trellis/spec/backend/android-client-contracts.md`：保持 Android 客户端兼容和网络契约。
- `.trellis/spec/guides/cross-layer-thinking-guide.md`：跨层数据与错误语义需要显式契约。

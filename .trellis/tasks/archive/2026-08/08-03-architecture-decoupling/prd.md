# UI、业务编排与数据层解耦

## Goal

让 UI、播放、会话和数据模块各自拥有单一职责，通过窄契约协作，降低 `AppContainer` 和具体实现的跨层扩散。

## Dependency

- 在 `08-03-cache-startup-performance` 完成后启动，基于已稳定的状态和存储边界调整依赖。

## Requirements

- R1：UI 接收页面状态与动作接口，不直接持有全量 `AppContainer` 进行跨层编排。
- R2：退出登录、缓存 namespace 切换/清理、播放停止和鉴权恢复由单一业务编排边界负责。
- R3：用类型化错误/事件替代 UI 对 `BAD_HTTP_STATUS` 字符串的包含判断。
- R4：播放模块依赖能力接口而非 `core:data` 具体仓库；接口放在拥有业务契约的稳定层。
- R5：保持现有调用顺序、取消语义和用户可见错误行为，不引入新框架。

## Acceptance Criteria

- [x] 页面 Composable 的核心入口不再依赖全量 `AppContainer`。
- [x] 跨模块退出/清理/鉴权流程只有一个明确所有者，并有顺序测试。
- [x] UI 不解析基础设施错误字符串。
- [x] 模块依赖图不再要求播放模块依赖数据层具体实现，或保留的依赖有明确契约理由。
- [x] 登录、退出、鉴权过期、缓存切换和播放停止行为测试通过。

## Verification

- `./gradlew :core:playback:testDebugUnitTest :core:playback:lintDebug :app:testSideloadDebugUnitTest :app:lintSideloadDebug` 通过。
- `./gradlew test lint --continue` 通过，共 289 个任务。
- 结构检查确认 `core:playback` 无 `core:data` 引用，已登录 UI 无 `AppContainer`、`BAD_HTTP_STATUS` 或直接登出/鉴权调用。

## Out of Scope

- 全项目 Clean Architecture 重写、依赖注入框架迁移或公共 API 大规模重命名。

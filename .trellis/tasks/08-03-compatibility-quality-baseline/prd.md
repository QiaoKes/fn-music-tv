# Android 23 兼容性与质量基线

## Goal

在不改变请求 header、鉴权和连接行为的前提下，消除当前 API 23 lint 阻断，并建立后续重构可依赖的绿色质量基线。

## Requirements

- R1：将 `ConnectionResolver.kt:84` 和 `TrimMusicApi.kt:155` 的 API 24 `Map.forEach` 用 API 23 安全的显式条目迭代等量替换。
- R2：header 的键、值、添加顺序和 OkHttp 覆盖语义保持不变。
- R3：补充或复用契约测试，覆盖普通连接、relay access code 和 authenticated token header。
- R4：本子任务不处理非阻断 UseKtx 警告，也不顺带调整网络层结构。

## Acceptance Criteria

- [x] `core:data` 在 minSdk 23 下不再报告 `NewApi` 错误。
- [x] header 契约测试通过，网络请求行为无变化。
- [x] `./gradlew :core:data:testDebugUnitTest :core:data:lintDebug` 通过。
- [x] `./gradlew test lint --continue` 通过或只剩与本次改动无关且已记录的环境性问题。

## Verification

- `./gradlew :core:data:testDebugUnitTest :core:data:lintDebug`：通过。
- `./gradlew test lint --continue`：通过，289 个 Gradle task（53 executed，236 up-to-date）。
- 新增 `access code verification preserves relay request headers` 回归测试，覆盖 relay cookie、access code 与 source header。

## Out of Scope

- 播放、UI、缓存、路由和架构重构。
- 批量清理 lint warning 或升级 Android/Kotlin 依赖。

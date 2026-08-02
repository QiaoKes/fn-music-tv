# 源码清理与性能回归验证

## Goal

在前五阶段行为稳定后，按既有职责拆分超大源码、删除已确认死代码和无用依赖，并用最终质量与性能验证关闭重构。

## Dependency

- 在 `08-03-architecture-decoupling` 完成后最后启动。

## Requirements

- R1：按页面/领域职责拆分 `AuthenticatedApp.kt`，按投影、持久化、队列/漫游职责拆分 `PlaybackController.kt`，保持 API 和控制流不变。
- R2：删除 `FnMusicApp.kt` 中确认无调用的旧 UI 实现。
- R3：仅移除由依赖分析和构建验证确认无用的依赖与导入，不做版本升级。
- R4：补齐 app 自身 baseline profile 消费/生成链路，避免只有依赖库规则。
- R5：执行全量单元测试、lint、设备测试和性能对比；设备不可用项必须记录。

## Acceptance Criteria

- [x] 超大文件按高内聚职责拆分，包可见性和行为测试保持通过。
- [x] 死代码和确认无用依赖已删除，release 构建通过。
- [x] `./gradlew test lint --continue` 通过。
- [x] app baseline profile 含 `com/fnmusic/tv` 自身规则并被 release 构建消费。
- [x] 设备可用时完成关键路径截图/焦点回归、macrobenchmark 与 profile 验证；不可用时提交明确缺口和命令。
- [x] 最终报告对比重组范围、主线程热路径和缓存/启动行为，确认无业务、UI 或控制逻辑变化。

## Out of Scope

- UI 改版、功能新增、依赖升级和与本次性能目标无关的格式化重写。

## Verification

- `AuthenticatedApp.kt` 从 4477 行降至 2217 行；播放器 UI、library 状态、播放器展示投影和设置页分别独立。`PlaybackController.kt` 从 1391 行降至 1244 行，状态/队列投影与快照提交跟踪器独立。
- 删除旧 `TopBar/HomeScreen/MyScreen/PlayerScreen/LibraryBand/MediaCard` 和未引用的 Navigation Compose、Lifecycle ViewModel Compose、tooling-preview、Espresso 直接依赖。
- `./gradlew test lint assembleSideloadRelease assembleStoreRelease --continue -PallowUnsignedRelease=true` 通过，共 382 个任务。
- 产出 `fn-music-tv-0.1.11-sideload-release.apk` 与 `fn-music-tv-0.1.11-store-release.apk`；两者的 merged art profile 均包含 13 条 `Lcom/fnmusic/tv` 自身规则。
- `adb devices` 无连接设备，因此未执行关键路径截图/焦点、connected UI、profile 重新采集和 macrobenchmark。复现命令：
  - `./gradlew :app:connectedSideloadDebugAndroidTest`
  - `./gradlew :baselineprofile:connectedNonMinifiedReleaseAndroidTest`
  - `./gradlew :baselineprofile:collectNonMinifiedReleaseBaselineProfile`
  - `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`

## Performance Comparison

- 播放进度：根稳定状态 4 Hz 重组 -> 仅播放器歌词/进度子树订阅高频 flow。
- 队列投影：每次状态投影重建最多 250 项 -> 仅 timeline/item/metadata 事件重建。
- 快照：调用线程编码 -> FIFO 内按 revision 在 Default dispatcher 编码。
- 图片缓存：每次写入 walk/sort -> 启动校准后的增量字节账本，越界才剪枝。
- Room：每次保存 SUM/checkpoint/vacuum -> 估算阈值审计，实际淘汰后才回收空间。
- 启动：Application 构造读取安全存储 -> restore 阶段在 IO dispatcher 单次加载。
- 页面状态：详情状态无限保留 -> 路由完全离栈后释放 saveable 与详情 retained key。

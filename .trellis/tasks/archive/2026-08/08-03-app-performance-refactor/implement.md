# Implementation Plan

## Ordered Checklist

- [x] 1. 完成 `08-03-compatibility-quality-baseline`：修复 API 23 lint 阻断，建立全量测试/lint 基线。
- [x] 2. 完成 `08-03-playback-state-performance`：拆分播放状态订阅，分类播放器事件，后台编码快照。
- [x] 3. 完成 `08-03-route-state-lifecycle`：为 retained/saveable route state 建立出栈回收规则。
- [x] 4. 完成 `08-03-cache-startup-performance`：优化图片缓存剪枝、数据库预算治理和会话启动读取。
- [x] 5. 完成 `08-03-architecture-decoupling`：引入窄 UI 契约与业务编排边界，收紧模块依赖。
- [x] 6. 完成 `08-03-source-cleanup-performance-validation`：按职责拆文件、删除死代码、清理确认无用依赖并执行性能回归验证。
- [x] 7. 汇总六个子任务结果，执行最终跨层行为检查并关闭父任务。

## Validation Gates

- 每个阶段：运行受影响模块的单元测试和 lint。
- 每个阶段：运行现有行为契约测试，确认业务、UI 布局、焦点和控制逻辑未变。
- 播放阶段：验证高频进度流不触发非播放器页面重组，队列仅在结构事件投影。
- 路由阶段：验证栈内恢复和出栈释放。
- 缓存阶段：验证并发、容量边界、异常恢复和冷启动状态。
- 架构阶段：检查依赖方向，不允许 UI 新增对具体数据实现的依赖。
- 最终阶段：运行 `./gradlew test lint --continue`；设备可用时运行 instrumentation、macrobenchmark 和 baseline profile 生成/校验。

## Risky Areas

- `PlaybackController` 的事件去重、播放恢复 revision 和漫游自动切歌。
- `AuthenticatedApp` 的焦点恢复、动态详情返回栈和页面状态保留。
- 图片缓存并发写入与数据库预算越界后的回收一致性。
- 会话初始化时序以及旧 token/access code 的读取失败行为。

## Start Condition

- 父任务保持 `planning`，不直接启动；每次只启动拥有下一项交付物的子任务。
- 首个子任务的最新规划摘要经用户明确批准后，启动 `08-03-compatibility-quality-baseline`。

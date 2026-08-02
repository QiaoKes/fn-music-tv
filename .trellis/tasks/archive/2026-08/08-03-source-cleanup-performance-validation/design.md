# 源码整理与性能验收设计

## 源码职责

- `LibraryState.kt` 承载路由、返回栈生命周期和保留数据状态，不包含 UI 渲染。
- `PlayerPresentation.kt` 承载播放器 UI 投影与纯状态模型；播放器渲染仍保持原 Composable 树。
- `PlaybackState.kt` 承载稳定/高频状态、类型化错误和队列投影。
- `PlaybackSnapshotCommitTracker.kt` 承载快照 revision、提交确认和 durability barrier 状态。
- `PlaybackController` 保留 Media3 生命周期和控制命令；队列/漫游算法继续由现有纯 reducer/guard 文件承载。

## 清理

- 删除 `FnMusicApp.kt` 中未被入口或测试引用的旧 Home/My/Player UI，不触碰登录界面。
- 仅删除全仓搜索无引用且全变体编译确认安全的依赖。

## Baseline Profile

- app 应用 Baseline Profile consumer plugin，并通过 `baselineProfile(project(":baselineprofile"))` 连接现有生成模块。
- 保留一份覆盖 app 自身启动/登录/已登录/播放器关键类的源 profile，使无设备构建也能验证消费；设备生成仍以现有 journey 为准。

## 验证

- 全量单元测试、lint、全部 release APK 构建和 merged baseline profile 检查。
- 有设备时运行 connected UI、profile 生成和 macrobenchmark；无设备时记录缺口与可复现命令。
- 文件拆分只改变包内位置/委托，不改变 UI、焦点或控制流。

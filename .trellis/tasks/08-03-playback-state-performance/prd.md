# 播放状态边界与主线程性能

## Goal

隔离播放高频进度更新与低频结构状态，减少无效 Compose 重组、重复队列投影和主线程快照编码，同时保持全部播放控制语义。

## Dependency

- 在 `08-03-compatibility-quality-baseline` 完成并通过全量质量基线后启动。

## Requirements

- R1：按进度、当前曲目/播放元数据、队列/模式等变化频率拆分状态契约。
- R2：非播放器页面不订阅 250 ms 进度；播放器页仅在最小作用域观察进度。
- R3：播放器事件按类型投影，纯进度事件不得重建完整队列，专用回调与 `onEvents` 不重复完成同一投影。
- R4：快照 capture/encode 从主线程热路径移出，并保持 namespace、revision、顺序和最后写入获胜语义。
- R5：播放、暂停、seek、上下曲、队列、随机/循环、漫游、自动切歌和恢复逻辑保持不变。
- R6：歌曲点击播放、队列选择、上一首/下一首和自动切歌必须复用同一套当前歌曲呈现切换逻辑，不出现封面或歌词被提前清空的中间态。
- R7：切歌后立即保留上一首已就绪的封面与歌词视觉，直到新歌曲对应资源就绪或进入明确终态，避免网络请求期间空白等待。
- R8：播放队列通过方向键移动焦点时，列表滚动和焦点目标保持稳定，不因状态重组重复滚动或重新请求焦点。

## Acceptance Criteria

- [x] 非播放器路由不会被进度 ticker 以 4 Hz 驱动重组。
- [x] 纯进度更新不创建新的完整队列列表。
- [x] 快照 JSON 编码在线程检查中不发生于主线程。
- [x] 现有播放测试全部通过，并新增状态隔离、事件分类和快照顺序测试。
- [x] 播放器 UI 布局、控制回调和用户可见状态无变化。
- [x] 从歌曲列表点击播放与点击下一首使用统一呈现切换路径，加载期间无无图歌词中间态。
- [x] 下一首触发后旧封面和歌词持续可见，新资源就绪后一次性替换。
- [x] 播放队列连续按下键时行焦点与滚动稳定，无抽搐或回跳。

## Verification

- ticker 只更新 `PlaybackController.progress`；稳定 `state` 不再产生 position-only 发射。
- `shouldRebuildPlaybackQueue` 将队列重建限制为 timeline、media item transition 和 metadata 事件。
- `PlaybackSnapshotWriterTest` 验证捕获快照在指定后台 dispatcher 编码，现有 FIFO/屏障测试继续通过。
- `./gradlew :core:playback:test :core:playback:lint :app:testSideloadDebugUnitTest :app:lintSideloadDebug`：通过。
- `./gradlew test lint --continue`：通过，289 个 Gradle task（18 executed，271 up-to-date）。
- `PlayerVisualResourceContinuity` 在 authenticated session route host 生命周期内保留同 namespace 的歌词与已解码封面，播放器路由重建不会清空视觉。
- `MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED` 不再强制重复 presentation revision，避免队列安装/元数据补全后取消并重启封面与歌词请求。
- `./gradlew :core:playback:testDebugUnitTest :app:testSideloadDebugUnitTest :app:lintSideloadDebug`：通过。
- `./gradlew test lint --continue`：通过，289 个 Gradle task（42 executed，247 up-to-date）。
- Google TV `FnMusicTV_API36` 模拟器执行队列连续下移与焦点行删除回退两项仪器测试：2/2 通过。

## Out of Scope

- 播放 UI 设计、播放算法改写、音频缓存和 Media3 大版本升级。

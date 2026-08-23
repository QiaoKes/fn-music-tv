# 首页与我的页面 UI 改版实施计划

## 1. Prepare shared projections and tests

- [ ] 在 `AuthenticatedApp.kt` 附近增加纯数据投影，生成稳定、去重、最多三项的功能卡封面槽位。
- [ ] 为收藏预览 revision 刷新条件与封面槽位补充 JVM 单元测试。
- [ ] 保持现有 `LibraryRetainedStateStore` 会话所有权；仅在确有必要时增加最小加载 / 重置辅助函数。

Validation:

```sh
./gradlew :app:testSideloadDebugUnitTest
```

## 2. Implement shared top bar and Home

- [ ] 将 `LibraryTopBar` 的独立文字按钮替换为 `SegmentedLibraryTabs`，保留 `NowPlayingPill`。
- [ ] 新增 `HomeFeatureCard`、`FeatureCoverDeck` 与随机 / 收藏图标绘制。
- [ ] 接入专辑、歌单与收藏预览封面，处理加载、空数据、失败和不足三项。
- [ ] 保留随机漫游 busy guard、收藏路由、歌单横向列表和焦点恢复。
- [ ] 明确 Home 顶部栏、功能卡和歌单行的 D-pad 邻接。

Validation:

```sh
./gradlew :app:compileSideloadDebugKotlin :app:testSideloadDebugUnitTest
```

Rollback point: Home 组件可独立回退，不改变 Repository 或业务回调。

## 3. Implement My profile and media bands

- [ ] 新增资料条、头像、服务器徽章与两个幽灵操作按钮。
- [ ] My 歌手 / 专辑复用横向 lockup，全部歌曲使用同规格左图右文卡片，并保留完整列表终端入口。
- [ ] 在专辑下方保留可滚动到达的“音乐库 / 全部歌曲”band。
- [ ] 让 My 的全部歌曲入口复用 `CollectionArtworkFallback.Collection`，删除该入口的“全”字占位路径。
- [ ] 明确资料条、歌手、专辑、音乐库与顶部栏的 D-pad 邻接和恢复键。

Validation:

```sh
./gradlew :app:compileSideloadDebugKotlin :app:testSideloadDebugUnitTest
```

Rollback point: My 专用 lockup 不替换网格 / 详情页 lockup，便于局部回退。

## 4. Add interaction and layout coverage

- [ ] 增加 Compose / device 测试，验证分段导航、功能卡与资料条命令只触发一次。
- [ ] 验证遥控器从顶部栏到内容行、从内容行上下移动以及详情返回后的焦点恢复。
- [ ] 验证超长用户名、服务器名、歌手名和专辑名不会重叠。
- [ ] 验证所有缩放列表 / 网格首尾项和“加载更多”的焦点描边不被裁切。
- [ ] 验证全部歌曲入口与详情使用相同 Collection 图形，且语义中不存在“全”字图标占位。
- [ ] 复跑触屏按钮拖动测试，确认横向滚动不误触。

Validation:

```sh
./gradlew :app:testSideloadDebugUnitTest :app:connectedSideloadDebugAndroidTest
```

## 5. Full quality and visual verification

- [ ] 执行 app lint 和相关模块测试。
- [ ] 构建并安装 sideload debug 到 `FnMusicTV_API36`。
- [ ] 在 1920×1080 截取 Home、My 首屏和 My 音乐库区，逐项对照视觉稿与 PRD。
- [ ] 检查真实 NAS 数据、空收藏 / 少封面回退、网络失败、触屏和 D-pad。
- [ ] 完成 Trellis quality check；若实现暴露新的稳定 TV 交互约束，再更新 spec。

## 6. Reuse the fixed full-catalog pager

- [x] 抽取 `PagedCatalogPage<T>`，全部歌手和全部专辑共享 4 列 x 3 行、底部分页器与显式 D-pad 邻接。
- [x] Repository 将 `size = 12` 直接传给歌手 / 专辑接口，并把 size 纳入响应和磁盘缓存键。
- [x] 保留媒体差异：歌手继续使用圆形头像，专辑继续使用方形封面。
- [x] 当前页稳定后异步预取下一服务端页；翻页期间不改变当前页或抢占焦点。
- [x] 增加分页计算、缓存键及 API 查询参数的回归测试。
- [x] 封住 My 横向媒体带首尾焦点边界，终端“全部”项继续按右键不再跳到顶部控件。
- [x] 修正 Repository 构造分页结果时遗留的 50 条页大小，确保 12 条请求不会在第 7 页错误结束。
- [x] 删除 Home 无媒体顶部栏遗留的“回声台”回退文字，仅在启动品牌过渡保留 Logo + 品牌名。

Validation:

```sh
./gradlew :core:data:testDebugUnitTest :app:testSideloadDebugUnitTest :app:compileSideloadDebugKotlin
```

Validation:

```sh
./gradlew \
  :core:model:test \
  :core:data:testDebugUnitTest \
  :core:playback:testDebugUnitTest \
  :app:testSideloadDebugUnitTest \
  :app:lintSideloadDebug \
  :app:lintStoreDebug
```

## Final review gate

- [ ] 所有 PRD acceptance criteria 有对应测试或模拟器证据。
- [ ] `git diff` 仅包含本任务代码、测试、设计和 Trellis 记录；不覆盖用户已有未提交内容。
- [ ] 在提交前向用户展示最终模拟器截图并确认视觉结果。

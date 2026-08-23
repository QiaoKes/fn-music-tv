# 首页与我的页面 UI 改版技术设计

## Scope and boundaries

本次主要调整 `app` 层已登录后的 Home / My 概览界面，并让歌手 / 专辑 Repository 透传现有服务端页大小参数。路由、播放控制、账号协调、详情页和设置页保持现有边界。主要实现位于 `app/src/main/java/com/fnmusic/tv/ui/AuthenticatedApp.kt`，共享状态仍由 `LibraryRetainedStateStore` 和现有 `AuthenticatedAppDependencies` 提供。

视觉基准：

- 首页：`docs/ui-redesign/design-a-home-dynamic.png`
- 我的：`docs/ui-redesign/design-a-my.png`

## Component design

### Shared top bar

- `LibraryTopBar` 保留左侧 `NowPlayingPill`，右侧委托给新的 `SegmentedLibraryTabs`。
- 分段容器为固定高度胶囊；当前页分段使用 `FnColors.Coral` 实底和深色文字，非当前页保持透明。
- 当前页分段不执行重复导航；非当前页仍是项目触屏兼容 `Button`，拥有稳定 semantics 与 `FocusRequester`。
- 焦点态在分段边界或表面色上显示，不改变组件尺寸。页面向上焦点邻接显式指向正在播放胶囊或可切换分段。

### Home feature cards

- 新建 `HomeFeatureCard`，使用 `Row` + `weight(1f)` 形成两个等宽、固定 150dp 高的宽卡；卡片本身是唯一交互和焦点目标。
- 新建 `FeatureCoverDeck`，接收最多三个 `FeatureArtworkItem(title, coverId)`。三个 `RemoteArtwork(CoverVariant.Grid, fallbackVariant = CoverVariant.Compact)` 固定为同尺寸正方形，通过 `graphicsLayer` 设置位置、旋转和阴影。
- `Roam` deck 使用向外角度，`Favorites` deck 使用以中间封面为前景的对称收拢角度。封面层、遮罩、图标和标题均 `matchParentSize` 内绘制，不参与焦点。
- 随机漫游封面优先来自共享 `grid:albums` 第一页，再使用首页歌单封面补足；收藏封面来自独立的 `home:favorites-preview` 第一页。封面 ID 去重后最多取三张，选择顺序在同一登录会话中稳定。
- 首页进入时并行触发现有歌单、专辑和收藏预览加载。收藏预览观察 `FavoriteLibraryState.revision`；成功收藏变更后重置并刷新预览。预览失败只回退占位，不阻止功能卡点击，也不新增页面级错误。
- 无封面 ID 的条目在投影阶段过滤；不足三个有效封面时按实际数量使用居中的 1 张或 2 张布局，不再用首字占位补足。远端加载期间使用中性深色底，不显示标题首字卡片。
- `ArtworkBitmapCache.getProgressively` 先查找精确 Grid 缓存；未命中时加载并立即发布 Compact 过渡图，再异步加载 Grid 并替换。Grid 加载失败时保留已发布的 Compact 图；缓存键仍包含账号命名空间、封面 ID 和图片规格，不跨账号复用。

### My profile strip

- 移除重复的“我的音乐”页面大标题，`ProfileStrip` 在顶部导航后以 12dp 间距直接出现，为媒体内容释放更多垂直空间。
- `LibraryTopBar` 在 Home / My 无媒体时都保留空白左侧槽位，使分段导航继续右对齐，且不显示常驻品牌文字；会话加载态 `BrandLoading` 复用 `R.drawable.ic_logo`，只在启动过渡中的品牌文字左侧显示 54dp Logo。
- 新建 `ProfileStrip`，内部为 `ProfileAvatar`、用户名、`ServerChip`、弹性间隔、设置与切换账号两个 `ProfileActionButton`。
- `ProfileStrip` 只承担稳定布局，不绘制外层背景或边框；两个操作按钮继续独立表达可操作性和焦点状态。
- 用户名和服务器名限制为单行省略；操作区固定宽度，身份区使用 `weight(1f)`，避免长文本挤压按钮。
- 设置与切换账号继续调用传入回调；图标使用项目现有 Canvas / 矢量绘制习惯，不增加图标库依赖。

### My media bands

- 保留 `MediaBand` 的 `LazyRow` 和终端入口语义；歌手和专辑复用现有横向 `ArtistLockup` / `AlbumLockup`，全部歌曲使用同尺寸的 `MyLibraryLockup`。
- Home 的“全部歌单”使用独立 `PlaylistGrid` 四宫格占位，与 My 的“全部歌曲”曲库插画保持语义区分；默认 artwork 不附加珊瑚边框，聚焦时才条件式添加边框 Modifier。
- 三类媒体项目均为约 170×95dp 的横向卡片：固定 artwork 位于左侧，名称与次要信息位于右侧；名称最多两行并省略。
- 共享 `lockupButtonColors` 的常态与禁用表面为透明，`lockupButtonBorder` 的常态边框为透明；聚焦 / 按压仍使用深灰表面、1.5dp 珊瑚描边和 1.025 倍缩放。
- “全部歌手”“全部专辑”保留在各自横向列表末尾；“音乐库 / 全部歌曲”作为专辑下方第三个 band，位于首屏下方并由 `LazyColumn` 滚动到达。
- `MyLibraryLockup` 直接调用 `CollectionArtworkFallbackContent(..., CollectionArtworkFallback.Collection, ...)`，与全部歌曲详情头图复用 `HomeArtworkKind.Collection`，不再走 `InitialArtworkPlaceholder("全部歌曲", ...)`。
- 所有承载缩放卡片的 `LazyRow` / `LazyVerticalGrid` 使用 4dp `contentPadding`，给首尾焦点描边与缩放留出安全区。

### Shared full-catalog pagination

- `ArtistGrid` 和 `AlbumGrid` 均委托给泛型 `PagedCatalogPage<T>`。调用方只提供 `stateKey`、标题、总数文案、Repository loader、稳定键和现有 `ArtistLockup` / `AlbumLockup`。
- 标准 TV 视口使用 4 列 × 3 行，页容量固定为 12。网格高度由 3 行和 95dp 卡片高度显式计算，`userScrollEnabled = false`。
- `MusicRepository.artists(page, size)` 和 `albums(page, size)` 将 `size = 12` 透传给 `TrimMusicApi`。`sizedPageSourceKey` 把页大小纳入内存、响应和 Room 缓存键，使旧默认 50 项缓存与 TV 12 项缓存彻底隔离。
- `RetainedPageSnapshot.total` 保留 API 总数。一个 API page 就是一个 TV page；已预取的 12 项页按稳定媒体键追加到保留状态，`catalogPageEntries` 按相同 12 项边界取出当前页。`shouldPrefetchCatalogContinuation` 只预取相邻下一页。
- `CatalogPager` 是居中底部控件，包含两个圆形图标按钮和固定宽度页码。页码整体使用同一低对比辅助色，不单独高亮当前页；焦点状态只由左右箭头表达。焦点在第一页 / 最后一页自动交接到仍可用的方向，避免当前按钮禁用后丢失焦点。
- 媒体卡按实际网格位置显式设置左右上下邻接。末行左半优先进入上一页，右半优先进入下一页；只有一个方向可用时所有列都进入该按钮。分页器向上返回最后聚焦卡片的同一网格位置。

## Data flow

```text
MusicRepository
  ├─ playlists() ───────────────> home playlist row + roam fallback covers
  ├─ albums(1) ─> grid:albums ──> My album row + roam primary covers
  ├─ artists(1) -> grid:artists -> My artist row
  └─ favoriteTracks(1)
       -> home:favorites-preview -> favorite cover deck
       -> favorites:tracks       -> existing Favorites route (unchanged)
```

`home:favorites-preview` 与完整收藏路由分开保存，避免 Home 的装饰摘要被 Favorites 路由出栈清理，也避免让摘要状态承担队列分页契约。账号切换时现有 session-owned store 整体丢弃，不跨账号泄漏封面。

## Focus graph

- Home：左/右在随机漫游与收藏间显式移动；向下进入歌单行；歌单行向上按所在区域回到相邻功能卡；功能卡向上进入顶部栏。
- My：资料条内设置与切换账号显式互邻；媒体行纵向按资料条 → 歌手 → 专辑 → 音乐库移动，横向由各 `LazyRow` 管理；顶部可切换分段和正在播放胶囊均有显式入口。
- 当前页分段的“选中”是页面状态，不冒充真实焦点。首次加载继续等待 artist/album 初始终态后聚焦第一个可操作媒体项；恢复时优先使用保留的 `focusedKey`。

## Compatibility and failure behavior

- 无后端、模型或持久化格式变更；无需迁移。
- 动态预览为非关键装饰数据：网络失败、空收藏、无封面均不影响点击和路由。
- 1920×1080 使用视觉稿对应尺寸；较小横屏保持固定字体与组件尺寸，通过现有 `LazyRow` / `LazyColumn` 滚动，不做 viewport 字号缩放。
- 回滚只需恢复 Home/My composable 与新增测试，不涉及数据迁移或缓存清理。

## Testing strategy

- 单元测试覆盖封面选择：去重、三项上限、补位、稳定顺序、收藏 revision 刷新判定，以及 Compact 过渡图先发布、Grid 成功替换与失败保留。
- 全目录分页单元测试覆盖 12 项切片、总页数、达到已加载边界时的预取判定、页大小缓存键隔离、HTTP `size=12`，以及末行列到可用分页方向的映射。
- Compose / device 测试覆盖胶囊导航选中与焦点区分、功能卡单击、资料条长文本不重叠、全部歌曲图形复用、关键 D-pad 邻接和触屏拖动不误触。
- 在 1920×1080 TV 模拟器安装 sideload debug，分别截取 Home / My 首屏与 My 向下滚动后的音乐库区，检查空白、裁切、重叠、焦点和真实封面加载。

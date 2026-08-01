# 播放页海报模式与进度条技术设计

## Boundaries

- 主要改动限制在 `app` 模块的 Compose 播放页和聚焦测试。
- `PlaybackController`、`MusicRepository`、歌词解析器、偏好存储与 Media3 队列契约保持不变。
- 现有 `ImmersivePlayer` 继续拥有数据加载、漫游动作和控制层显示状态；视觉拆成小型无状态组件，避免两种模式复制交互逻辑。

## Component Shape

```text
ImmersivePlayer
  +-- PlayerBackdrop
  +-- PosterPlayerContent / DiscPlayerContent
  |     +-- PlayerArtwork
  |     +-- TrackHeader
  |     +-- PlayerLyrics
  +-- PlayerControlOverlay
        +-- SeekProgress
        +-- TransportActions
```

- `PosterPlayerContent` 使用固定比例左右分栏。左区约 48%，在容器内以 `ContentScale.Fit` 显示海报；右区使用有上限的歌词槽位，避免动态行数改变布局。
- `DiscPlayerContent` 使用大尺寸圆形盘面和圆形专辑盘芯，共享 `TrackHeader`、`PlayerLyrics` 和底部控制层。
- `PlayerControlOverlay` 通过根 `Box` 底部对齐覆盖内容。主内容预留视觉安全区，但控制层显隐不改变任何尺寸。

## Artwork

- 将现有图片加载/解码逻辑复用到可配置的播放器图片组件，保留 `CoverVariant.Poster` 和 `CoverVariant.Player`。
- 海报模式取消图片本身的强卡片圆角；CD 模式通过圆形 clip 生成盘芯；列表组件继续使用现有 8dp 圆角。
- 封面位图加载完成后抽样提取主色，统一限制明度和饱和度，再生成左侧主色、中部深色、右侧更深同色的水平氛围层；歌词侧另叠固定暗色保护层。
- 背景颜色使用短时缓动过渡，切歌时不闪白；无封面时使用标题与歌手哈希得到稳定色相，再经过相同的明度、饱和度约束。
- 不增加实时模糊。主色提取复用已显示的位图，避免额外网络请求和额外全屏位图副本。

## CD / Disc Visual

- 盘面用 Compose Canvas 绘制原创的深色同心圆纹理、外沿和中心盘芯，不打包或抓取网易云素材。
- 专辑封面裁成圆形后嵌入盘芯；无图时使用与海报模式一致的文字占位。
- 播放状态驱动无限旋转动画，暂停时保持当前角度。动画层只旋转盘面与盘芯，不旋转歌词或控制区。
- 在盘面上层绘制一支原创、简化的静态唱臂，位置不随盘面旋转；只借鉴“唱片 + 唱臂”的物件关系，不复刻参考产品的角度、比例和细节。
- 保留上个任务截图的“左侧唱片、右侧歌词”空间关系，获得用户要的 CD 模式识别度，同时维持原创边界。

## Lyrics

- 从 active index 取当前行前后上下文，固定为最多 4 组；当前行靠近可读区域中部偏上。
- 歌词容器保持固定总高度；有当前行时，当前组获得约 112dp、允许最多 4 个视觉行，另外 3 个上下文组各约 48dp、允许 2 行；没有当前行时各组均分高度。`texts` 仍按同一时间点换行展示，保持翻译/双语关联，不使用省略号截断当前组。
- 当前组使用 `FnColors.Text`、较大字号与 Bold；相邻组按距离降低 alpha。静态歌词、加载、失败和无歌词状态复用同一稳定容器。

## Controls And Focus

- 根层保存 `controlsVisible` 和 5 秒交互 epoch。控制层显示时默认焦点为播放/暂停。
- 进度条拥有独立 `FocusRequester`。播放/暂停按 Up 进入进度条，进度条按 Down 返回播放/暂停。
- 进度条由 Compose 绘制为细轨道、已播放段和小圆点；焦点只改变圆点尺寸/颜色及可见光晕，不画整框。
- 传输按钮维持显式焦点图：previous <-> play/pause <-> next <-> exit roam。上下键不交给几何焦点搜索。
- 控制层使用覆盖层，不拦截系统音量键或媒体键；现有隐藏态首键消费、Back 行为和漫游单飞保持不变。

## Home Card Media

- 将现有通用 `MediaTile` 拆为 `PlaylistTile`、`ArtistLockup` 和 `AlbumLockup`，不改数据请求和导航结构。
- `PlaylistTile` 采用上图下文；`ArtistLockup` 采用圆形头像 + 右侧双行信息；`AlbumLockup` 采用方形封面 + 右侧双行信息。物理尺寸按 HTML 1920x1080 原型映射到 320dpi Compose 单位。
- 有 `coverId` 时使用 `ContentScale.Crop` 填满各自媒体边界；无图歌手使用稳定首字圆形头像，无图专辑/歌单使用原创封套视觉。
- 所有类型使用固定尺寸、8dp 形状和显式 1.025 焦点缩放，加载占位与最终图片共享相同边界。

## Compatibility And Risk

- 风险最高的是 1080p 垂直空间和焦点切换。用固定控制层高度、歌词组高度和显式焦点路由降低风险。
- 海报图片极端比例可能产生留白，这是“完整显示且不裁切”的预期代价；不得用拉伸或重度模糊消除留白。CD 盘芯为了圆形构图允许居中裁切。
- 若 Android TV Material 默认按钮焦点样式与设计冲突，只在播放器局部使用定制焦点容器，不全局修改主题。
- 回滚点是 `AuthenticatedApp.kt` 的播放器组件区；数据层和数据库不需要迁移或回滚。

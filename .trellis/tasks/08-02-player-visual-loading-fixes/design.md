# Design: 优化播放页视觉与切歌体验

## Boundaries

- `AuthenticatedApp.kt`：播放器展示状态、封面种子色映射、按钮/队列布局、会话级媒体列表保留。
- `NowPlayingPresenter.kt`：继续作为当前歌曲资源的唯一并发与身份所有者；不允许旧 revision 回写。
- `PlayerUiStateTest.kt` 与 `PlayerOverlayFocusTest.kt`：覆盖确定性取色、资源保持投影和固定布局语义/焦点。
- Gradle version catalog / app dependency：仅在采用 AndroidX Palette 时增加官方依赖。

## Color Extraction

### Input and threading

封面已经在 `Dispatchers.Default` 解码。取色与解码放在同一后台计算结果中，产出 `bitmap + ambienceSeed`，避免在 Composable 重组期间扫描位图。

### Selection

1. 使用量化色板而不是 9 x 9 的最高计数桶。
2. 过滤近透明像素；抑制几乎纯黑/纯白且缺乏色彩信息的 swatch。
3. 对候选色按人口、饱和度和适合深色 UI 的亮度联合评分，优先 `darkVibrant/vibrant/muted/darkMuted` 范围内的代表色，而不是只取人口最大项。
4. 彩色候选缺失时选择色板中人口最高的中性色；封面完全不可用时使用固定品牌深色回退色。
5. 将选中色相映射到受控饱和度和明度，并继续通过 `posterSurfaceColor` 生成海报模式面板色。

这与 Android 官方 Palette 文档公开的评分维度一致。网易云 TV 只能作为视觉参照，无法验证其内部算法是否相同。

## Retained Library Data

在 `LibraryRetainedStateStore` 增加会话级 overview 状态：

- playlists：列表、初始加载是否完成、错误、进行中标记；首页与全部歌单共享。
- artists/albums：直接复用现有 `paged("grid:artists")` / `paged("grid:albums")` 的第一页快照；“我的音乐”读取同一快照，全部列表继续追加分页。
- shared libraries：保留在 overview 状态，避免“我的音乐”返回时整页等待。

store 已由 `remember(session.user.guid, retainedScope)` 绑定登录用户，因此账号变化会自然丢弃旧会话数据。

## Seamless Player Projection

保留 presenter 的严格 current identity contract，在 Composable 展示层引入 `RetainedPlayerVisuals`：

- `artwork`: 只在当前 identity 的 artwork 完成解码后替换；当前资源为 `Absent`/失败时清到稳定占位，`Loading` 时不清旧 bitmap。
- `lyrics`: 当前 identity 为 `Loading` 时沿用上一份可显示 lyric payload；到 `Ready`、`Absent` 或失败终态时切换。
- 保留内容必须带来源 identity，且只用于视觉过渡，不能重新进入 presenter 或覆盖当前资源状态。
- 标题、歌手、格式、播放进度和控制能力继续直接读取当前 playback/presentation。
- 首次播放无 retained 内容时沿用现有 loading UI。

切歌形成以下流程：

```text
current identity changes
  -> presenter publishes current Loading states
  -> UI keeps last decoded artwork / displayable lyrics
  -> current artwork decodes -> replace artwork + animate color
  -> current lyrics reaches terminal state -> replace lyrics/fallback
```

## Explicit Centering

- 文本型 `PlayerSideActionButton` 使用零内容内边距，并在 `Box(fillMaxSize(), contentAlignment = Center)` 中设置明确 lineHeight。
- 队列按钮使用零内容内边距，内部 `Row(fillMaxSize(), verticalAlignment = CenterVertically)`；标题/歌手列使用固定、匹配字体的 lineHeight 和 `Arrangement.Center`。
- 状态标签与序号也设置显式 lineHeight。重试行复用满尺寸居中容器。
- 保持所有按钮外部尺寸和 focus modifier 顺序不变。

## Compatibility and Rollback

- 不修改 API、数据库或播放快照格式，无迁移需求。
- 若 Palette 依赖在当前 minSdk/构建环境不兼容，可退回项目内确定性量化器，但仍必须保留联合评分和测试。
- 每部分可独立回滚：取色、布局、列表保留、播放视觉保持之间没有数据格式耦合。

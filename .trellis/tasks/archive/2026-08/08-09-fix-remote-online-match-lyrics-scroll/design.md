# Design: 统一歌词解析、选择与 TV 滚动展示

## Architecture Boundaries

- `core:lyrics`：唯一的歌词语法适配层。封装 `accompanist-lyrics-core`、注册 QRC 插件，并让匹配与质量评估直接消费 SDK 歌词模型。
- `core:data`：继续负责 FnMusic 和线上来源请求、鉴权、Base64/压缩/加密传输解包，以及候选编排；不得自行解析歌词语法。
- `core:model`：删除 `LrcParser.kt` 中的解析器及旧 `LyricLine/LyricWord/LyricTimeline` 层级；不再承担歌词解析或时间轴建模。
- `app`：直接消费 `accompanist-lyrics-core` 的模型，由 `accompanist-lyrics-ui` 负责列表布局、跟随滚动和逐字绘制。
- `README.md`：仅在已有 `## 特别感谢` 中增加两个项目链接和用途说明。

## Dependencies

- 在 version catalog 中显式锁定 `accompanist-lyrics-core:0.4.7` 与 `accompanist-lyrics-ui:1.0.19`，避免 UI 的传递版本覆盖 core 版本。
- `accompanist-lyrics-ui` 的所有近期版本均声明 Android minSdk 29；应用 minSdk 从 23 提升到 29，不使用 `tools:overrideLibrary` 绕过依赖契约。核心 library 模块无需随之提高。
- 先执行最小编译验证。若 UI 依赖要求 Kotlin 2.3.x，则统一升级 Kotlin/Compose 编译链并同步 serialization 版本；不得用排除关键运行时依赖的方式掩盖二进制不兼容。
- SDK 歌词类型可以作为 `core:lyrics`、`core:data` 与 app 之间的明确契约，但不得进入与歌词无关的 `core:model`。依赖版本集中锁定，降低升级影响。

## Parsing Pipeline

```text
FnMusic / provider response
  -> provider transport decode (Base64, KRC decrypt/decompress, QRC payload extraction)
  -> parser registry (QRC plugin, YRC, KRC, Enhanced LRC/LRC)
  -> accompanist normalized lyrics
  -> Japanese display normalization
  -> duration gate and candidate selection
  -> accompanist-lyrics-ui
```

- QRC 插件必须排在宽泛的 LRC 识别器之前，只接受明确的 QRC 逐字标记，并以 QQ 原始 fixture 覆盖多行、逐字时长、翻译和损坏输入。
- 网易云优先请求 YRC，失败或缺失时回退 LRC；QQ 优先请求真实 QRC 响应，失败或缺失时回退当前 LRC；酷狗优先请求并解包 KRC，失败时回退多轨 LRC。
- FnMusic 与本地 LRC 也通过同一 registry，`CurrentLyrics` 只携带原始文档信息和 SDK 歌词对象，不再转换为项目自定义时间轴。

## Legacy Removal

- 删除 `core/lyrics/.../LyricsTextParser.kt`、`core/model/.../lyric/LrcParser.kt` 及对应 parser 测试；SDK fixture 测试取代其有效行为覆盖。
- 删除 `TimedLyricsWord`、`TimedLyricsLine`、`TimedLyricsTrack`、`SourceLyrics` 等旧中间模型，让来源、质量评估、匹配结果和播放器共用 SDK 模型。
- 删除 `PlayerScreen.kt` 中的 `currentAndNextLyricIndices`、旧歌词 slot/line、手写逐字 `AnnotatedString`、旧切行动画等 SDK 已接管的代码，以及绑定这些实现细节的测试。
- 删除迁移后无引用的 alignment、LRC timestamp 重建和旧 UI 常量；不保留 deprecated wrapper 或双写兼容期。
- 缓存若因 SDK 类型不可序列化而需要 DTO，只允许私有持久化映射，并集中在缓存边界；运行时立即还原为 SDK 模型，不能进入匹配、选择或 UI 接口。

## Japanese Annotation Normalization

- 只识别附着在正文后的半角/全角日文假名括号注音，例如 `世(よ)`、`虚（むな）`；删除括号段，保留正文。
- 对行文本、翻译文本和逐字 syllable 序列使用同一窄范围 normalizer。YRC 中括号与假名若是独立 syllable，需要丢弃这些 syllable，同时保留正文 syllable 的原时间。
- 清理后合并展示文本，但不重算歌曲时间、不把括号内容当翻译，也不删除普通说明性括号。fixture 需明确覆盖半角、全角、连续注音和非注音括号。

## Candidate Selection

1. 先沿用现有标题、歌手等元数据最低阈值剔除不可信候选；普通逐行歌词的最大时长差固定为 5,000 ms。
2. 解析每个可获取候选，记录来源、绝对时长差、翻译覆盖和有效逐字覆盖。
3. `wordEligible = 两侧时长已知 && durationDelta <= 2000ms && 有有效逐字覆盖`。
4. 所有可用候选统一按翻译覆盖、逐字可用、时长差、网易云/QQ/酷狗、稳定 ID 排序；翻译优先级高于逐字同步。
5. 逐字候选的时长差超过 2,000 ms 但不超过 5,000 ms 时，通过 SDK 模型变换清空 syllable/word timing 后参与逐行选择；超过 5,000 ms 时直接排除。
6. 提升 `MATCH_PROTOCOL_VERSION`，使旧缓存失效并按新规则重新匹配。

排序实现为具名 rank tuple，避免在多个 comparator 中重复或出现相反的来源顺序。

## TV Lyrics UI

- 两种播放器样式复用一个 TV 配置的 `KaraokeLyricsView`：真实 `LazyColumn`、跟随播放时间自动定位、当前行强调、逐字填充和翻译展示。
- 关闭 phonetic/ruby 展示；为原文与翻译设置紧凑但独立的行高和间距，翻译字号只比原文低一级。长行允许换行；控制栏隐藏时释放其底部预留区给歌词，控制栏出现前收回歌词视口，并将当前行锚点适度上移以展示更多后续歌词。
- 在歌词容器层禁止焦点进入，并用无操作点击回调或公开的非交互配置隔离触摸行为；必须在模拟器验证方向键仍进入播放控制和侧边操作。
- 不复制库的滚动算法。若顶层组件无法满足 TV 非聚焦要求，只允许组合其公开、支持非交互的组件，且仍由库负责歌词行绘制和时间同步。

## Compatibility and Failure Handling

- 各来源原生逐字请求失败时立即走其 LRC 回退，不因单个格式或接口失败中断整个匹配。
- 畸形 QRC/KRC/YRC 返回结构化解析失败并继续候选流程，不缓存半解析结果。
- 依赖升级、QRC 请求、KRC 解包、排序规则和 UI 替换分步提交验证，便于定位兼容问题；不修改数据库 schema。
- 模拟器验证期间保留 `FnMusicTV_API36` 运行，完成后不关闭，供用户继续测试。

# Implementation Plan: 优化播放页视觉与切歌体验

## Checklist

- [x] 1. 为色板选色和播放器视觉保持补充失败优先的单元测试，覆盖低饱和大面积背景、彩色主体、无封面与身份切换。
- [x] 2. 将封面解码结果扩展为位图与后台计算的色彩种子；替换 9 x 9 最高计数桶和标题哈希回退，保持深色归一化与背景动画。
- [x] 3. 在会话级 retained store 中共享歌单、歌手、专辑和共享音乐库的首次加载快照，并让 Home/My/All 列表消费同一状态。
- [x] 4. 在播放器 UI 增加按资源终态替换的 retained visuals，加载期间保留上一封面/歌词，首次播放仍显示加载态。
- [x] 5. 为“退出漫游”、队列行和队列重试按钮加入明确满尺寸居中布局与行高，不改变尺寸、语义或焦点图。
- [x] 6. 扩展 Compose UI 测试，验证文本控件边界/焦点稳定和队列行行为；扩展 retained state 单元测试验证不重复首屏加载。
- [x] 7. 运行格式/编译、app 单元测试、相关 Compose instrumentation 测试、lint 和 debug assemble。
- [x] 8. 对照用户两张实拍封面的代表色构造或本地样本验证背景色，并检查切歌期间没有占位帧。

## Validation

```bash
./gradlew :app:testSideloadDebugUnitTest
./gradlew :app:lintSideloadDebug
./gradlew :app:assembleSideloadDebug
./gradlew :app:connectedSideloadDebugAndroidTest
```

若本机没有可用 Android TV/模拟器，instrumentation 测试记录为环境限制，但单元测试、lint 和 assemble 仍是必需门槛。

## Risk and Rollback Points

- 色板依赖和同步生成可能影响性能：取色必须和解码一起在 `Dispatchers.Default` 完成；若基准不合格，回滚依赖并使用本地量化器。
- 保留旧歌词可能造成短暂的歌曲/歌词来源不一致：仅在当前歌词为 `Loading` 时保留，并在任一终态立即替换；当前标题和进度始终显示真实歌曲。
- 共享分页首屏可能影响 continuation：只共享同一 retained snapshot，不复制或重新拼接页面元数据。
- Button 内部布局调整可能影响焦点边界：外层固定尺寸和 modifier 顺序不得改变，Compose UI 测试作为回滚门槛。

## Pre-Start Review

- [x] 用户已审阅并明确批准本规划摘要。
- [x] `prd.md` 无未解决问题，需求与验收标准无重复或冲突。
- [x] 已读取 frontend/backend/guides 相关 Trellis 规范。

## Completion Record

- `./gradlew test :app:lintSideloadDebug :app:assembleSideloadDebug` passed on 2026-08-02.
- `./gradlew :app:connectedSideloadDebugAndroidTest` passed 17/17 tests on the Android TV API 36 emulator.
- Player layout regressions assert the roam label and queue title/artist group centers against their owning fixed bounds.
- Color regressions cover a colorful subject against a dominant neutral field, black margins, fixed missing-artwork fallback, and bounded dark output.

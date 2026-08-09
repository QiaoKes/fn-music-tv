# Implementation Plan: 修复在线匹配并替换歌词栈

## Checklist

- [x] 1. 加入并锁定两个 Accompanist Lyrics 依赖，执行最小编译验证；仅在二进制兼容确有要求时统一升级 Kotlin/Compose/serialization 工具链。
- [x] 2. 在 `core:lyrics` 建立统一 parser registry 和窄范围日文括号注音 normalizer；让匹配与质量评估直接消费 SDK 模型，并补齐 LRC/YRC/KRC、双语、逐字和注音 fixture 测试。
- [x] 3. 实现唯一的 QRC parser 插件并注册在通用 LRC parser 之前，以真实 QQ fixture 验证逐字时间、翻译、损坏输入和误识别边界。
- [x] 4. 更新三个来源：网易云 YRC/LRC、QQ QRC/LRC、酷狗 KRC/LRC；传输解包与语法解析分层，并为每个首选格式和回退路径增加测试。
- [x] 5. 让 FnMusic、本地歌词和线上候选全部经过统一适配层；删除 `LyricsTextParser`、`LrcParser/LyricParser`、旧 `TimedLyrics* / SourceLyrics` 模型、旧 alignment/timestamp helper 及对应旧测试，不保留停用代码或 deprecated wrapper。
- [x] 6. 将候选 rank tuple 改为翻译优先、逐字同步其次，使用 2,000 ms 逐字门槛和 5,000 ms 逐行门槛，统一来源顺序为网易云/QQ/酷狗，增加边界值、未知时长、翻译优先和逐行降级测试；提升匹配缓存协议版本。
- [x] 7. 调整 `accompanist-lyrics-ui` 的海报/CD 视口：在控制栏安全区上方扩大展示范围并上移当前行锚点，提高翻译字号，同时保持双语紧凑间距、长行换行和非聚焦行为。
- [x] 8. 扩展 Compose 与 instrumentation 测试，覆盖滚动定位、双语间距、长行、遥控器焦点、设置确认键和 `min~眠~` 注音清理。
- [x] 9. 在 `README.md` 的 `## 特别感谢` 增加两个开源库的名称、链接和用途。
- [x] 10. 运行分模块测试、完整 app 测试、lint 和构建；安装到 Android 16 TV 模拟器，实际播放验证三个来源、两种播放器样式和遥控器操作，完成后保持模拟器开启。
- [x] 11. 用全仓搜索和依赖分析确认旧解析器、旧歌词模型、旧 UI helper 均无残留，新链路不存在双解析或双模型转换。

## Validation

```bash
./gradlew :core:lyrics:test
./gradlew :core:model:test
./gradlew :core:data:testDebugUnitTest
./gradlew :app:testSideloadDebugUnitTest
./gradlew :app:lintSideloadDebug :app:assembleSideloadDebug
./gradlew :app:connectedSideloadDebugAndroidTest
```

若模块实际生成的测试 task 名称不同，以 `./gradlew tasks` 确认后的等价 task 替换并记录结果。Android TV 模拟器验证是本任务的必需门槛，不按环境限制跳过。

## Risk and Rollback Points

- `accompanist-lyrics-ui` 的 Kotlin/Compose 版本可能高于项目当前工具链：先做依赖编译验证，再决定是否统一升级；不混用互不兼容的 Kotlin runtime。
- QQ QRC 与酷狗 KRC 的接口/封装可能变化：fixture 锁定解析契约，运行时保留各自 LRC 回退。
- UI 组件可能默认提供点击交互：以父容器焦点约束和模拟器焦点图验证为合入门槛，不接受播放器控制失焦。
- 去除括号注音可能误删说明文字：normalizer 只接受假名注音模式，普通中文/英文括号必须由反例测试保留。
- 缓存中已有旧解析结果：协议版本提升后重取，不做数据库破坏性迁移。

## Pre-Start Review

- [x] 用户已审阅并明确批准本规划摘要。
- [x] `prd.md` 无未解决问题，需求、范围和验收标准无冲突。
- [x] 已读取本任务相关 Trellis 工作流与项目规范。

## Validation Note

- 分模块单测、app 单测、lint、构建和 36 个 Android 16 TV 设备测试均通过；后续规则与布局调整再次通过全部非设备门槛和测试 APK 编译。
- QQ 真实接口返回加密 QRC，酷狗真实接口返回 `krc1` KRC；传输解包与 fixture 解析均已验证。
- `1.0.5` APK 已安装，模拟器保持运行；最终设备测试重跑曾在 3/36 时按要求停止并清除调试登录态，用户需在登录页重新登录。

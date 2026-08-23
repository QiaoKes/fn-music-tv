# 音乐台 UI 改版设计稿

针对你指出的四组文字——**「首页 / 我的」**、**「设置 / 切换账号」**、**「我的音乐 · test · nas」**——做的改版设计。两份方案，一份落地清单。

## 1. 为什么现在看起来丑

对照截图与现有代码（`AuthenticatedApp.kt`）：

| 现状 | 问题 |
| --- | --- |
| 「首页 / 我的」是右上角两个裸文字按钮 | 当前页签用**禁用态**深暖色 `#382A27` 填充，另一个是透明裸字——像坏掉的按钮，不像导航；两个词孤零零飘在角上 |
| 「设置 / 切换账号」用 TV Material3 默认按钮 | 实测容器色 `#3D3B44`（灰紫），和珊瑚/深色的品牌色系冲突；和左侧大标题「我的音乐」同级竞争，主次不分 |
| 「我的音乐」下紧跟「test · nas」 | 页面大标题 + 「用户名 · 服务器名」混排一行，中间点号让「test nas」读起来像乱码；没有头像、没有容器，三段信息挤成一条 |

## 2. 设计原则

- **导航要有容器**：分段胶囊，选中态 = 珊瑚实底 + 深色字，未选中 = 幽灵字，焦点 = 珊瑚描边。
- **身份要结构化**：头像 + 用户名 + 服务器徽章，替换「test · nas」字符串。
- **次级操作要退让**：设置/切换账号降级为小号幽灵按钮（带图标），放进资料条或侧栏底部。
- **TV 10 英尺体验**：焦点态一眼可见、字号够大、对比度足够；不改动现有珊瑚/青/深色的品牌基调。

## 3. 方案 A（推荐）· 胶囊导航 + 资料条

**顶栏**：左侧保留「正在播放」胶囊，右侧换成**分段胶囊导航**——
容器 48dp 全圆角、`#171A1E` 底 + `#2B3134` 描边；选中段珊瑚底深字加粗，未选中 `#A9ADB4`，焦点段 3dp 珊瑚内描边。

**「我的」页头部**：保留「我的音乐」作页面大标题；下面一行改成**资料条**（全宽卡片）：
`[头像] test  [🖥 nas]        ⚙ 设置   ⇄ 切换账号`

- 头像 44dp，珊瑚→青渐变底 + 用户名首字母；
- 服务器名收进**徽章 chip**（图标 + 文本 + 描边），不再用「·」硬拼；
- 设置/切换账号改成 15sp 幽灵按钮，焦点时珊瑚描边 + 柔光，不再抢标题的戏。

**规格速查**（px 为 1080p 下的 2×dp）：

| 元素 | 规格 |
| --- | --- |
| 顶栏高 | 64dp；胶囊导航 48dp 全圆角 |
| 分段 | 内边距 34dp，字号 21sp，选中 700 字重珊瑚底 |
| 页面标题 | 32sp / 700（听点什么、我的音乐） |
| 资料条 | 高 48dp，圆角 11dp，`#171A1E` 底 + 1dp 描边 |
| 头像 | 40dp 渐变圆；用户名 18sp/600 |
| 服务器徽章 | 12sp + server 图标，胶囊描边 |
| 幽灵按钮 | 高 28dp，15sp + 图标，焦点珊瑚描边 + 柔光 |
| 分组标题 | 23sp/600；歌手头像与专辑封面 75dp |

## 4. 方案 B（备选）· 左侧导航栏

把「首页 / 我的」移入左侧 210dp 竖栏（顶部品牌「回声台」，底部放「设置 / 切换账号」），内容区不再有导航噪音。适合以后导航项变多的情况；代价是遥控器左右键多一层焦点移动，与现有上下结构差异更大。

## 5. 落地清单（Compose 改动点）

全部集中在 `app/src/main/java/com/fnmusic/tv/ui/AuthenticatedApp.kt`：

1. **`LibraryTopBar`**：右侧两个 `Button` 换成 `SegmentedTabs`（选中段 `enabled = false` 保持现状防重复点击，但样式改为珊瑚实底，见下草图）。
2. **`BrowseMy` 头部**：删除「设置/切换账号」按钮行与「test · nas」副标题，换成 `ProfileStrip`；`focusedKey = "settings"` 的焦点逻辑改绑到幽灵按钮的 `Modifier` 上，其余焦点协议不变。
3. **新增 composable**：`SegmentedTabs`、`ProfileStrip`、`ServerChip`、`GhostActionButton`。
4. **`Theme.kt`（可选）**：补 `Border = Color(0xFF262C30)`、`OnAccent = Background` 两个 token，其余色值不变。

草图标段：

```kotlin
// 分段胶囊：选中 = 珊瑚底 + 深字，未选中 = 幽灵，焦点 = 珊瑚内描边
Row(
    Modifier.clip(CircleShape).background(FnColors.Surface)
        .border(1.dp, Color(0xFF2B3134), CircleShape)
        .padding(6.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
) {
    SegmentedTab("首页", selected = selectedHome, onClick = onHome)
    SegmentedTab("我的", selected = !selectedHome, onClick = onMy)
}

@Composable
private fun SegmentedTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !selected,
        modifier = Modifier.height(42.dp),
        shape = ButtonDefaults.shape(CircleShape, CircleShape, CircleShape, CircleShape, CircleShape),
        scale = ButtonDefaults.scale(focusedScale = 1.05f),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) FnColors.Coral else Color.Transparent,
            contentColor = if (selected) FnColors.Background else FnColors.Muted,
            focusedContainerColor = FnColors.Coral,
            focusedContentColor = FnColors.Background,
            disabledContainerColor = FnColors.Coral,
            disabledContentColor = FnColors.Background,
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, FnColors.Coral), CircleShape),
        ),
        contentPadding = PaddingValues(horizontal = 34.dp),
    ) { Text(label, fontSize = 21.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
}
```

```kotlin
// 资料条：头像 + 用户名 + 服务器徽章 + 幽灵操作
Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
        .background(FnColors.Surface)
        .border(1.dp, Color(0xFF262C30), RoundedCornerShape(11.dp))
        .padding(horizontal = 13.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.size(40.dp).background(Brush.linearGradient(listOf(FnColors.Coral, FnColors.Teal)), CircleShape),
        contentAlignment = Alignment.Center) {
        Text(session.user.username.take(1).uppercase(), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = FnColors.Background)
    }
    Spacer(Modifier.width(11.dp))
    Column {
        Text(session.user.username, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        ServerChip(session.server.name)   // 图标 + 文本胶囊
    }
    Spacer(Modifier.weight(1f))
    GhostActionButton("设置", onSettings)
    Spacer(Modifier.width(7.dp))
    GhostActionButton("切换账号") { scope.launch { container.authenticatedActions.switchAccount() } }
}
```

## 6. 设计稿文件

| 文件 | 说明 |
| --- | --- |
| `docs/ui-redesign/mockup-a-home.html` | 方案 A · 首页（可在浏览器/GUI 预览面板打开） |
| `docs/ui-redesign/mockup-a-my.html` | 方案 A · 我的 |
| `docs/ui-redesign/mockup-b-my.html` | 方案 B · 侧栏导航 |
| `docs/ui-redesign/design-a-home.png` / `design-a-my.png` / `design-b-my.png` | 1080p 渲染图 |
| `docs/ui-redesign/design-before-after.png` | 改版前后对照图 |

> 设计稿里演示了焦点态：首页焦点在「我的」页签与「随机漫游」卡片；我的页焦点在「切换账号」按钮与第一位歌手。选中我直接按方案 A 改 `AuthenticatedApp.kt` 即可。

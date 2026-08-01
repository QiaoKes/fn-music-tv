# Add phone launcher compatibility

## Goal

让已安装成功的 APK 能从普通 Android 手机及采用手机版 Android 桌面的投影仪启动，同时保留 Android TV 桌面入口和电视横屏行为。

## Requirements

- `MainActivity` 同时声明普通应用启动类别与 Android TV 启动类别。
- 保留现有 Leanback、电视设备和无触摸屏兼容声明。
- 不改变应用 ID、最低系统版本、屏幕方向或现有电视端导航交互。

## Acceptance Criteria

- [ ] 安装器能够识别普通启动 Activity，“打开”按钮可用。
- [ ] 普通 Android 桌面显示飞牛音乐 TV 图标并可启动应用。
- [ ] Android TV 桌面仍能识别并启动应用。
- [ ] sideload debug APK 构建成功且签名验证通过。

## Out of Scope

- 为手机触摸操作重新设计界面。
- 改变横屏锁定或电视端布局。

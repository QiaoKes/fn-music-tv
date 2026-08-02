# 回声台

原生 Android TV 客户端，使用 Kotlin、Compose for TV 与 Media3。当前 V1 开发构建包含
密码登录与会话恢复、歌单/歌手/专辑/全部歌曲浏览、普通队列与后台播放、歌词、随机漫游、
两套播放器布局，以及缓存额度设置。客户端对尚未通过真实 NAS 探针冻结的 CUE/HLS 参数
保持关闭，不会用猜测参数发起转码。

## 构建

环境要求：JDK 21、Android SDK 36。

```sh
./gradlew :core:model:test :core:data:testDebugUnitTest
./gradlew :app:lintSideloadDebug :app:lintStoreDebug
./gradlew :app:assembleSideloadDebug :app:assembleStoreDebug
```

侧载包允许用户配置的局域网 HTTP NAS，产物位于
`app/build/outputs/apk/sideload/<build-type>/`，文件名包含 `version.properties` 中的版本号。
`store` flavor 强制 HTTPS，并将 TV/Leanback
feature 设为必需；`sideload` flavor 将 TV/Leanback/touchscreen 都设为非必需，适配不声明
标准 TV feature 的 Android 投影设备。

## M0 外部验证

发布前仍需在开发 NAS 与 Vidda C3 Pro 上完成以下门槛：设备 API/ABI、逻辑 surface、
启动器 feature、D-pad 焦点路径、代表性 MP3/FLAC/CUE/不支持格式、长时间后台播放、
HLS codec/profile、heartbeat 单位与周期、歌词 offset 正负方向，以及 transcode create
最坏耗时。客户端不会猜测这些服务端参数。

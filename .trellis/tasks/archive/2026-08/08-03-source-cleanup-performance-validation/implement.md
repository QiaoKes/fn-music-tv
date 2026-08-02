# 执行计划

- [x] 删除 `FnMusicApp.kt` 已确认无调用的旧 Home/My/Player UI 与相关导入。
- [x] 抽取 library 路由/保留状态与 player 投影模型，降低 `AuthenticatedApp.kt` 的职责密度。
- [x] 抽取播放状态/队列投影和快照提交跟踪器，收敛 `PlaybackController`。
- [x] 移除无引用依赖并运行全变体编译验证。
- [x] 接通 app Baseline Profile consumer/generator，增加 app 自身启动关键路径规则。
- [x] 运行单元测试、lint、release 构建和静态性能结构检查。
- [x] 检测设备；可用时运行设备/benchmark/profile 验证，不可用时记录命令和缺口。
- [x] 更新规格、父子任务验收和最终对比报告。

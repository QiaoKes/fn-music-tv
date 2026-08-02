# 缓存与启动性能优化

## Goal

减少图片写入、本地数据库保存和会话冷启动中的高成本 IO/计算，并保持容量边界、离线数据和登录恢复语义。

## Dependency

- 在 `08-03-route-state-lifecycle` 完成后启动。

## Requirements

- R1：图片缓存使用增量大小记账或阈值触发剪枝，新增写入不再无条件扫描排序整个目录。
- R2：保留进程启动、账本缺失或异常情况下的全量校准与过期临时文件清理。
- R3：数据库预算检查区分轻量统计与昂贵回收，避免每次保存重复执行完整 SUM、checkpoint 和 vacuum 流程。
- R4：安全 token/access code 在 IO dispatcher 初始化，保持 `SessionState.Loading`、自动恢复和失败清理语义。
- R5：避免仓库层不必要的重复 JSON encode/decode，但不得改变缓存格式或容错行为。

## Acceptance Criteria

- [x] 图片缓存正常写入热路径不执行全目录 walk/sort。
- [x] 容量越界后仍按现有保留策略回收，重启后账本能被校准。
- [x] 常规数据库保存不触发不必要的 checkpoint/vacuum，越界时仍能回到预算内。
- [x] Application 主线程不执行安全存储读取。
- [x] 缓存、并发写入、数据库预算和会话恢复测试通过。

## Verification

- `./gradlew :core:data:testDebugUnitTest :core:data:lintDebug` 通过。
- `./gradlew test lint --continue` 通过，共 289 个任务。
- 图片缓存测试验证预算内连续写入不会重复全目录扫描，越界与重启校准行为保持不变。
- 本地数据库测试验证小写入只执行首次校准，实际淘汰前不执行空间回收。
- 会话测试验证构造阶段不读取安全存储，恢复阶段在非主线程只读取一次。
- 仓库层复用已解析 DTO，持久化格式与失败回退语义未改变。

## Out of Scope

- 修改缓存容量产品策略、数据库 schema、token 格式或离线功能范围。

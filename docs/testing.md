# 测试说明

默认 JVM 测试使用本地样本、fake 或本机测试服务。离线验证可显式清除联网开关，避免继承终端环境：

```bash
env -u NGA_INTEGRATION -u NGA_WRITE_SMOKE ./gradlew :app:testDebugUnitTest
# 仅验证一个测试类时，在上述命令后追加 --tests '<完整类名>'
```

| 验证 | 启用条件与影响 |
|---|---|
| JVM 线上读取冒烟 | `NGA_INTEGRATION=1`；访问真实 NGA，登录态读取还需 `NGA_UID` / `NGA_CID` |
| JVM 线上写入冒烟 | 上述条件加 `NGA_WRITE_SMOKE=1`；会执行签到、点赞、收藏增删和签名回写，可能留下真实账号状态变化 |
| 设备登录态读取 | `-Pandroid.testInstrumentationRunnerArguments.nga_integration=1`；使用目标安装变体中已有账号读取通知 |
| 冷启动 Macrobenchmark | 启动真实 app，首页可能联网；每轮在计时外冷却 60 秒，五轮至少增加五分钟等待 |
| Room 迁移与 DAO | `:app:connectedDebugAndroidTest`；`MigrationTestHelper` 读取 `app/schemas/` 的旧版本 JSON，改数据库结构后必须跑 |

联网测试按任务授权范围启用。凭证不写入仓库或验证报告。`testDebugUnitTest` 的成功不表示被跳过的线上冒烟已通过。

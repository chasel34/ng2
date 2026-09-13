# 项目文档

当前项目是仓库根目录下的 Kotlin / Jetpack Compose Android 工程。初次开发先读[项目 README](../README.md)和[开发约定](../CLAUDE.md)。

## 日常维护

| 文档 | 用途 |
|---|---|
| [术语表](../CONTEXT.md) | 版块、主题、楼层、账号与容错概念的统一命名 |
| [API 文档](API文档.md) | NGA 协议参考与本项目当前实现的差异 |
| [测试说明](testing.md) | 离线测试、联网冒烟与设备验证的适用范围 |
| [本地存储](storage.md) | Room、DataStore、凭证与缓存的持久化边界 |
| [性能手册](perf-playbook.md) | 性能判据、采样限制与历史基线 |
| [性能脚本](../scripts/perf/README.md) | 采样和分析命令 |
| [加载文案来源](loading-quotes-sources.md) | 文案清单、署名核对与来源记录 |
| [本地 Issue 约定](agents/issue-tracker.md) | `.scratch/` 中需求与问题记录的组织方式 |
| [设计参考](../design/README.md) | HTML 原型入口 |
| [金样本](../app/src/test/resources/goldens/README.md) | 解析与纯算法的对拍数据说明 |

## 技术决策

- [ADR-0001：BBCode 原生渲染](adr/0001-native-bbcode-ast-rendering.md)
- [ADR-0002：反封锁链与读写分离](adr/0002-anti-block-chain-first-class.md)
- [ADR-0003：原生 Android 重写](adr/0003-full-native-android-rewrite.md)
- [ADR-0004：Compose 与裸 OkHttp](adr/0004-native-stack-compose-bare-okhttp.md)

## 历史资料

[性能历史参考](perf-history-reference.md)汇总旧基线、设备状态与 RN 触摸模式。

[2026-08-15 RN 真机诊断](performance-diagnosis-2026-08-15.md)保留为性能判据与重写决策的证据，不是当前故障清单。`.scratch/` 保存各阶段 spec、Issue 和验收记录；文中的旧路径、旧包名和测量数值属于对应时间点。

维护文档时，优先链接源码、配置和专门文档，避免复制版本清单；长期规则留在本目录，一次性排查过程留在 `.scratch/`。删除资料前检查是否仍承担判据、来源或决策证据的作用。

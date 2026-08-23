# 票 19 真机性能验收报告

**状态:** 阻断,尚未进入十场景裁决  
**阻断票:** `issues/51-perf-baseline-profile-empty.md`

## 环境

| 项 | 值 |
|---|---|
| 验收日期 | 2026-08-23 |
| Git 基线 | `c559c92`(`android-native`) |
| 设备 | 小米 `25113PN0EC`,device `pudding` |
| 系统 | Android 16 / SDK 36 |
| 屏幕 | 1220×2656 @520dpi,目标 120Hz |
| ADB 目标 | `192.168.0.101:40039` |
| 原生包 | `com.chasel.ng2.n` |
| 采集 APK | `app-nonMinifiedRelease.apk`,SHA-256 `db10ecccb254710eba3d35768d783a18c3df55e9bc7bd8dccd572be8d61cfd5a`,非可调试 |
| Baseline Profile | **失败:** 采集结果在过滤前即为空,`baseline-prof.txt` 未生成 |
| 有效 release APK | 未生成/未安装;拒绝用无 profile 包裁决 |

## 闸前检查

`ANDROID_SERIAL=192.168.0.101:40039 ./gradlew :app:generateReleaseBaselineProfile`
在真机 `25113PN0EC - 16` 进入采集逻辑后失败:

```text
BaselineProfileGenerator > startup[25113PN0EC - 16] FAILED
java.lang.IllegalStateException: Generated Profile is empty, before filtering.
StartupBenchmark > coldStartupWithBaselineProfile[25113PN0EC - 16] SKIPPED
```

按票 19 与 T7/T8,被测包必须是打入 Baseline Profile 的 release 包。此前无线 ADB 断链与
MIUI 安装确认问题均已排除;当前是独立、确定的 profile 采集失败。已开缺陷票 51,
等待修复后从 `generateReleaseBaselineProfile` 重新开始。

## 十场景

| # | 场景 | 结果 | 说明 |
|---|---|---|---|
| 1 | 冷启动闪烁 | 阻断/未测 | 无有效 release + profile 包 |
| 2 | 冷启后首次进主题 | 阻断/未测 | 同上 |
| 3 | 主题列表快甩 | 阻断/未测 | 同上 |
| 4 | 楼层流慢拖/快甩 | 阻断/未测 | 同上 |
| 5 | 横滑翻页 | 阻断/未测 | 同上 |
| 6 | 抽屉开合 | 阻断/未测 | 同上 |
| 7 | 附件展开/收起 | 阻断/未测 | 同上 |
| 8 | 大图/画廊开合与缩放 | 阻断/未测 | 同上 |
| 9 | 各转场 latch2present / 连续丢帧 | 阻断/未测 | 同上 |
| 10 | 动画交互伞条款 | 阻断/未测 | 同上 |

## 结论

当前不是十场景“不通过”,而是强制前置产物缺失导致**不可裁决**。票 51 修复并复验通过前,
不产生任何原生版性能结论。

# 票 19 真机性能验收报告

**状态:** 进行中  
**已复验缺陷:** 票 51 Baseline Profile 采集为空(修复基线 `34589c6`)

## 环境

| 项 | 值 |
|---|---|
| 验收日期 | 2026-08-23 |
| Git 基线 | `34589c6`(`android-native`) |
| 设备 | 小米 `25113PN0EC`,device `pudding` |
| 系统 | Android 16 / SDK 36 |
| 屏幕 | 1220×2656 @520dpi,目标 120Hz |
| ADB 目标 | `192.168.0.101:40039` |
| 原生包 | `com.chasel.ng2.n` |
| Baseline Profile 源文件 | `baseline-prof.txt`,23,899 行, SHA-256 `75ce22259585423e06e38808105ba46f2addc3dc22fbb5b0ccd1740ce548abdd` |
| Release APK | `app-release.apk`,5.0 MiB, SHA-256 `c803ccfb2d7d175f98a8656c670c787cbab2cd14db15125ec841219827656c43` |
| 包变体 | release,非可调试,`com.chasel.ng2.n` 0.1.0(1) |
| Profile 打包 | APK 含 `assets/dexopt/baseline.prof`(10,400 B)与 `baseline.profm`(1,396 B) |
| 设备 dexopt | `arm64: [status=speed-profile] [reason=baseline]`,odex 7,868 KiB |

## Baseline Profile 闸前检查

票 51 修复后,profileBlock 改为首页→版块→详情→甩动并驻留 ≥12 秒;生成文件已在
`34589c6` 提交。设备 `dumpsys package com.chasel.ng2.n` 明确报告:

```text
Dexopt state:
  [com.chasel.ng2.n]
    arm64: [status=speed-profile] [reason=baseline] [primary-abi]
```

APK profile 资产、release 非可调试属性与真机 dexopt 三项均通过。下一步进行
`compile --reset` 与 `speed-profile` 冷启 A/B;A/B 后恢复 `speed-profile` 再测十场景。

## 十场景

| # | 场景 | 结果 | 说明 |
|---|---|---|---|
| 1 | 冷启动闪烁 | 待测 | — |
| 2 | 冷启后首次进主题 | 待测 | — |
| 3 | 主题列表快甩 | 待测 | — |
| 4 | 楼层流慢拖/快甩 | 待测 | — |
| 5 | 横滑翻页 | 待测 | — |
| 6 | 抽屉开合 | 待测 | — |
| 7 | 附件展开/收起 | 待测 | — |
| 8 | 大图/画廊开合与缩放 | 待测 | — |
| 9 | 各转场 latch2present / 连续丢帧 | 待测 | — |
| 10 | 动画交互伞条款 | 待测 | — |

## 结论

进行中;待十场景与伞条款全部完成后裁决。

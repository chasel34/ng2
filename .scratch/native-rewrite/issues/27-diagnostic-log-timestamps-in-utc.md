# 27 — P3:诊断日志/组合表的时间戳按 UTC 渲染,比手机本地时间早 8 小时

**Status:** open

**Severity:** P3(只读诊断信息;但这份东西的用途就是让人对着「刚才那次失败」查,时间对不上会直接误导)

## 现象

设置 → 实验室与诊断 →「本次运行的组合」(分享)与「导出诊断日志」里,
每条请求记录前面的 `HH:mm:ss` 用的是 **UTC**,而设备时区是 `Asia/Shanghai`(UTC+8)。
于是一次「刚刚」发生的请求会显示成 8 小时前。

## 复现(模拟器 emulator-5554,com.chasel.ng2.n debug,2026-08-23)

1. 进任意主题详情,翻两页(制造 read.php 请求)
2. 设置 → 实验室与诊断 → 点「本次运行的组合」→ 系统分享面板
3. 对比面板里的时间与 `adb shell date`

实测:

```
$ adb -s emulator-5554 shell date
Sun Aug 23 14:56:28 CST 2026
$ adb -s emulator-5554 shell getprop persist.sys.timezone
Asia/Shanghai
```

分享出来的文本(请求确实是 14:50 左右发的):

```
ng2n 0.1.0 (1) · 本次运行
【当前组合】
read.php: json @ https://bbs.nga.cn
【最近 3 个请求】
06:50:55 成功 read.php?tid=47406116&page=2&v2=1 (1 次尝试) [format-rotation] json @ https://bbs.nga.cn → …
06:50:54 成功 read.php?tid=47406116&page=1&v2=1 (1 次尝试) …
```

**期望**:`14:50:55`(设备本地时区)。
**实际**:`06:50:55`(UTC)。

## 疑似代码位置

`ui/settings/LabScreen.kt:42`:

```kotlin
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)
```

写死 `ZoneOffset.UTC`。改 `ZoneId.systemDefault()` 即可。

**注意别顺手改到别处**:反封锁链里图片附件目录合成用的 UTC+8 是逆向出来的服务端怪癖
(ADR-0002 / spec「逆向怪癖勿修」),那个不能动;这里是纯展示,和它无关。

## 只影响「组合表」那一处

同屏的「导出诊断日志」用的是另一套格式,带显式时区后缀
(`2026-08-22T13:36:57.198Z app_api.php 失败:connection closed`),
虽然也是 UTC 但标了 `Z`,不会误导。要收拾的只有 `CLOCK` 这一个 formatter。

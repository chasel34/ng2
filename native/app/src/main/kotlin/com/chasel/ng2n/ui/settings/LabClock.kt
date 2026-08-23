package com.chasel.ng2n.ui.settings

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * 「本次运行的组合」里每条请求前面的钟点(`ui/settings/LabScreen.kt` 的分享文本)。
 *
 * **按设备时区渲染**(票 27)。原先这个 formatter 写死 `ZoneOffset.UTC`,
 * 于是 UTC+8 的机器上一次一分钟前发出的请求会显示成 8 小时前 —— 这份东西的用途
 * 恰恰就是让人对着「刚才那次失败」查,时间对不上会直接把人带偏。
 *
 * 与反封锁链里图片附件目录合成用的那个 UTC+8 **没有关系**:那个是逆向出来的
 * 服务端怪癖(ADR-0002 /「逆向怪癖勿修」),这里是纯展示。
 *
 * 单独一个文件是为了让它在 JVM 单测里拿得到:`LabScreen.kt` 通篇是 Compose 与
 * `android.content.*`,把纯函数留在那儿测起来要拖一整屏的依赖。
 */
internal fun runLogClock(atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
  CLOCK.withZone(zone).format(Instant.ofEpochMilli(atMillis))

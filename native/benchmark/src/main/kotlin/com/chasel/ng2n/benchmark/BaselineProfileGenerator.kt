package com.chasel.ng2n.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test

/**
 * Baseline Profile 采集骨架。
 *
 * 骨架期只走「冷启到首帧」这一段;票 19 会把十场景闸里的关键路径(主题列表快甩、
 * 楼层流、横滑翻页……)补进 `profileBlock`,采集必须在**真机**上做,模拟器的
 * 采样对 120Hz 判据没有意义。
 */
class BaselineProfileGenerator {

  @get:Rule
  val rule = BaselineProfileRule()

  @Test
  fun startup() = rule.collect(packageName = TARGET_PACKAGE) {
    pressHome()
    startActivityAndWait()
  }
}

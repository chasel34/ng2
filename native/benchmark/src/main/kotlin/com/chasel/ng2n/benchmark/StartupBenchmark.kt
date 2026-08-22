package com.chasel.ng2n.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test

internal const val TARGET_PACKAGE = "com.chasel.ng2.n"

/**
 * 冷启 macrobenchmark 骨架。骨架期只要求「能编译、能连真机跑」;
 * 真跑与判据(录屏逐帧无闪烁帧、frameOverrunMs 单峰)在票 19。
 *
 * 注意:**模拟器与 debug 包的数字永不用于性能裁决**(spec §五)。
 */
class StartupBenchmark {

  @get:Rule
  val rule = MacrobenchmarkRule()

  @Test
  fun coldStartupWithBaselineProfile() = rule.measureRepeated(
    packageName = TARGET_PACKAGE,
    metrics = listOf(StartupTimingMetric()),
    iterations = 5,
    startupMode = StartupMode.COLD,
    compilationMode = CompilationMode.Partial(),
  ) {
    pressHome()
    startActivityAndWait()
  }
}

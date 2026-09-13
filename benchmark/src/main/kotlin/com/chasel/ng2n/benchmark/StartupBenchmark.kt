package com.chasel.ng2n.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test

internal const val TARGET_PACKAGE = "com.chasel.ng2"

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
    setupBlock = {
      pressHome()
      killProcess()
      // 首页可能联网，冷却放在计时外以避免连续启动触发 NGA 限流。
      Thread.sleep(60_000)
    },
  ) {
    startActivityAndWait()
  }
}

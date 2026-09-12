package com.chasel.ng2n.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Baseline Profile 采集。
 *
 * 采集必须在**真机**上做,模拟器的采样对 120Hz 判据没有意义。
 *
 * 票 51 的教训:`profileBlock` 不能「冷启到首帧」就返回。ART 的 JIT profile 有首次落盘
 * 门槛(`dalvik.vm.ps-min-first-save-ms`,小米 25113PN0EC 上是 8000ms),宏基准在 block
 * 结束后立刻发 `SAVE_PROFILE` 并 flush —— 早于这个门槛时盘上什么都没有,`dump-profiles`
 * 出来就是空的(「Generated Profile is empty, before filtering」)。所以这里把十场景里
 * 真正要热的路径(首页宫格 → 版块列表 → 主题详情 → 列表甩动 → 返回)走一遍,
 * 总时长压在 ≥ [MIN_DWELL_MS] 之上,再交还给 rule 去 flush。
 */
class BaselineProfileGenerator {

  @get:Rule
  val rule = BaselineProfileRule()

  @Test
  fun startup() = rule.collect(packageName = TARGET_PACKAGE) {
    pressHome()
    startActivityAndWait()
    val started = System.currentTimeMillis()

    // 首页宫格就位(HomeScreen 的 `ng2n-skeleton-ready` 标签)
    device.wait(Until.hasObject(By.res("ng2n-skeleton-ready")), 15_000)
    device.waitForIdle()

    // 版块宫格第一项 → 版块列表;找不到(网络不通/限流)就退化成只在首页滚
    val board = device.findObject(By.clickable(true).hasChild(By.textContains("杂谈")))
      ?: device.findObject(By.textContains("杂谈"))
    board?.click()
    device.wait(Until.hasObject(By.textContains("版头")), 10_000)
    device.waitForIdle()

    // 列表甩动两轮(快甩路径)
    repeat(2) {
      device.swipe(
        device.displayWidth / 2,
        device.displayHeight * 3 / 4,
        device.displayWidth / 2,
        device.displayHeight / 4,
        8,
      )
      device.waitForIdle()
    }

    // 点第一条主题 → 详情(楼层流 + BBCode 渲染),再甩一轮
    if (!device.hasObject(By.textContains("楼]"))) {
      val rows = device.findObjects(By.clickable(true))
      rows.getOrNull(rows.size / 2)?.click()
    }
    device.wait(Until.hasObject(By.textContains("楼]")), 10_000)
    device.waitForIdle()
    device.swipe(
      device.displayWidth / 2,
      device.displayHeight * 3 / 4,
      device.displayWidth / 2,
      device.displayHeight / 4,
      8,
    )
    device.waitForIdle()

    // 不按返回:若前面的点击没命中,返回会把 app 退到后台,MIUI 随即拦掉
    // `SAVE_PROFILE` 广播(「The save profile broadcast was not received」)。
    // flush 时 app 必须在前台。

    // 兜底:无论上面走到哪一步,总驻留时间必须越过 ART 首次落盘门槛
    val remaining = MIN_DWELL_MS - (System.currentTimeMillis() - started)
    if (remaining > 0) Thread.sleep(remaining)
  }

  private companion object {
    /** 高于 `dalvik.vm.ps-min-first-save-ms`(实测 8000)并留余量。 */
    const val MIN_DWELL_MS = 12_000L
  }
}

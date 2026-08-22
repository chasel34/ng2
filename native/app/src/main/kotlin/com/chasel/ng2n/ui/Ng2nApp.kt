package com.chasel.ng2n.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.chasel.ng2n.ui.bbcode.BBCodeDemoKey
import com.chasel.ng2n.ui.bbcode.BBCodeDemoScreen
import com.chasel.ng2n.ui.common.SnackbarHost
import com.chasel.ng2n.ui.dev.DevMenuKey
import com.chasel.ng2n.ui.dev.DevMenuScreen
import com.chasel.ng2n.ui.home.homeEntries
import com.chasel.ng2n.ui.image.ImageViewerKey
import com.chasel.ng2n.ui.image.ImageViewerScreen
import com.chasel.ng2n.ui.nav.Home
import com.chasel.ng2n.ui.nav.Navigator

/**
 * 全 app 的导航宿主。
 *
 * 预测性返回不需要在这里写代码:manifest 开了 `enableOnBackInvokedCallback`,
 * NavDisplay 自带 predictive back 动画(ADR-0004 的有意偏离,RN 版是关的)。
 */
@Composable
fun Ng2nApp() {
  val backStack = rememberNavBackStack(Home)
  val nav = remember(backStack) {
    object : Navigator {
      override fun push(key: NavKey) {
        backStack.add(key)
      }

      override fun pop() {
        backStack.removeLastOrNull()
      }
    }
  }

  Box(Modifier.fillMaxSize()) {
    NavDisplay(
      backStack = backStack,
      onBack = { backStack.removeLastOrNull() },
      entryProvider = entryProvider {
        // 票 16:首页 / 版块面 / 抽屉,外加还没落地那些键的占位条目
        homeEntries(nav = nav, onOpenDevMenu = { backStack.add(DevMenuKey) })

        /*
         * 开发者入口(抽屉「关于」长按)。票 11 / 12 的手验屏收在这儿 ——
         * demo 屏本身**不删**(票 18 的功能验收要用),只是不再挂在首页上。
         * TODO(票 17):决定这个菜单是挪进「实验室」还是删掉。
         */
        entry<DevMenuKey> {
          DevMenuScreen(
            onBack = { backStack.removeLastOrNull() },
            entries = listOf(
              "BBCode 渲染 demo(票 11)" to { backStack.add(BBCodeDemoKey) },
              "图片查看器 demo(票 12)" to {
                backStack.add(ImageViewerKey(urls = DEMO_IMAGES, index = 0))
              },
            ),
          )
        }
        entry<BBCodeDemoKey> {
          BBCodeDemoScreen(onOpenViewer = { backStack.add(it) })
        }

        // 查看器是「盖在当前页上的全屏浮层」:RN 侧 transparentModal + fade
        // (`motion.ts` 的 screenTransition.overlay),这里对应成 fade 进 fade 出,
        // 时长取 duration.panel = 220ms(同一份设计稿 token)。
        entry<ImageViewerKey>(
          metadata = NavDisplay.transitionSpec {
            fadeIn(tween(VIEWER_FADE_MS)) togetherWith fadeOut(tween(VIEWER_FADE_MS))
          } + NavDisplay.popTransitionSpec {
            fadeIn(tween(VIEWER_FADE_MS)) togetherWith fadeOut(tween(VIEWER_FADE_MS))
          } + NavDisplay.predictivePopTransitionSpec {
            fadeIn(tween(VIEWER_FADE_MS)) togetherWith fadeOut(tween(VIEWER_FADE_MS))
          },
        ) { key ->
          ImageViewerScreen(key = key, onBack = { backStack.removeLastOrNull() })
        }
      },
    )

    // 提示要盖在所有页面上,并且在发起它的页面退场后还能活着把「撤销」等到
    SnackbarHost()
  }
}

private const val VIEWER_FADE_MS = 220

/**
 * 图片管线手验用的三张公网图(票 12)。挑的是尺寸确定、覆盖三种形态的:
 * 横图 3:2、竖图 2:3、以及会触发比例封顶的 1:4 长图。
 */
private val DEMO_IMAGES = listOf(
  "https://picsum.photos/seed/ng2n-a/1200/800",
  "https://picsum.photos/seed/ng2n-b/800/1200",
  "https://picsum.photos/seed/ng2n-c/600/2400",
)

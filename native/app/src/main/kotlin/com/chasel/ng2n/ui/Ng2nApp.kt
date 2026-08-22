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
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.chasel.ng2n.ui.bbcode.BBCodeDemoKey
import com.chasel.ng2n.ui.bbcode.BBCodeDemoScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.chasel.ng2n.ui.accounts.AccountsScreen
import com.chasel.ng2n.ui.accounts.AccountsViewModel
import com.chasel.ng2n.ui.login.LoginScreen
import com.chasel.ng2n.ui.common.SnackbarHost
import com.chasel.ng2n.ui.dev.DevMenuEntry
import com.chasel.ng2n.ui.dev.DevMenuKey
import com.chasel.ng2n.ui.dev.DevMenuScreen
import com.chasel.ng2n.ui.home.homeEntries
import com.chasel.ng2n.ui.image.ImageViewerKey
import com.chasel.ng2n.ui.image.ImageViewerScreen
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.ui.topic.topicEntries
import kotlinx.serialization.Serializable

/** 导航键。Nav3 的 back stack 就是一串 [NavKey],屏幕由 entryProvider 按键类型分派。 */
@Serializable
data object Home : NavKey

/** WebView 登录屏(票 15)。 */
@Serializable
data object Login : NavKey

/** 多账号管理屏(票 15)。 */
@Serializable
data object Accounts : NavKey

/**
 * 全 app 的导航宿主。
 *
 * 其余 20 个键在 `ui/nav/Keys.kt`(票 16 一次定齐);这三个留在本文件,
 * 是因为票 01 / 票 15 就在这儿声明的 —— 挪走只会让并行期合并多三处冲突。
 *
 * 预测性返回不需要在这里写代码:manifest 开了 `enableOnBackInvokedCallback`,
 * NavDisplay 自带 predictive back 动画(ADR-0004 的有意偏离,RN 版是关的)。
 */
@Composable
fun Ng2nApp() {
  val backStack = rememberNavBackStack(Home)
  // 账号状态是全 app 一份(RN 版是全局 zustand store):在 NavDisplay **外面**取一次,
  // 各屏共用同一个实例 —— 每屏各 hiltViewModel() 的话,切号之后另一屏的账号头不会跟着变。
  val accounts: AccountsViewModel = hiltViewModel()
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
      // 票 13:NavDisplay 默认只装 SaveableStateHolder 那一个装饰器,ViewModel 的作用域
      // 要自己加 —— 不加的话条目里的 ViewModel 挂在 Activity 上,pop 之后不 clear,
      // 主题详情的页级渲染成品(几百 KB / 页)会一直留着。
      entryDecorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator(),
        rememberViewModelStoreNavEntryDecorator(),
      ),
      entryProvider = entryProvider {
        // 票 13:主题详情 / 回复链 / 用户资料占位
        topicEntries(nav)
        // 票 16:首页 / 版块面 / 抽屉宿主,外加还没落地那些键的占位条目
        // (Login / Accounts 的占位条目也在里面,票 15 合并时换成真屏 —— 见票 16 Comments)
        homeEntries(nav = nav, accounts = accounts, onOpenDevMenu = { backStack.add(DevMenuKey) })
        entry<Login> {
          LoginScreen(onBack = { backStack.removeLastOrNull() })
        }
        entry<Accounts> {
          AccountsScreen(
            viewModel = accounts,
            onBack = { backStack.removeLastOrNull() },
            onAddAccount = { backStack.add(Login) },
          )
        }

        /*
         * 开发者入口(抽屉「关于」长按)。票 11 / 12 的手验屏收在这儿 ——
         * demo 屏本身**不删**(票 18 的功能验收要用),只是不再挂在首页上。
         * TODO(票 17):决定这个菜单是挪进「实验室」还是删掉。
         */
        entry<DevMenuKey> {
          DevMenuScreen(
            onBack = { backStack.removeLastOrNull() },
            entries = listOf(
              DevMenuEntry(
                label = "BBCode 渲染 demo(票 11)",
                tag = com.chasel.ng2n.ui.bbcode.BBCODE_DEMO_BUTTON_TAG,
                onClick = { backStack.add(BBCodeDemoKey) },
              ),
              DevMenuEntry(
                label = "图片查看器 demo(票 12)",
                tag = IMAGE_DEMO_BUTTON_TAG,
                onClick = { backStack.add(ImageViewerKey(urls = DEMO_IMAGES, index = 0)) },
              ),
              DevMenuEntry(
                label = "主题详情 tid=47406116(票 13)",
                tag = TOPIC_DEMO_BUTTON_TAG,
                onClick = { backStack.add(TopicKey(tid = 47406116L)) },
              ),
              DevMenuEntry(
                label = "登录(票 15)",
                tag = LOGIN_ENTRY_TAG,
                onClick = { backStack.add(Login) },
              ),
              DevMenuEntry(
                label = "账号管理(票 15)",
                tag = ACCOUNTS_ENTRY_TAG,
                onClick = { backStack.add(Accounts) },
              ),
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

/**
 * macrobenchmark 等首帧内容的锚点(票 19 用;uiautomator 认 contentDescription)。
 * **票 01 立的锚,语义不变**:首页首帧可用时打这个 tag —— 现在打在真首页的版块宫格上
 * (`ui/home/HomeScreen.kt`)。
 */
const val SKELETON_READY_TAG: String = "ng2n-skeleton-ready"

/**
 * 票 11 / 12 / 15 的手验入口锚点。入口从首页搬进了开发者菜单
 * (抽屉「关于」长按 → [DevMenuKey]),**锚点名一个字没改** —— 那几张票的
 * uiautomator 脚本按 content-desc 找它们。
 */
const val IMAGE_DEMO_BUTTON_TAG: String = "ng2n-image-demo"

/** 票 15 手验入口锚点(现挂在开发者菜单里)。 */
const val LOGIN_ENTRY_TAG: String = "ng2n-login-entry"
const val ACCOUNTS_ENTRY_TAG: String = "ng2n-accounts-entry"
const val TOPIC_DEMO_BUTTON_TAG: String = "ng2n-topic-demo"

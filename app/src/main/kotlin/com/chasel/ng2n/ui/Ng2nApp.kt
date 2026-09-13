package com.chasel.ng2n.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.chasel.ng2n.ui.accounts.AccountsScreen
import com.chasel.ng2n.ui.accounts.AccountsViewModel
import com.chasel.ng2n.ui.bbcode.BBCodeDemoKey
import com.chasel.ng2n.ui.bbcode.BBCodeDemoScreen
import com.chasel.ng2n.ui.common.SnackbarHost
import com.chasel.ng2n.ui.common.pageTransition
import com.chasel.ng2n.ui.dev.DevMenuEntry
import com.chasel.ng2n.ui.dev.DevMenuKey
import com.chasel.ng2n.ui.dev.DevMenuScreen
import com.chasel.ng2n.ui.filters.filtersAndUserEntries
import com.chasel.ng2n.ui.home.homeEntries
import com.chasel.ng2n.ui.image.ImageViewerKey
import com.chasel.ng2n.ui.image.ImageViewerScreen
import com.chasel.ng2n.ui.lists.listEntries
import com.chasel.ng2n.ui.login.LoginScreen
import com.chasel.ng2n.ui.nav.DeepLinkInbox
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.ui.settings.settingsEntries
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.topic.topicEntries
import kotlinx.serialization.Serializable

@Serializable
data object Home : NavKey

@Serializable
data object Login : NavKey

@Serializable
data object Accounts : NavKey

@Composable
fun Ng2nApp() {
  val backStack = rememberNavBackStack(Home)
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

  val pendingLink by DeepLinkInbox.pending.collectAsStateWithLifecycle()
  LaunchedEffect(pendingLink) {
    val key = pendingLink ?: return@LaunchedEffect
    DeepLinkInbox.consume()
    if (backStack.lastOrNull() != key) backStack.add(key)
  }

  Box(Modifier.fillMaxSize().background(LocalNg2nColors.current.bg)) {
    NavDisplay(
      backStack = backStack,
      onBack = { backStack.removeLastOrNull() },
      transitionSpec = {
        pageTransition()
      },
      popTransitionSpec = {
        pageTransition(back = true)
      },
      entryDecorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator(),
        rememberViewModelStoreNavEntryDecorator(),
      ),
      entryProvider = entryProvider {
        topicEntries(nav)
        homeEntries(nav = nav, accounts = accounts, onOpenDevMenu = { backStack.add(DevMenuKey) })
        filtersAndUserEntries(nav = nav)
        settingsEntries(nav = nav, onOpenAccounts = { backStack.add(Accounts) })
        listEntries(nav = nav)
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

        entry<ImageViewerKey>(
          metadata = NavDisplay.transitionSpec {
            fadeIn(tween(VIEWER_FADE_MS)) togetherWith fadeOut(tween(VIEWER_FADE_MS))
          } + NavDisplay.popTransitionSpec {
            fadeIn(tween(VIEWER_FADE_MS)) togetherWith fadeOut(tween(VIEWER_FADE_MS))
          },
        ) { key ->
          ImageViewerScreen(key = key, onBack = { backStack.removeLastOrNull() })
        }
      },
    )

    SnackbarHost()
  }
}

private const val VIEWER_FADE_MS = 220

private val DEMO_IMAGES = listOf(
  "https://picsum.photos/seed/ng2n-a/1200/800",
  "https://picsum.photos/seed/ng2n-b/800/1200",
  "https://picsum.photos/seed/ng2n-c/600/2400",
)

const val SKELETON_READY_TAG: String = "ng2n-skeleton-ready"

const val IMAGE_DEMO_BUTTON_TAG: String = "ng2n-image-demo"

const val LOGIN_ENTRY_TAG: String = "ng2n-login-entry"
const val ACCOUNTS_ENTRY_TAG: String = "ng2n-accounts-entry"
const val TOPIC_DEMO_BUTTON_TAG: String = "ng2n-topic-demo"

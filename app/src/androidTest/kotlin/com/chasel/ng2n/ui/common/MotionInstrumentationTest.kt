package com.chasel.ng2n.ui.common

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.test.platform.app.InstrumentationRegistry
import com.chasel.ng2n.ui.bbcode.ChevronIcon
import com.chasel.ng2n.ui.drawer.AppDrawerContent
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.settings.SettingsOption
import com.chasel.ng2n.ui.settings.SettingsOptionDialog
import com.chasel.ng2n.ui.theme.Ng2nTheme
import com.chasel.ng2n.ui.topic.BookmarkDialog
import com.chasel.ng2n.ui.topic.BookmarkDialogState
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MotionInstrumentationTest {
  @get:Rule val compose = createComposeRule()

  @After fun resetSnackbar() { compose.runOnIdle { Snackbars.hide() } }

  @Test fun pagePushAndPopMoveInOppositeDirections() {
    val stack = mutableStateListOf<NavKey>(MotionPage(0))
    compose.setContent {
      Ng2nTheme {
        NavDisplay(
          modifier = Modifier.background(Color(0xFFFCF4E1)),
          backStack = stack,
          onBack = { stack.removeLastOrNull() },
          transitionSpec = { pageTransition() },
          popTransitionSpec = { pageTransition(back = true) },
          entryProvider = entryProvider {
            entry<MotionPage> { page ->
              Box(Modifier.fillMaxSize().background(if (page.id == 0) Color(0xFFFCF4E1) else Color(0xFFE4EFEA))) {
                Text("页面 ${page.id}", Modifier.padding(48.dp).testTag("page-${page.id}"))
              }
            }
          },
        )
      }
    }
    val origin = compose.onNodeWithTag("page-0").fetchSemanticsNode().boundsInRoot.left
    compose.mainClock.autoAdvance = false
    compose.runOnIdle { stack.add(MotionPage(1)) }
    compose.mainClock.advanceTimeBy(80)
    val entering = compose.onNodeWithTag("page-1").fetchSemanticsNode().boundsInRoot.left
    assertTrue("Forward page must enter from the right", entering > origin)
    capture("page-forward-middle")
    compose.mainClock.advanceTimeBy(400)
    compose.onNodeWithTag("page-1").assertIsDisplayed()
    compose.runOnIdle { stack.removeLastOrNull() }
    compose.mainClock.advanceTimeBy(80)
    val returning = compose.onNodeWithTag("page-0").fetchSemanticsNode().boundsInRoot.left
    assertTrue("Previous page must enter from the left", returning < origin)
    capture("page-back-middle")
    compose.mainClock.advanceTimeBy(400)
    compose.onNodeWithTag("page-1").assertDoesNotExist()
    compose.onNodeWithTag("page-0").assertIsDisplayed()
  }

  @Test fun opaquePagesNeverRevealTheBrightNavigationBackground() {
    val stack = mutableStateListOf<NavKey>(MotionPage(0))
    val pageColor = Color(0xFF14796B)
    compose.setContent {
      Ng2nTheme {
        NavDisplay(
          modifier = Modifier.background(Color.White),
          backStack = stack,
          onBack = { stack.removeLastOrNull() },
          transitionSpec = { pageTransition() },
          popTransitionSpec = { pageTransition(back = true) },
          entryProvider = entryProvider {
            entry<MotionPage> { Box(Modifier.fillMaxSize().background(pageColor)) }
          },
        )
      }
    }
    compose.mainClock.autoAdvance = false
    repeat(2) { direction ->
      compose.runOnIdle {
        if (direction == 0) stack.add(MotionPage(1)) else stack.removeLastOrNull()
      }
      repeat(20) {
        compose.mainClock.advanceTimeByFrame()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        for (x in listOf(bitmap.width / 10, bitmap.width / 2, bitmap.width * 9 / 10)) {
          assertEquals("The opaque page must cover the navigation background throughout the transition",
            pageColor.toArgb(), bitmap.getPixel(x, bitmap.height / 3))
        }
      }
    }
  }

  @Test fun dialogRemainsUntilExitCompletesAndCanReverse() {
    val open = mutableStateOf(false)
    var mounted = false
    compose.setContent {
      Ng2nTheme {
        DialogShell(open.value, { open.value = false }) {
          DisposableEffect(Unit) {
            mounted = true
            onDispose { mounted = false }
          }
          Text("对话框内容", Modifier.padding(32.dp))
        }
      }
    }
    compose.mainClock.autoAdvance = false
    compose.runOnIdle { open.value = true }
    compose.mainClock.advanceTimeBy(320)
    compose.onNodeWithText("对话框内容").assertIsDisplayed()
    compose.runOnIdle { open.value = false }
    compose.mainClock.advanceTimeBy(48)
    assertTrue("Content must survive the exit animation", mounted)
    capture("dialog-exit-middle")
    compose.runOnIdle { open.value = true }
    compose.mainClock.advanceTimeBy(320)
    compose.onNodeWithText("对话框内容").assertIsDisplayed()
    compose.runOnIdle { open.value = false }
    compose.mainClock.advanceTimeBy(320)
    assertFalse("Content must be disposed after exit", mounted)
  }

  @Test fun closeDuringEntryStillAnimatesOut() {
    val open = mutableStateOf(false)
    var mounted = false
    compose.setContent {
      Ng2nTheme {
        DialogShell(open.value, {}) {
          DisposableEffect(Unit) {
            mounted = true
            onDispose { mounted = false }
          }
          Text("快速关闭")
        }
      }
    }
    compose.mainClock.autoAdvance = false
    compose.runOnIdle { open.value = true }
    compose.mainClock.advanceTimeBy(80)
    compose.runOnIdle { open.value = false }
    compose.mainClock.advanceTimeBy(16)
    assertTrue("An interrupted entry must not remove visible content immediately", mounted)
    compose.mainClock.advanceTimeBy(400)
    assertFalse(mounted)
  }

  @Test fun exitingMenuCannotActivateAnItem() {
    val open = mutableStateOf(true)
    var clicks = 0
    compose.setContent {
      Ng2nTheme {
        OverflowMenu(open.value, { open.value = false }, listOf(MenuItem("item", "菜单动作", onClick = { clicks++ })))
      }
    }
    compose.onNodeWithText("菜单动作").assertIsDisplayed()
    val position = compose.onNodeWithText("菜单动作").fetchSemanticsNode().boundsInRoot.center
    compose.mainClock.autoAdvance = false
    compose.runOnIdle { open.value = false }
    compose.mainClock.advanceTimeBy(48)
    compose.onNodeWithText("菜单动作").assertDoesNotExist()
    compose.onRoot().performTouchInput { click(position) }
    capture("menu-exit-middle")
    compose.mainClock.advanceTimeBy(300)
    assertTrue(clicks == 0)
  }

  @Test fun snackbarReplacementKeepsLatestActionAndDismisses() {
    var oldClicks = 0
    var newClicks = 0
    compose.setContent { Ng2nTheme { SnackbarHost() } }
    compose.runOnIdle { Snackbars.show("旧提示", SnackbarAction("旧动作") { oldClicks++ }) }
    compose.onNodeWithText("旧提示").assertIsDisplayed()
    compose.runOnIdle { Snackbars.show("新提示", SnackbarAction("新动作") { newClicks++ }) }
    compose.onNodeWithText("新提示").assertIsDisplayed()
    capture("snackbar-replaced")
    compose.onNodeWithText("新动作").performClick()
    compose.onNodeWithText("新提示").assertDoesNotExist()
    assertTrue(oldClicks == 0 && newClicks == 1)
  }

  @Test fun settingsDialogReopensWithCurrentSelection() {
    val open = mutableStateOf(true)
    val value = mutableStateOf(1)
    compose.setContent {
      Ng2nTheme {
        SettingsOptionDialog(
          open.value, "选项", listOf(SettingsOption(1, "一"), SettingsOption(2, "二")), value.value,
          onCancel = { open.value = false }, onConfirm = { value.value = it; open.value = false },
        )
      }
    }
    compose.onNodeWithText("二").performClick()
    compose.onNodeWithText("取消").performClick()
    compose.onNodeWithText("选项").assertDoesNotExist()
    compose.runOnIdle { open.value = true }
    compose.onNodeWithText("应用").performClick()
    assertTrue("Cancelled selection must not leak into the next opening", value.value == 1)
  }

  @Test fun inputErrorCanResizeAndRecover() {
    val error = mutableStateOf<String?>(null)
    compose.setContent {
      Ng2nTheme {
        InputDialog(true, "打开链接", "打开", {}, {}, error = error.value, initialValue = "invalid")
      }
    }
    compose.runOnIdle { error.value = "请输入有效的帖子链接" }
    compose.onNodeWithText("请输入有效的帖子链接").assertIsDisplayed()
    capture("input-error")
    compose.runOnIdle { error.value = null }
    compose.onNodeWithText("请输入有效的帖子链接").assertDoesNotExist()
  }

  @Test fun accordionAndIconSettleAfterRapidChanges() {
    val open = mutableStateOf(false)
    compose.setContent {
      Ng2nTheme {
        Column {
          ChevronIcon(Color.Black, open.value)
          MotionIcon(if (open.value) Ng2nIcon.CHECK_BOX else Ng2nIcon.CHECK_BOX_OUTLINE_BLANK, Color.Black)
          AnimatedVisibility(open.value, enter = expandVertically(), exit = shrinkVertically()) {
            Text("折叠内容", Modifier.height(180.dp))
          }
        }
      }
    }
    compose.mainClock.autoAdvance = false
    repeat(5) {
      compose.runOnIdle { open.value = !open.value }
      compose.mainClock.advanceTimeBy(48)
    }
    compose.mainClock.advanceTimeBy(1200)
    compose.onNodeWithText("折叠内容").assertIsDisplayed()
    capture("accordion-open")
    compose.runOnIdle { open.value = false }
    compose.mainClock.advanceTimeBy(1200)
    compose.onNodeWithText("折叠内容").assertDoesNotExist()
  }

  @Test fun unreadBadgeKeepsLastCountUntilExitCompletes() {
    val unread = mutableStateOf(0)
    compose.setContent { Ng2nTheme { AppDrawerContent(onEntry = {}, unread = unread.value) } }
    compose.onNodeWithText("最近被喷").performScrollTo()
    compose.mainClock.autoAdvance = false
    compose.runOnIdle { unread.value = 12 }
    compose.mainClock.advanceTimeBy(400)
    compose.onNodeWithText("12").assertIsDisplayed()
    capture("unread-badge")
    compose.runOnIdle { unread.value = 0 }
    compose.mainClock.advanceTimeBy(48)
    compose.onNodeWithText("12").assertExists()
    compose.onNodeWithText("1").assertDoesNotExist()
    compose.mainClock.advanceTimeBy(400)
    compose.onNodeWithText("12").assertDoesNotExist()
  }

  @Test fun nullableBookmarkDialogRetainsContentWhileClosing() {
    val state = mutableStateOf<BookmarkDialogState?>(
      BookmarkDialogState(false, 1, 1, "测试作者", "测试摘要", "原备注"),
    )
    compose.setContent {
      Ng2nTheme {
        BookmarkDialog(state.value, { state.value = null }, {})
      }
    }
    compose.onNodeWithText("加书签").assertIsDisplayed()
    compose.mainClock.autoAdvance = false
    compose.runOnIdle { state.value = null }
    compose.mainClock.advanceTimeBy(48)
    capture("bookmark-exit-middle")
    compose.mainClock.advanceTimeBy(400)
    compose.onNodeWithText("加书签").assertDoesNotExist()
    compose.runOnIdle { state.value = BookmarkDialogState(true, 2, 2, "测试作者", "第二段摘要", "新备注") }
    compose.mainClock.advanceTimeBy(400)
    compose.onNodeWithText("编辑书签").assertIsDisplayed()
    compose.onNodeWithText("新备注").assertIsDisplayed()
  }

  private fun capture(name: String) {
    val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
    val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "motion-verification")
    dir.mkdirs()
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}

private data class MotionPage(val id: Int) : NavKey

package com.chasel.ng2n.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable

/** 导航键。Nav3 的 back stack 就是一串 [NavKey],屏幕由 entryProvider 按键类型分派。 */
@Serializable
data object Home : NavKey

/**
 * 空首屏:只为证明 Hilt + Compose + Nav3 + Material3 这条管线通到底。真正的首页在票 16。
 *
 * 预测性返回不需要在这里写代码:manifest 开了 `enableOnBackInvokedCallback`,
 * NavDisplay 自带 predictive back 动画(ADR-0004 的有意偏离,RN 版是关的)。
 */
@Composable
fun Ng2nApp() {
  val backStack = rememberNavBackStack(Home)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider = entryProvider {
      entry<Home> { HomeScreen() }
    },
  )
}

@Composable
private fun HomeScreen() {
  Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        // macrobenchmark 等首帧内容的锚点(票 19 会用):uiautomator 认 contentDescription。
        .semantics { contentDescription = SKELETON_READY_TAG },
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(text = "NG2N", style = MaterialTheme.typography.headlineMedium)
      Text(text = "骨架就位(票 01)", style = MaterialTheme.typography.bodyMedium)
    }
  }
}

const val SKELETON_READY_TAG: String = "ng2n-skeleton-ready"

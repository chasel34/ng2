package com.chasel.ng2n.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.chasel.ng2n.ui.bbcode.BBCODE_DEMO_BUTTON_TAG
import com.chasel.ng2n.ui.bbcode.BBCodeDemoKey
import com.chasel.ng2n.ui.bbcode.BBCodeDemoScreen
import com.chasel.ng2n.ui.image.ImageViewerKey
import com.chasel.ng2n.ui.image.ImageViewerScreen
import com.chasel.ng2n.ui.image.PostImage
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.topic.TopicDevOpenSection
import com.chasel.ng2n.ui.topic.TopicKey
import com.chasel.ng2n.ui.topic.topicEntries
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
  // 票 13:各屏只认 push/pop 这两件事,back stack 不往下传(见 ui/nav/Navigator.kt)
  val nav = remember(backStack) {
    object : Navigator {
      override fun push(key: NavKey) { backStack.add(key) }
      override fun pop() { backStack.removeLastOrNull() }
    }
  }

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
      entry<Home> {
        HomeScreen(
          onOpenViewer = { backStack.add(it) },
          onOpenBBCodeDemo = { backStack.add(BBCodeDemoKey) },
          onOpenTopic = { tid -> backStack.add(TopicKey(tid = tid)) },
        )
      }
      // TODO(票 16 移除):BBCode 渲染器的模拟器手验屏(票 11)
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
}

private const val VIEWER_FADE_MS = 220

@Composable
private fun HomeScreen(
  onOpenViewer: (ImageViewerKey) -> Unit,
  onOpenBBCodeDemo: () -> Unit,
  onOpenTopic: (Long) -> Unit,
) {
  Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp)
        // macrobenchmark 等首帧内容的锚点(票 19 会用):uiautomator 认 contentDescription。
        .semantics { contentDescription = SKELETON_READY_TAG },
      // 票 01 原本是 Arrangement.Center(那时屏上只有两行字)。加了 demo 之后内容比
      // 视口高,Center 会把溢出的一半顶到滚动范围之外——上半截够不着。改 Top。
      verticalArrangement = Arrangement.Top,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(Modifier.height(24.dp))
      Text(text = "NG2N", style = MaterialTheme.typography.headlineMedium)
      Text(text = "骨架就位(票 01)", style = MaterialTheme.typography.bodyMedium)

      Spacer(Modifier.height(24.dp))
      // TODO(票 16 移除):BBCode 渲染 demo(票 11)
      Button(
        onClick = onOpenBBCodeDemo,
        modifier = Modifier.semantics { contentDescription = BBCODE_DEMO_BUTTON_TAG },
      ) {
        Text("BBCode 渲染 demo")
      }

      Spacer(Modifier.height(24.dp))
      // TODO(票 16 移除):主题详情屏的手验入口(票 13)
      TopicDevOpenSection(onOpen = onOpenTopic)

      Spacer(Modifier.height(24.dp))
      ImageDemoSection(onOpenViewer = onOpenViewer)
    }
  }
}

/**
 * **TODO(票 16 移除)**:图片管线的模拟器手验入口(票 12)。
 *
 * 三张公网图写死在这儿,换成别的地址直接改 [DEMO_IMAGES] 即可 —— 挑的是尺寸确定、
 * 覆盖三种形态的:横图 3:2、竖图 2:3、以及会触发比例封顶的 1:4 长图。
 * 票 16 铺真首页时连同 [ImageDemoSection] 一起删掉。
 */
private val DEMO_IMAGES = listOf(
  "https://picsum.photos/seed/ng2n-a/1200/800",
  "https://picsum.photos/seed/ng2n-b/800/1200",
  "https://picsum.photos/seed/ng2n-c/600/2400",
)

@Composable
private fun ImageDemoSection(onOpenViewer: (ImageViewerKey) -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(text = "图片查看器 demo(票 12,票 16 移除)", style = MaterialTheme.typography.labelLarge)
    Button(
      onClick = { onOpenViewer(ImageViewerKey(urls = DEMO_IMAGES, index = 0)) },
      modifier = Modifier.semantics { contentDescription = IMAGE_DEMO_BUTTON_TAG },
    ) {
      Text("打开图片查看器")
    }
    DEMO_IMAGES.forEachIndexed { index, url ->
      PostImage(
        url = url,
        onClick = { onOpenViewer(ImageViewerKey(urls = DEMO_IMAGES, index = index)) },
      )
    }
    Spacer(Modifier.height(24.dp))
  }
}

const val SKELETON_READY_TAG: String = "ng2n-skeleton-ready"

/** 票 12 手验用的锚点(uiautomator 按 content-desc 找它)。票 16 随 demo 一起删。 */
const val IMAGE_DEMO_BUTTON_TAG: String = "ng2n-image-demo"

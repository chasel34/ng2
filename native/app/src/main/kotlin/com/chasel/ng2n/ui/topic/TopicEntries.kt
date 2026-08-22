package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.ui.nav.ChainKey
import com.chasel.ng2n.ui.nav.UserKey
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.compose.material3.Text
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Typo

/**
 * 主题相关屏幕的 Nav3 条目。`Ng2nApp` 里只需要一行 `topicEntries(nav)`。
 *
 * 三个条目:主题详情 / 回复链 / 用户资料占位(资料屏本体归票 17)。
 */
fun EntryProviderScope<NavKey>.topicEntries(nav: Navigator) {
  entry<TopicKey> { key -> TopicScreen(key = key, nav = nav) }
  entry<ChainKey> { key -> ChainScreen(key = key, nav = nav) }
  // TODO(票 17):换成真的用户资料屏
  entry<UserKey> { key -> UserPlaceholderScreen(key = key, nav = nav) }
}

/**
 * 详情屏的 ViewModel。
 *
 * `viewModel { … }` 的宿主是 Nav3 条目的 `ViewModelStoreOwner`
 * (`Ng2nApp` 里装的 `rememberViewModelStoreNavEntryDecorator()`)——
 * 所以**这一屏被 pop 掉时 ViewModel 才 clear**,页级渲染成品随之释放;
 * 而往前 push 一个回复链屏、再返回时,楼层与滚动位置原样还在
 * (RN 侧靠 native stack 把屏留在栈里,同一个效果)。
 *
 * key 里带 tid:同一个 back stack 上开两个不同主题各有各的 ViewModel。
 */
@Composable
fun rememberTopicViewModel(key: TopicKey): TopicViewModel {
  val deps = rememberTopicDeps()
  return viewModel(key = "topic:${key.tid}:${key.pid}:${key.fav}") { TopicViewModel(key, deps) }
}

@Composable
fun rememberChainViewModel(key: ChainKey): ChainViewModel {
  val deps = rememberTopicDeps()
  return viewModel(key = "chain:${key.tid}:${key.pid}") { ChainViewModel(key, deps) }
}

/** **TODO(票 17)**:用户资料屏的占位。点头像有地方去,别在这一票里留死入口。 */
@Composable
private fun UserPlaceholderScreen(key: UserKey, nav: Navigator) {
  val colors = LocalNg2nColors.current
  Column(Modifier.fillMaxSize()) {
    TopicTopBar {
      TopBarButton(onClick = nav::pop, label = "返回", box = 46.dp) {
        BackArrowIcon(tint = colors.onTopbar)
      }
      TopBarTitle(text = key.name ?: "用户 ${key.uid}", modifier = Modifier.weight(1f))
    }
    Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text("用户资料屏归票 17", fontSize = Typo.section.size, color = colors.fg)
      Text("uid = ${key.uid}", fontSize = Typo.listMeta.size, color = colors.meta)
    }
  }
}

package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.ui.nav.ChainKey
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

fun EntryProviderScope<NavKey>.topicEntries(nav: Navigator) {
  entry<TopicKey> { key -> TopicScreen(key = key, nav = nav) }
  entry<ChainKey> { key -> ChainScreen(key = key, nav = nav) }
}

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

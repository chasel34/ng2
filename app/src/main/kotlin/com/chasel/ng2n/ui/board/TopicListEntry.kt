package com.chasel.ng2n.ui.board

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.chasel.ng2n.data.board.TopicListRepository

/** 新入栈拉最新第一页；详情返回时恢复原有分页，缓存被淘汰才补拉。 */
@Composable
internal fun LoadTopicListOnEntry(repository: TopicListRepository, key: TopicListRepository.Key) {
  var loaded by rememberSaveable(key) { mutableStateOf(false) }
  LaunchedEffect(repository, key) {
    repository.loadOnEntry(key, restored = loaded)
    loaded = true
  }
}

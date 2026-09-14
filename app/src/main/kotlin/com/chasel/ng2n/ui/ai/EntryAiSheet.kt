package com.chasel.ng2n.ui.ai

import androidx.compose.runtime.Composable
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.ui.settings.AiSettingsKey

@Composable
fun EntryAiSheet(state: TopicAiState, vm: TopicAiViewModel, nav: Navigator) {
  TopicAiSheet(state, vm, onSettings = { nav.push(AiSettingsKey) }, onHistory = { nav.push(AiHistoryKey()) },
    onAccounts = { nav.push(com.chasel.ng2n.ui.Accounts) },
    onSource = { nav.push(TopicKey(it.tid, pid = it.pid.takeIf { pid -> pid > 0 }, floor = it.floor, highlightSource = true)) })
}

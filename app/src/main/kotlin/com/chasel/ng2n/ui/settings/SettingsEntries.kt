package com.chasel.ng2n.ui.settings

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.chasel.ng2n.ui.about.AboutScreen
import com.chasel.ng2n.ui.nav.AboutKey
import com.chasel.ng2n.ui.nav.FiltersKey
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.nav.SettingsKey
import com.chasel.ng2n.ui.nav.WebKey
import com.chasel.ng2n.ui.web.WebFallbackScreen
import kotlinx.serialization.Serializable

@Serializable
data object FontSizeKey : NavKey

@Serializable
data object AiSettingsKey : NavKey

@Serializable
data object LabKey : NavKey

fun EntryProviderScope<NavKey>.settingsEntries(
  nav: Navigator,
  onOpenAccounts: () -> Unit,
) {
  entry<SettingsKey> {
    SettingsScreen(
      onBack = nav::pop,
      onOpenAccounts = onOpenAccounts,
      onOpenFilters = { nav.push(FiltersKey) },
      onOpenFontSize = { nav.push(FontSizeKey) },
      onOpenLab = { nav.push(LabKey) },
      onOpenAi = { nav.push(AiSettingsKey) },
    )
  }

  entry<AiSettingsKey> {
    AiSettingsScreen(onBack = nav::pop, onHistory = { nav.push(com.chasel.ng2n.ui.ai.AiHistoryKey()) }, viewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel())
  }

  entry<com.chasel.ng2n.ui.ai.AiHistoryKey> { com.chasel.ng2n.ui.ai.AiHistoryScreen(it, nav) }
  entry<com.chasel.ng2n.ui.ai.AiChatKey> { com.chasel.ng2n.ui.ai.AiChatScreen(it, nav) }

  entry<FontSizeKey> { FontSizeScreen(onBack = nav::pop) }

  entry<LabKey> { LabScreen(onBack = nav::pop) }

  entry<AboutKey> {
    AboutScreen(onBack = nav::pop, onOpenLab = { nav.push(LabKey) })
  }

  entry<WebKey> { key ->
    WebFallbackScreen(url = key.url, title = key.title, onBack = nav::pop)
  }
}

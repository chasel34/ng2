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

/**
 * 「字体和头像大小」屏(设置的二级页)。
 *
 * 与 `ui/image/ImageViewerKey`、`ui/bbcode/BBCodeDemoKey` 同一个做法:**只有本模块用得到
 * 的键跟着屏幕住**,不进 `ui/nav/Keys.kt` 那张全局表(那张表是票 16 为「抽屉里十几个
 * 入口指向谁」一次定齐的,设置的两个二级页从来没有外部引用者)。
 */
@Serializable
data object FontSizeKey : NavKey

/** 「实验室与诊断」屏(设置的二级页)。同上,跟着屏幕住。 */
@Serializable
data object LabKey : NavKey

/**
 * 票 17c 的导航条目 —— 设置树三屏 / 关于 / 网页兜底。
 *
 * [onOpenAccounts] 由宿主给:多账号屏的键住在 `ui/Ng2nApp.kt`(票 15),
 * 而且它要共享 NavDisplay 外面那一份 `AccountsViewModel`,不能在这里 push。
 */
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
    )
  }

  entry<FontSizeKey> { FontSizeScreen(onBack = nav::pop) }

  entry<LabKey> { LabScreen(onBack = nav::pop) }

  entry<AboutKey> {
    AboutScreen(onBack = nav::pop, onOpenLab = { nav.push(LabKey) })
  }

  entry<WebKey> { key ->
    WebFallbackScreen(url = key.url, title = key.title, onBack = nav::pop)
  }
}

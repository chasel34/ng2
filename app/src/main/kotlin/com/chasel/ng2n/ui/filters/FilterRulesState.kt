package com.chasel.ng2n.ui.filters

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.core.local.FilterRule
import com.chasel.ng2n.ui.rememberAppDeps

/**
 * 参与判定的完整规则表(本地在前、官方在后)—— RN 侧 `useFilterRules` 的对应物。
 *
 * 主题列表屏(版块 / 24h 热帖 / 精华区 / 搜索结果 / 收藏夹)各调一次,拿到的规则交给
 * `filterTopics`。**本地在前**这个顺序是 `FilterRepository.allRules` 定的,别在屏里重排:
 * 命中时报的应该是用户自己加的那条。
 *
 * 官方那份是账号级云端数据(API 文档 §11.5):
 *
 * - 进屏顺手问一次([FilterRepository.ensureBlockWords] 幂等,5 分钟内不重问,
 *   与 RN 侧 `staleTime: 5 * 60 * 1000` 同一口径),游客态是 no-op;
 * - **没拉回来不阻塞列表**:先只按本地规则渲染,云端表回来后这条流再发一次,
 *   那时命中的行才藏起来。
 */
@Composable
fun rememberFilterRules(): List<FilterRule> {
  val deps = rememberAppDeps()
  val rules by deps.filters.allRules.collectAsStateWithLifecycle(initialValue = emptyList())
  LaunchedEffect(deps) { deps.filters.ensureBlockWords() }
  return rules
}

package com.chasel.ng2n.ui.filters

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.core.local.FilterRule
import com.chasel.ng2n.ui.rememberAppDeps

@Composable
fun rememberFilterRules(): List<FilterRule> {
  val deps = rememberAppDeps()
  val rules by deps.filters.allRules.collectAsStateWithLifecycle(initialValue = emptyList())
  LaunchedEffect(deps) { deps.filters.ensureBlockWords() }
  return rules
}

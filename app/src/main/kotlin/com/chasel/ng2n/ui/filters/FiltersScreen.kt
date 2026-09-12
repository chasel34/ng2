package com.chasel.ng2n.ui.filters

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.core.api.BlockWordList
import com.chasel.ng2n.core.api.BlockedUser
import com.chasel.ng2n.core.api.blockWordError
import com.chasel.ng2n.core.local.FilterRule
import com.chasel.ng2n.core.local.FilterRuleInput
import com.chasel.ng2n.core.local.FilterRuleKind
import com.chasel.ng2n.data.filters.FilterRepository
import com.chasel.ng2n.data.settings.DEFAULT_SETTINGS
import com.chasel.ng2n.ui.board.dateText
import com.chasel.ng2n.ui.common.EmptyState
import com.chasel.ng2n.ui.common.InputDialog
import com.chasel.ng2n.ui.common.LoadFailedNotice
import com.chasel.ng2n.ui.common.LoadingState
import com.chasel.ng2n.ui.common.SnackbarAction
import com.chasel.ng2n.ui.common.Snackbars
import com.chasel.ng2n.ui.common.StateVariant
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarButton
import com.chasel.ng2n.ui.common.TopBarTitle
import com.chasel.ng2n.ui.common.TopBarTitleVariant
import com.chasel.ng2n.ui.common.failureText
import com.chasel.ng2n.ui.common.ListPullToRefreshBox
import com.chasel.ng2n.ui.common.showLoginPrompt
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.rememberAppDeps
import com.chasel.ng2n.ui.theme.Elevation
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.coroutines.launch

private val TAB_HEIGHT = 42.dp

private enum class FilterTab(val label: String, val hint: String) {
  LOCAL(
    "本地规则",
    "仅存在本机,卸载即丢失;命中的主题在列表里隐藏,命中的楼层折叠成一行。",
  ),
  OFFICIAL_USERS("官方用户屏蔽", "与 NGA 账号云端同步,可在这里解除。"),
  OFFICIAL_WORDS(
    "官方关键词",
    "与 NGA 账号云端同步,和网页版「控制面板 → 屏蔽」是同一份数据。",
  ),
}

private fun iconOf(kind: FilterRuleKind): Ng2nIcon = when (kind) {
  FilterRuleKind.USER -> Ng2nIcon.PERSON
  FilterRuleKind.KEYWORD -> Ng2nIcon.TEXT_FIELDS
  FilterRuleKind.CATEGORY -> Ng2nIcon.BOOKMARK
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltersScreen(nav: Navigator, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()

  var tab by remember { mutableStateOf(FilterTab.LOCAL) }
  var addRuleOpen by remember { mutableStateOf(false) }
  var addWordOpen by remember { mutableStateOf(false) }
  var wordError by remember { mutableStateOf<String?>(null) }

  val rules by deps.filters.localRules.collectAsStateWithLifecycle(initialValue = emptyList())
  val cloud by deps.filters.blockWords.collectAsStateWithLifecycle()
  val appSettings by deps.settings.settings.collectAsStateWithLifecycle(
    initialValue = DEFAULT_SETTINGS,
  )

  var uid by remember { mutableStateOf<String?>(null) }
  var uidKnown by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    deps.filters.currentUid.collect { current ->
      uid = current
      uidKnown = true
      deps.filters.ensureBlockWords()
    }
  }
  val signedIn = uid != null

  val cloudFailed: (Throwable) -> Unit = { error ->
    Snackbars.show(if (error is IllegalStateException) error.message ?: FALLBACK else failureText(error))
  }

  fun undoable(message: String, run: suspend () -> Unit) {
    val before = cloud.list
    scope.launch {
      runCatching { run() }.fold(
        onSuccess = {
          Snackbars.show(
            message,
            before?.let { snapshot ->
              SnackbarAction("撤销") {
                scope.launch {
                  runCatching { deps.filters.replaceOfficial(snapshot) }
                    .onFailure(cloudFailed)
                }
              }
            },
          )
        },
        onFailure = cloudFailed,
      )
    }
  }

  Column(modifier.fillMaxSize().background(colors.bg)) {
    TopBar(
      paddingHorizontal = 4.dp,
      below = {
        Row(Modifier.padding(horizontal = 6.dp)) {
          for (item in FilterTab.entries) {
            val on = tab == item
            Box(
              modifier = Modifier
                .height(TAB_HEIGHT)
                .clickable(onClickLabel = item.label) { tab = item }
                .then(
                  if (!on) Modifier else Modifier.drawBehind {
                    val h = 3.dp.toPx()
                    drawRect(
                      color = colors.onTopbar,
                      topLeft = Offset(0f, size.height - h),
                      size = androidx.compose.ui.geometry.Size(size.width, h),
                    )
                  },
                )
                .padding(horizontal = 15.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = item.label,
                style = TextStyle(
                  fontSize = 14.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = if (on) colors.onTopbar else colors.onTopbar.copy(alpha = 0.6f),
                ),
              )
            }
          }
        }
      },
    ) {
      TopBarButton(
        icon = Ng2nIcon.ARROW_BACK,
        size = 24.dp,
        box = 46.dp,
        contentDescription = "返回",
        onClick = nav::pop,
      )
      TopBarTitle(text = "屏蔽规则", variant = TopBarTitleVariant.SUB)
    }

    Box(Modifier.weight(1f)) {
      val pullable = tab != FilterTab.LOCAL && signedIn
      ListPullToRefreshBox(
        isRefreshing = pullable && cloud.refreshing,
        onRefresh = { if (pullable) scope.launch { deps.filters.refreshBlockWords() } },
        modifier = Modifier.fillMaxSize(),
      ) {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(bottom = 80.dp),
        ) {
          item(key = FiltersKeys.HINT, contentType = "hint") { HintBar(tab.hint) }

          if (tab == FilterTab.LOCAL) {
            if (rules.isEmpty()) {
              item(key = FiltersKeys.EMPTY_LOCAL, contentType = "state") {
                EmptyState(
                  icon = Ng2nIcon.BLOCK,
                  text = "还没有本地屏蔽规则",
                  variant = StateVariant.INLINE,
                )
              }
            } else {
              items(count = rules.size, key = { FiltersKeys.rule(rules[it].id) }, contentType = { "rule" }) { index ->
                val rule = rules[index]
                FilterRow(
                  icon = iconOf(rule.kind),
                  text = "${rule.kind.label}:${rule.value}",
                  sub = localRuleSub(rule),
                  onDelete = {
                    scope.launch {
                      deps.filters.removeLocal(rule.id)
                      Snackbars.show(
                        "已删除规则:${rule.value}",
                        SnackbarAction("撤销") {
                          scope.launch { deps.filters.restoreLocal(rule) }
                        },
                      )
                    }
                  },
                )
              }
            }
          } else {
            officialBody(
              tab = tab,
              signedIn = signedIn,
              uidKnown = uidKnown,
              state = cloud,
              onRetry = { scope.launch { deps.filters.refreshBlockWords() } },
              onRemoveUser = { user ->
                undoable("已解除对 ${user.name} 的屏蔽") { deps.filters.removeOfficialUser(user) }
              },
              onRemoveWord = { word ->
                undoable("已删除官方关键词:$word") { deps.filters.removeOfficialWord(word) }
              },
            )
          }
        }
      }

      if (tab != FilterTab.OFFICIAL_USERS) {
        AddRuleFab(
          leftHanded = appSettings.leftHanded,
          modifier = Modifier.align(
            if (appSettings.leftHanded) Alignment.BottomStart else Alignment.BottomEnd,
          ),
          onClick = {
            when {
              tab == FilterTab.LOCAL -> addRuleOpen = true
              !signedIn -> showLoginPrompt(nav, "登录后才能写官方屏蔽词")
              else -> {
                wordError = null
                addWordOpen = true
              }
            }
          },
        )
      }
    }
  }

  FilterRuleDialog(
    open = addRuleOpen,
    onCancel = { addRuleOpen = false },
    onConfirm = { input: FilterRuleInput ->
      addRuleOpen = false
      scope.launch {
        val rule = deps.filters.addLocal(input)
        Snackbars.show(
          "已添加${rule.kind.label}规则:${rule.value}",
          SnackbarAction("撤销") { scope.launch { deps.filters.removeLocal(rule.id) } },
        )
      }
    },
  )

  InputDialog(
    open = addWordOpen,
    title = "新增官方关键词",
    hint = "写入 NGA 账号云端,与网页版互通;中间不能有空格",
    error = wordError,
    confirmLabel = "保存",
    onCancel = { addWordOpen = false },
    onValueChange = { wordError = null },
    onConfirm = { value ->
      val invalid = blockWordError(value)
      if (invalid != null) {
        wordError = invalid
      } else {
        addWordOpen = false
        val word = value.trim()
        scope.launch {
          runCatching { deps.filters.addOfficialWord(word) }.fold(
            onSuccess = { Snackbars.show("已添加官方关键词:$word") },
            onFailure = cloudFailed,
          )
        }
      }
    },
  )
}

private fun androidx.compose.foundation.lazy.LazyListScope.officialBody(
  tab: FilterTab,
  signedIn: Boolean,
  uidKnown: Boolean,
  state: FilterRepository.BlockWordsState,
  onRetry: () -> Unit,
  onRemoveUser: (BlockedUser) -> Unit,
  onRemoveWord: (String) -> Unit,
) {
  if (!uidKnown) {
    item(key = FiltersKeys.UNKNOWN, contentType = "state") { LoadingState(variant = StateVariant.INLINE) }
    return
  }
  if (!signedIn) {
    item(key = FiltersKeys.GUEST, contentType = "state") {
      EmptyState(
        icon = Ng2nIcon.PERSON,
        text = "登录后才能读写官方屏蔽词",
        variant = StateVariant.INLINE,
      )
    }
    return
  }
  val list: BlockWordList? = state.list
  if (list == null) {
    item(key = FiltersKeys.CLOUD_STATE, contentType = "state") {
      if (state.loading) {
        LoadingState(variant = StateVariant.INLINE)
      } else {
        LoadFailedNotice(error = state.error, onRetry = onRetry)
      }
    }
    return
  }

  if (tab == FilterTab.OFFICIAL_USERS) {
    if (list.users.isEmpty()) {
      item(key = FiltersKeys.EMPTY_USERS, contentType = "state") {
        EmptyState(icon = Ng2nIcon.BLOCK, text = "云端还没有屏蔽的用户", variant = StateVariant.INLINE)
      }
      return
    }
    val users = distinctBlockUsers(list.users)
    items(
      count = users.size,
      key = { FiltersKeys.user(users[it]) },
      contentType = { "rule" },
    ) { index ->
      val user = users[index]
      FilterRow(
        icon = Ng2nIcon.PERSON,
        text = "用户:${user.name}",
        sub = if (user.uid == null) "云端" else "云端 · uid ${user.uid}",
        onDelete = { onRemoveUser(user) },
      )
    }
    return
  }

  if (list.words.isEmpty()) {
    item(key = FiltersKeys.EMPTY_WORDS, contentType = "state") {
      EmptyState(icon = Ng2nIcon.BLOCK, text = "云端还没有屏蔽关键词", variant = StateVariant.INLINE)
    }
    return
  }
  val words = distinctBlockWords(list.words)
  items(count = words.size, key = { FiltersKeys.word(words[it]) }, contentType = { "rule" }) { index ->
    val word = words[index]
    FilterRow(
      icon = Ng2nIcon.TEXT_FIELDS,
      text = "关键词:$word",
      sub = "云端 · 命中标题或正文",
      onDelete = { onRemoveWord(word) },
    )
  }
}

internal object FiltersKeys {
  const val HINT = "hint"
  const val EMPTY_LOCAL = "empty"
  const val UNKNOWN = "unknown"
  const val GUEST = "guest"
  const val CLOUD_STATE = "cloud-state"
  const val EMPTY_USERS = "empty-users"
  const val EMPTY_WORDS = "empty-words"

  val all: List<String> =
    listOf(HINT, EMPTY_LOCAL, UNKNOWN, GUEST, CLOUD_STATE, EMPTY_USERS, EMPTY_WORDS)

  fun rule(id: String): String = "rule/$id"

  fun word(word: String): String = "word/$word"

  fun user(user: BlockedUser): String = "user/${user.uid?.toString() ?: user.name}"
}

internal fun distinctBlockWords(words: List<String>): List<String> = words.distinct()

internal fun distinctBlockUsers(users: List<BlockedUser>): List<BlockedUser> =
  users.distinctBy { it.uid?.toString() ?: it.name }

internal fun localRuleSub(rule: FilterRule): String {
  val added = rule.createdAt?.let { "${dateText(it)} 添加" }
  val scope = when (rule.kind) {
    FilterRuleKind.KEYWORD -> if (rule.regex) "正则 · 命中标题或正文" else "命中标题或正文"
    FilterRuleKind.USER -> if (rule.uid == null) "按用户名匹配" else "uid ${rule.uid}"
    FilterRuleKind.CATEGORY -> "全部版块"
  }
  return if (added == null) scope else "$added · $scope"
}

@Composable
private fun FilterRow(icon: Ng2nIcon, text: String, sub: String, onDelete: () -> Unit) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .padding(vertical = Spacing.row, horizontal = Spacing.lg),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
  ) {
    AppIcon(icon = icon, tint = colors.fg2, size = 20.dp)
    Column(Modifier.weight(1f)) {
      Text(
        text = text,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontSize = 15.5.sp, lineHeight = 22.sp, color = colors.fg),
      )
      Text(
        text = sub,
        modifier = Modifier.padding(top = 4.dp),
        style = TextStyle(
          fontSize = Typo.meta.size,
          lineHeight = Typo.meta.lineHeight,
          color = colors.meta,
        ),
      )
    }
    Box(
      modifier = Modifier
        .size(34.dp)
        .clip(RoundedCornerShape(Radius.full))
        .clickable(onClickLabel = "删除$text", onClick = onDelete)
        .semantics { contentDescription = "删除$text" },
      contentAlignment = Alignment.Center,
    ) {
      AppIcon(icon = Ng2nIcon.CLOSE, tint = colors.danger, size = 19.dp)
    }
  }
}

@Composable
private fun HintBar(text: String) {
  val colors = LocalNg2nColors.current
  Text(
    text = text,
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.surface2)
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .padding(vertical = Spacing.md, horizontal = Spacing.lg),
    style = TextStyle(
      fontSize = Typo.listSubtitle.size,
      lineHeight = Typo.listSubtitle.lineHeight,
      color = colors.meta,
    ),
  )
}

@Composable
private fun AddRuleFab(leftHanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = modifier
      .padding(bottom = 24.dp)
      .padding(start = if (leftHanded) Spacing.xl else 0.dp, end = if (leftHanded) 0.dp else Spacing.xl)
      .height(50.dp)
      .shadow(Elevation.level1, RoundedCornerShape(Radius.pill))
      .clip(RoundedCornerShape(Radius.pill))
      .background(colors.fab)
      .clickable(onClickLabel = "新增规则", onClick = onClick)
      .padding(horizontal = Spacing.xl),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    AppIcon(icon = Ng2nIcon.ADD, tint = colors.onFab, size = 22.dp)
    Text(
      text = "新增规则",
      style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onFab),
    )
  }
}

private const val FALLBACK = "官方屏蔽词没能同步到云端"

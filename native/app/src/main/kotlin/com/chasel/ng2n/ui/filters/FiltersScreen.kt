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

/**
 * 屏蔽规则页(设计稿 isFilters,`research/inventory.md` 第 13 屏)——
 * 直译 RN 侧 `src/app/filters.tsx`。
 *
 * 三个 tab 对应两种数据源:本地规则在 DataStore(**游客也能用**),另外两个 tab 是
 * 同一张云端表(API 文档 §11.5 的 `get/set_block_word`)的两半,写回去也是**整表覆盖**。
 *
 * 入口在设置页(票 17c 的设置树最后一行「屏蔽规则」)与楼层菜单「屏蔽此人」。
 */

/** 设计稿 isFilters 屏:tab 高 42、未选中透明度 .6、指示条 3px。 */
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

/**
 * 设计稿标的是 `person_off` / `text_fields` / `tag`。前两个在图标集里有对应造型,
 * 分类那个取同一套里最接近的 [Ng2nIcon.BOOKMARK](RN 版同样的取舍)。
 */
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

  /*
   * 登录态是**三态**:还没从磁盘读到 / 游客 / 某个 uid。
   * 少了「还没读到」这一档,进屏第一帧就会拿游客态渲染 —— 已登录用户会先看到
   * 一闪的「登录后才能读写官方屏蔽词」。账号表是 DataStore(suspend,修 P2-04),
   * 第一次发射必然晚于首帧。
   */
  var uid by remember { mutableStateOf<String?>(null) }
  var uidKnown by remember { mutableStateOf(false) }
  // 切号也要重来一遍:云端表是账号级数据
  LaunchedEffect(Unit) {
    deps.filters.currentUid.collect { current ->
      uid = current
      uidKnown = true
      deps.filters.ensureBlockWords()
    }
  }
  val signedIn = uid != null

  /** 云端写操作统一的失败话术:接口是整表覆盖,失败时表还是原来那张。 */
  val cloudFailed: (Throwable) -> Unit = { error ->
    Snackbars.show(if (error is IllegalStateException) error.message ?: FALLBACK else failureText(error))
  }

  /** 云端删除:成功后给一手「撤销」——把改动前那张表原样写回去。 */
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
                // 票 41:指示条画**整格**宽(含左右 15 内距)。drawBehind 量的是它
                // 右边那截链子的尺寸,挂在 padding 后面就只有文字宽了 ——
                // RN 那份是 `position:absolute; left:0; right:0` 的独立 View,铺满整格。
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
                  // 设计稿:未选中透明度 .6
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
      // 官方那两个 tab 是云端数据,而且用户可能刚在网页版改过——留一个下拉重读的口子。
      // 本地规则没有「刷新」这回事,改了立刻就在屏上
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

      // 官方用户屏蔽只做「读 + 解除」(票面):加人要 uid,输入框拿不到,所以那个 tab 不给 FAB
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

  // 官方关键词只有「一个词」要填,用通用输入框即可;空格是云端表的分隔符,拦在这里
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

/** 云端两个 tab 共用一份取数状态:游客、加载中、失败、空表各有各的话。 */
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
    // 账号表还没从磁盘读回来:先转圈,别拿游客态措辞
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

/**
 * 屏蔽规则屏那一个 `LazyColumn` 的 key。
 *
 * 状态行(空表 / 游客 / 云端加载)是字面量,规则行是**数据里来的**:本地规则的 id、
 * 云端的关键词原文与用户名。数据里来的那半一律带前缀 —— 用户把「hint」加成屏蔽词时,
 * 裸 key 会和 [HINT] 撞成同一个 key,Compose 当场抛(票 28 同一类崩溃)。
 */
internal object FiltersKeys {
  const val HINT = "hint"
  const val EMPTY_LOCAL = "empty"
  const val UNKNOWN = "unknown"
  const val GUEST = "guest"
  const val CLOUD_STATE = "cloud-state"
  const val EMPTY_USERS = "empty-users"
  const val EMPTY_WORDS = "empty-words"

  /** 静态那半;数据行的 key 由下面三个函数带前缀生成,与它们不可能相等。 */
  val all: List<String> =
    listOf(HINT, EMPTY_LOCAL, UNKNOWN, GUEST, CLOUD_STATE, EMPTY_USERS, EMPTY_WORDS)

  fun rule(id: String): String = "rule/$id"

  fun word(word: String): String = "word/$word"

  fun user(user: BlockedUser): String = "user/${user.uid?.toString() ?: user.name}"
}

/**
 * 云端那张表是**空格分隔的一行文本**(`core/api/BlockWord.kt`),同一个词加两遍在
 * 网页版那边是合法的,读回来就是两个一模一样的条目 —— 直接铺进 `LazyColumn` 会撞 key。
 * 显示前按 key 去重(留第一条),写回云端的仍是仓库里那张原表。
 */
internal fun distinctBlockWords(words: List<String>): List<String> = words.distinct()

/** 同 [distinctBlockWords]:有 uid 的按 uid 去重,没 uid 的按名字。 */
internal fun distinctBlockUsers(users: List<BlockedUser>): List<BlockedUser> =
  users.distinctBy { it.uid?.toString() ?: it.name }

/** 本地规则行的第二行灰字。设计稿在这行放添加时间与生效范围。 */
internal fun localRuleSub(rule: FilterRule): String {
  val added = rule.createdAt?.let { "${dateText(it)} 添加" }
  val scope = when (rule.kind) {
    FilterRuleKind.KEYWORD -> if (rule.regex) "正则 · 命中标题或正文" else "命中标题或正文"
    FilterRuleKind.USER -> if (rule.uid == null) "按用户名匹配" else "uid ${rule.uid}"
    FilterRuleKind.CATEGORY -> "全部版块"
  }
  return if (added == null) scope else "$added · $scope"
}

/** 设计稿 isFilters 的一行:图标 + 两行文字 + 右侧红色 close。 */
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

/** 设计稿:每个 tab 顶上一条 surface-2 底的说明条。 */
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

/** 设计稿:扩展 FAB,高 50、左右 20、圆角 16、距右 20 距底 24。左手模式镜像到左下角。 */
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

/** 云端写操作失败时最后的兜底话术(RN 侧同一句)。 */
private const val FALLBACK = "官方屏蔽词没能同步到云端"

package com.chasel.ng2n.data.filters

import com.chasel.ng2n.core.api.BlockWordList
import com.chasel.ng2n.core.api.BlockedUser
import com.chasel.ng2n.core.api.Topic
import com.chasel.ng2n.core.api.officialFilterRules
import com.chasel.ng2n.core.local.FilterRule
import com.chasel.ng2n.core.local.FilterRuleInput
import com.chasel.ng2n.core.local.FilterRuleKind
import com.chasel.ng2n.core.local.FilterRuleOrigin
import com.chasel.ng2n.core.local.createFilterRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 票 29:屏蔽规则对**主题列表**生效。
 *
 * 规则页顶上白纸黑字写着「命中的主题在列表里隐藏,命中的楼层折叠成一行」——
 * 移植时楼层那半搬了、列表这半漏了(`matchFilterRules` 在 native 侧只有楼层流一个
 * 调用点)。判定本身不重写,这里钉的是**列表这一侧的口径**:
 *
 * - 主题行没有正文,判定只看标题 / 作者 / 作者 uid / 标题里的分类标签;
 * - 命中就整行摘掉(不是折叠);
 * - 本地规则与官方屏蔽表**同一张表**,官方那份没拉回来时列表照常渲染。
 *
 * 用例逐条对着 RN 侧 `useTopicFilter` + `matchFilterRules` 的语义写。
 */
class TopicFilterTest {

  private fun topic(
    tid: Long,
    subject: String,
    author: String = "路人",
    authorId: Long? = 1000 + tid,
  ) = Topic(tid = tid, subject = subject, author = author, authorId = authorId)

  private fun keyword(value: String, regex: Boolean = false): FilterRule =
    createFilterRule(
      FilterRuleInput(kind = FilterRuleKind.KEYWORD, value = value, regex = regex),
      nowSeconds = 1_700_000_000,
    )

  private fun user(value: String, uid: Long? = null): FilterRule = createFilterRule(
    FilterRuleInput(kind = FilterRuleKind.USER, value = value, uid = uid),
    nowSeconds = 1_700_000_000,
  )

  private fun category(value: String): FilterRule = createFilterRule(
    FilterRuleInput(kind = FilterRuleKind.CATEGORY, value = value),
    nowSeconds = 1_700_000_000,
  )

  private val list = listOf(
    topic(1, "[新闻] 《守望先锋》精彩聚焦"),
    topic(2, "[杂谈] 玩快速遇到的这种号目的是啥", author = "老白", authorId = 42),
    topic(3, "求一个内部消息的后续"),
  )

  // ------------------------------------------------------------------ 基本口径

  @Test
  fun `一条规则都没有时原样返回同一个列表`() {
    // 新引用会让屏上 remember(rows) 白重建一次彩色标题
    assertSame(list, filterTopics(emptyList(), list))
  }

  @Test
  fun `关键词命中标题的主题整行摘掉`() {
    val kept = filterTopics(listOf(keyword("内部消息")), list)
    assertEquals(listOf(1L, 2L), kept.map { it.tid })
  }

  @Test
  fun `关键词大小写不敏感`() {
    val topics = listOf(topic(9, "PVE 打不过来看看"))
    assertTrue(filterTopics(listOf(keyword("pve")), topics).isEmpty())
  }

  @Test
  fun `正则关键词按正则跑`() {
    // 票面复现用的就是这条:匹配任何标题,一条都不该剩
    assertTrue(filterTopics(listOf(keyword("^.*$", regex = true)), list).isEmpty())
    assertEquals(
      listOf(3L),
      filterTopics(listOf(keyword("""^\[[^\]]+\]""", regex = true)), list).map { it.tid },
    )
  }

  @Test
  fun `写错的正则永不命中也不抛`() {
    assertEquals(list.map { it.tid }, filterTopics(listOf(keyword("(", regex = true)), list).map { it.tid })
  }

  @Test
  fun `用户规则按名字命中,大小写与首尾空白不算数`() {
    val topics = listOf(topic(9, "标题", author = "老白", authorId = 42))
    assertTrue(filterTopics(listOf(user(" 老白 ")), topics).isEmpty())
  }

  @Test
  fun `带 uid 的用户规则认 uid,改了名照样命中`() {
    val topics = listOf(topic(9, "标题", author = "改过名的老白", authorId = 42))
    assertTrue(filterTopics(listOf(user("老白", uid = 42)), topics).isEmpty())
  }

  @Test
  fun `匿名主题没有 uid 时按名字比`() {
    val topics = listOf(topic(9, "标题", author = "匿名的白", authorId = null))
    assertTrue(filterTopics(listOf(user("匿名的白", uid = 42)), topics).isEmpty())
  }

  @Test
  fun `分类规则看标题里的方括号标签`() {
    val kept = filterTopics(listOf(category("杂谈")), list)
    assertEquals(listOf(1L, 3L), kept.map { it.tid })
  }

  @Test
  fun `主题行没有正文,只有正文才有的词不该误伤`() {
    // 楼层判定会把 content 拼进 haystack;主题行拿不出正文,漏判好过误伤
    val topics = listOf(topic(9, "标题很正常"))
    assertEquals(1, filterTopics(listOf(keyword("正文里的词")), topics).size)
  }

  // ------------------------------------------------------------------ 官方屏蔽表

  @Test
  fun `官方屏蔽表对主题列表同样生效`() {
    val official = officialFilterRules(
      BlockWordList(words = listOf("内部消息"), users = listOf(BlockedUser(uid = 42, name = "老白"))),
    )
    assertEquals(listOf(1L), filterTopics(official, list).map { it.tid })
  }

  @Test
  fun `官方表没拉回来时只按本地规则过,列表不阻塞`() {
    // 游客 / 还在飞的时候 allRules 只有本地那半
    val local = listOf(keyword("内部消息"))
    assertEquals(listOf(1L, 2L), filterTopics(local, list).map { it.tid })
  }

  @Test
  fun `命中时报的是最前面那条规则`() {
    val local = keyword("内部消息")
    val official = officialFilterRules(BlockWordList(words = listOf("内部消息")))
    // 仓库的 allRules 是「本地在前、官方在后」,报的应该是用户自己加的那条
    val hit = matchTopicFilterRules(listOf(local) + official, list[2])
    assertEquals(FilterRuleOrigin.LOCAL, hit?.origin)
    assertNull(matchTopicFilterRules(listOf(local) + official, list[0]))
  }

  // ------------------------------------------------------------------ 与楼层同源

  @Test
  fun `主题行交给判定的字段就是标题 作者 uid 三样`() {
    val subject = topicFilterSubject(topic(9, "[杂谈] 标题", author = "老白", authorId = 42))
    assertEquals("[杂谈] 标题", subject.title)
    assertEquals("老白", subject.author)
    assertEquals(42L, subject.authorId)
    assertNull(subject.content)
  }
}

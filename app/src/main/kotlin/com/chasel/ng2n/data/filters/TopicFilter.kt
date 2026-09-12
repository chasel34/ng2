package com.chasel.ng2n.data.filters

import com.chasel.ng2n.core.api.Topic
import com.chasel.ng2n.core.local.FilterRule
import com.chasel.ng2n.core.local.FilterSubject
import com.chasel.ng2n.core.local.matchFilterRules

/**
 * 主题列表的屏蔽过滤 —— RN 侧 `src/store/filters.ts` 的 `useTopicFilter` 那一半。
 *
 * **列表命中就整行隐藏,楼层命中只折成一行**(屏蔽规则页顶上那句说明白纸黑字写着)。
 * 两处共用同一个 [matchFilterRules](`src/core/local/filters.ts` 的注释点名了这条:
 * 「主题列表与楼层流共用一次 matchFilterRules」),不许各写一套判定 —— 各写一套的结果
 * 就是「列表藏了、点进去详情没折」这种前后不一致。
 *
 * 移植时楼层那半搬了、列表这半漏了(票 29),所以这里补的是接线,不是新判定。
 *
 * ## 主题行拿得出哪些字段
 *
 * 主题行**没有正文**,所以 [FilterSubject.content] 一律缺省:关键词规则只看标题
 * (与 RN 版逐字一致)。分类标签由 `matchFilterRules` 自己从标题里解。
 * 匿名主题没有数字 uid,[Topic.authorId] 是 `null`,那时只能按作者名比。
 */
fun topicFilterSubject(topic: Topic): FilterSubject = FilterSubject(
  author = topic.author,
  authorId = topic.authorId,
  title = topic.subject,
)

/** 这一行命中了哪条规则;没命中返回 `null`。 */
fun matchTopicFilterRules(rules: List<FilterRule>, topic: Topic): FilterRule? =
  matchFilterRules(rules, topicFilterSubject(topic))

/**
 * 把命中规则的主题从列表里摘掉。
 *
 * 一条规则都没有时**返回原列表本身**(不白造一个新 List):调用方拿它当
 * `remember` 的 key,新引用会让整屏彩色标题白重建一遍。
 */
fun filterTopics(rules: List<FilterRule>, topics: List<Topic>): List<Topic> {
  if (rules.isEmpty()) return topics
  return topics.filter { matchTopicFilterRules(rules, it) == null }
}

package com.chasel.ng2n.data.filters

import com.chasel.ng2n.core.api.Topic
import com.chasel.ng2n.core.local.FilterRule
import com.chasel.ng2n.core.local.FilterSubject
import com.chasel.ng2n.core.local.matchFilterRules

fun topicFilterSubject(topic: Topic): FilterSubject = FilterSubject(
  author = topic.author,
  authorId = topic.authorId,
  title = topic.subject,
)

fun matchTopicFilterRules(rules: List<FilterRule>, topic: Topic): FilterRule? =
  matchFilterRules(rules, topicFilterSubject(topic))

fun filterTopics(rules: List<FilterRule>, topics: List<Topic>): List<Topic> {
  if (rules.isEmpty()) return topics
  return topics.filter { matchTopicFilterRules(rules, it) == null }
}

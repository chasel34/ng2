package com.chasel.ng2n.data.ai

import com.chasel.ng2n.core.ai.*
import com.chasel.ng2n.core.api.*
import com.chasel.ng2n.core.local.FilterRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class PersonaHistoryReader(
  private val read: suspend (Long, UserPostKind, Int) -> TopicList,
  private val limiter: ForumReadLimiter = ForumReadLimiter.shared,
) {
  suspend fun load(uid: Long, name: String, rules: List<FilterRule>): TopicContext {
    require(canAnalyzePersona(uid))
    val posts = mutableListOf<Topic>()
    val missing = mutableListOf<String>()
    for (kind in UserPostKind.entries) {
      val seen = mutableSetOf<String>()
      var eligible = 0
      var ended = false
      for (page in 1..100) {
        currentCoroutineContext().ensureActive()
        val batch = try { limiter.read { read(uid, kind, page) }.topics.map { topic ->
          // 历史回复列表的顶层作者属于主题，__P 正文才属于查询用户。
          if (topic.reply != null) topic.copy(author = name, authorId = uid, anonymous = false) else topic
        } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { missing += "$kind 第 $page 页：${ForumToolSession.classify(error)["detail"]}"; ended = true; break }
        if (batch.isEmpty()) { ended = true; break }
        val added = batch.filter { seen.add(personaPostKey(it)) }
        if (added.isEmpty()) { missing += "$kind 分页未提供新发言"; ended = true; break }
        posts += added
        eligible += added.count { personaPostAllowed(it, rules) }
        if (eligible >= 300) { ended = true; break }
      }
      if (!ended) missing += "$kind 达到分页保护限制，仍有未读取历史"
    }
    return buildPersonaContext(posts, name, rules, missing)
  }
}

package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.queryOf
import kotlinx.serialization.json.JsonObject

enum class UserPostKind { TOPICS, REPLIES }

suspend fun fetchUserTopics(
  client: NgaClient,
  uid: Long,
  kind: UserPostKind,
  page: Int,
  sortByPostDate: Boolean = false,
): TopicList {
  val result = client.execute(
    NgaRequest(
      path = "thread.php",
      operation = Operation.READ,
      query = queryOf(
        "authorid" to uid,
        "page" to page,
        "order_by" to if (sortByPostDate) "postdatedesc" else null,
        "searchpost" to if (kind == UserPostKind.REPLIES) 1 else null,
      ),
      validate = ::rejectNonTopicList,
    ),
  )

  val data = result.data
  if (data !is JsonObject) {
    if (result.fakeError != null) return serverEmptyTopicList()
    throw NgaError(NgaErrorKind.PARSE, "用户主题列表响应里没有 data", via = result.via)
  }
  return parseTopicList(data)
}

fun hasMoreUserPosts(page: TopicList): Boolean = page.topics.isNotEmpty()

fun userPostKey(topic: Topic): String = topic.reply?.pid?.takeIf { it > 0 }?.let { "p$it" } ?: "t${topic.tid}"

fun mergeUserPostPages(pages: List<TopicList>): List<Topic> {
  val seen = HashSet<String>()
  val merged = ArrayList<Topic>()
  for (page in pages) {
    for (topic in page.topics) {
      if (!seen.add(userPostKey(topic))) continue
      merged += topic
    }
  }
  return merged
}

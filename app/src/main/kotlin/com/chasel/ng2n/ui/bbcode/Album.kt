package com.chasel.ng2n.ui.bbcode

import com.chasel.ng2n.core.api.AttachmentUrlOptions
import com.chasel.ng2n.core.api.AttachmentUrls

private val TAGGED_PATTERN =
  Regex("""]\s*((?:https?://|\./)[^\[]+?)\s*\[""", RegexOption.IGNORE_CASE)

private val BARE_PATTERN = Regex(
  """(?:^|[^a-zA-Z0-9\-_+=.${'$'};/?:@&#%])((?:https?://|\./)[a-zA-Z0-9\-_+=.${'$'};/?:@&#%]+)""",
  RegexOption.IGNORE_CASE,
)

private val HAS_TAG = Regex("""\[(?:img|url)]""", RegexOption.IGNORE_CASE)

fun albumImageUrls(
  value: String,
  options: AttachmentUrlOptions,
  urls: AttachmentUrls,
): List<String> {
  val pattern = if (HAS_TAG.containsMatchIn(value)) TAGGED_PATTERN else BARE_PATTERN
  return pattern.findAll(value).map { match ->
    val raw = match.groupValues[1]
    val relative = raw.startsWith("./")
    urls.attachmentUrl(
      src = if (relative) raw.substring(2) else raw,
      needsAttachBase = relative,
      options = options,
    )
  }.toList()
}

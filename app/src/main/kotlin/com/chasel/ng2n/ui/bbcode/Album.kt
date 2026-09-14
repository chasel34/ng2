package com.chasel.ng2n.ui.bbcode

import com.chasel.ng2n.core.api.AttachmentUrlOptions
import com.chasel.ng2n.core.api.AttachmentUrls

fun albumImageUrls(value: String, options: AttachmentUrlOptions, urls: AttachmentUrls): List<String> =
  com.chasel.ng2n.core.ai.albumImageUrls(value, options, urls)

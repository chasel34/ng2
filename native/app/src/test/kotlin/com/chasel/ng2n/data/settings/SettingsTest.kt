package com.chasel.ng2n.data.settings

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `src/core/local/settings.test.ts` 的手工移植(票 14 验收项①)。
 * 重点是**逐字段容错**:加了新设置项的版本读旧存档,老项要留着;
 * 某一项写坏了只丢那一项。
 */
class SettingsTest {

  private fun json(raw: String) = Json.parseToJsonElement(raw)

  // ------------------------------------------------------------ 默认值

  @Test
  fun `默认域名就是 API 文档 §0点1 的首选域名`() {
    assertEquals(DEFAULT_NGA_HOST, DEFAULT_SETTINGS.host)
  }

  @Test
  fun `五根滑杆的默认值都落在自己的量程内 且正好是步长的整数倍`() {
    for (spec in APPEARANCE_SLIDERS) {
      val value = spec.get(DEFAULT_SETTINGS.appearance)
      assertTrue(value >= spec.min, spec.key)
      assertTrue(value <= spec.max, spec.key)
      assertEquals(value, clampSlider(spec, value), spec.key)
    }
  }

  @Test
  fun `头像与表情的默认百分比换算回现行尺寸`() {
    assertEquals(AVATAR_BASE_SIZE, avatarSizeOf(DEFAULT_SETTINGS.appearance.avatarScale))
    assertEquals(24, smileyHeightOf(DEFAULT_SETTINGS.appearance.smileyScale))
    assertEquals(16, SMILEY_BASE_HEIGHT)
  }

  // ------------------------------------------------------------ clampSlider

  private val lineHeight = sliderSpec("bodyLineHeight")

  @Test
  fun `夹在量程内`() {
    assertEquals(lineHeight.min, clampSlider(lineHeight, 0.4))
    assertEquals(lineHeight.max, clampSlider(lineHeight, 9.0))
  }

  @Test
  fun `量化到步长`() {
    assertEquals(140.0, clampSlider(sliderSpec("smileyScale"), 143.0))
    assertEquals(15.5, clampSlider(sliderSpec("bodyFontSize"), 15.3))
  }

  /** 0.02 步长连加会攒出 1.7000000000000002,气泡上就露出来了。 */
  @Test
  fun `浮点步长不会攒出长尾小数`() {
    var value = lineHeight.min
    repeat(20) { value = clampSlider(lineHeight, value + lineHeight.step) }
    assertEquals(1.7, value)
  }

  @Test
  fun `拿到 NaN 时回落到默认值`() {
    assertEquals(
      DEFAULT_SETTINGS.appearance.bodyLineHeight,
      clampSlider(lineHeight, Double.NaN),
    )
  }

  // ------------------------------------------------------------ 滑杆比例

  private val avatar = sliderSpec("avatarScale")

  @Test
  fun `比例与值可以来回换算`() {
    assertEquals(0.0, sliderRatio(avatar, avatar.min))
    assertEquals(1.0, sliderRatio(avatar, avatar.max))
    assertEquals(
      clampSlider(avatar, (avatar.min + avatar.max) / 2),
      sliderValueAt(avatar, 0.5),
    )
  }

  @Test
  fun `拖出轨道两端的比例被夹住`() {
    assertEquals(1.0, sliderRatio(avatar, 999.0))
    assertEquals(avatar.min, sliderValueAt(avatar, -3.0))
  }

  // ------------------------------------------------------------ formatSliderValue

  @Test
  fun `整数档不带小数点 百分比档带百分号`() {
    assertEquals("17", formatSliderValue(sliderSpec("listFontSize"), 17.0))
    assertEquals("104%", formatSliderValue(avatar, 104.0))
    assertEquals("15.5", formatSliderValue(sliderSpec("bodyFontSize"), 15.5))
    assertEquals("1.70", formatSliderValue(lineHeight, 1.7))
  }

  // ------------------------------------------------------------ parseSettings

  @Test
  fun `存档不是对象时整份回落`() {
    assertEquals(DEFAULT_SETTINGS, parseSettings(null))
    assertEquals(DEFAULT_SETTINGS, parseSettings(json("null")))
    assertEquals(DEFAULT_SETTINGS, parseSettings(json("\"{}\"")))
  }

  @Test
  fun `认得的域名留下 不认得的换回默认域名`() {
    assertEquals(NGA_HOSTS[3], parseSettings(json("""{"host":"${NGA_HOSTS[3]}"}""")).host)
    assertEquals(
      DEFAULT_NGA_HOST,
      parseSettings(json("""{"host":"https://evil.example"}""")).host,
    )
  }

  /** 加了新设置项的版本读旧存档,老项不能被整份默认值盖掉。 */
  @Test
  fun `只认得一半的存档里 认得的那一半保留`() {
    val parsed = parseSettings(json("""{"solidBackground":true,"imageQuality":"thumbnail"}"""))
    assertEquals(true, parsed.solidBackground)
    assertEquals(ImageQuality.THUMBNAIL, parsed.imageQuality)
    assertEquals(DEFAULT_SETTINGS.showSignature, parsed.showSignature)
  }

  @Test
  fun `类型不对的项各自回落 不牵连别项`() {
    val parsed = parseSettings(
      json(
        """
        {
          "wifiOnlyImages": "yes",
          "themeStyle": "rainbow",
          "keepScreenOn": true,
          "appearance": { "bodyFontSize": "大", "avatarScale": 132 }
        }
        """.trimIndent(),
      ),
    )
    assertEquals(DEFAULT_SETTINGS.wifiOnlyImages, parsed.wifiOnlyImages)
    assertEquals(DEFAULT_SETTINGS.themeStyle, parsed.themeStyle)
    assertEquals(true, parsed.keepScreenOn)
    assertEquals(DEFAULT_SETTINGS.appearance.bodyFontSize, parsed.appearance.bodyFontSize)
    assertEquals(132.0, parsed.appearance.avatarScale)
  }

  @Test
  fun `越界的滑杆值被夹回量程`() {
    val parsed = parseSettings(json("""{"appearance":{"listFontSize":999}}"""))
    assertEquals(sliderSpec("listFontSize").max, parsed.appearance.listFontSize)
  }

  @Test
  fun `一趟存读之后设置表原样`() {
    val settings = DEFAULT_SETTINGS.copy(
      host = NGA_HOSTS[1],
      themeStyle = ThemeStyle.PLAIN,
      appearance = DEFAULT_SETTINGS.appearance.copy(bodyLineHeight = 1.9),
    )
    assertEquals(settings, parseSettings(settings.toJson()))
  }

  // ------------------------------------------------------------ 其余 MMKV 键的容错

  @Test
  fun `签到日只认 YYYY-MM-DD 坏值当没签过`() {
    val days = sanitizeCheckInDays(
      mapOf("1" to "2026-08-08", "2" to "昨天", "3" to ""),
    )
    assertEquals(mapOf("1" to "2026-08-08"), days)
  }

  @Test
  fun `签到日按 UTC加8 算而不是设备时区`() {
    // 2026-08-08 00:30 UTC+8 == 2026-08-07 16:30 UTC
    val ms = java.time.Instant.parse("2026-08-07T16:30:00Z").toEpochMilli()
    assertEquals("2026-08-08", beijingDayKey(ms))
    assertTrue(isCheckedInOn(mapOf("42" to "2026-08-08"), "42", ms))
  }

  @Test
  fun `搜索历史同词同范围只留一条 且每 tab 最多 20 条`() {
    var history = EMPTY_SEARCH_HISTORY
    repeat(25) { index ->
      history = addSearchHistory(history, SearchTab.TOPICS, SearchHistoryEntry("词$index"))
    }
    assertEquals(SEARCH_HISTORY_LIMIT, history.topics.size)
    assertEquals("词24", history.topics[0].query)

    history = addSearchHistory(history, SearchTab.TOPICS, SearchHistoryEntry("词24"))
    assertEquals(SEARCH_HISTORY_LIMIT, history.topics.size)
    assertEquals("词24", history.topics[0].query)
    assertEquals(1, history.topics.count { it.query == "词24" })
  }

  @Test
  fun `版块树缓存 24 小时 SWR 时钟往回跳一律按过期算`() {
    val now = 1_800_000_000_000L
    assertTrue(isBoardTreeStale(now - BOARD_TREE_TTL_MS, now))
    assertTrue(!isBoardTreeStale(now - BOARD_TREE_TTL_MS + 1, now))
    assertTrue(isBoardTreeStale(now + 1000, now))
  }

  @Test
  fun `已读公告只留最近 20 条 重复关同一条不重复占位`() {
    var ids = emptyList<String>()
    repeat(25) { ids = withDismissedAnnouncement(ids, "a$it") }
    assertEquals(DISMISSED_ANNOUNCEMENTS_LIMIT, ids.size)
    assertEquals("a24", ids.last())

    ids = withDismissedAnnouncement(ids, "a24")
    assertEquals(DISMISSED_ANNOUNCEMENTS_LIMIT, ids.size)
    assertEquals(1, ids.count { it == "a24" })
  }

  @Test
  fun `屏蔽规则归一化 同一条重复添加会覆盖而不是并存`() {
    val rule = createFilterRule(
      FilterRuleInput(FilterRuleKind.USER, "  张 三 "),
      nowSeconds = 100,
    )
    assertEquals("张 三", rule.value)
    assertEquals("local:user:张 三", rule.id)

    val same = createFilterRule(FilterRuleInput(FilterRuleKind.USER, "张   三"), 200)
    val rules = upsertFilterRule(upsertFilterRule(emptyList(), rule), same)
    assertEquals(1, rules.size)
    assertEquals(200L, rules[0].createdAt)
  }

  @Test
  fun `正则只有关键词能开 且非法正则在存之前就报错`() {
    assertEquals(false, createFilterRule(FilterRuleInput(FilterRuleKind.USER, "x", regex = true), 1).regex)
    assertTrue(
      validateFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, "[", regex = true))
        ?.startsWith("正则表达式不合法") == true,
    )
    assertNull(validateFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, "abc")))
  }
}

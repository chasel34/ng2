package com.chasel.ng2n.core.local

private const val STEMS = "甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉戌亥"

private const val SURNAMES = "王李张刘陈杨黄吴赵周徐孙马朱胡林郭何高罗郑梁谢宋唐许邓冯韩曹曾彭萧蔡潘田董袁于余叶蒋杜苏魏程吕丁沈任姚卢傅钟姜崔谭廖范汪陆金石戴贾韦夏邱方侯邹熊孟秦白江阎薛尹段雷黎史龙陶贺顾毛郝龚邵万钱严赖覃洪武莫孔汤向常温康施文牛樊葛邢安齐易乔伍庞颜倪庄聂章鲁岳翟殷詹申欧耿关兰焦俞左柳甘祝包宁尚符舒阮柯纪梅童凌毕单季裴霍涂成苗谷盛曲翁冉骆蓝路游辛靳管柴蒙鲍华喻祁蒲房滕屈饶解牟艾尤阳时穆农司卓古吉缪简车项连芦麦褚娄窦戚岑景党宫费卜冷晏席卫米柏宗瞿桂全佟应臧闵苟邬边卞姬师和仇栾隋商刁沙荣巫寇桑郎甄丛仲虞敖巩明佘池查麻苑迟邝"

private val ANONYMOUS_PATTERN = Regex("^#anony_([0-9a-f]{32})$")

private data class NameSegment(val table: String, val at: Int, val width: Int)

private val SEGMENTS = listOf(
  NameSegment(STEMS, 0, 1),
  NameSegment(SURNAMES, 1, 2),
  NameSegment(SURNAMES, 3, 2),
  NameSegment(STEMS, 6, 1),
  NameSegment(SURNAMES, 7, 2),
  NameSegment(SURNAMES, 9, 2),
)

data class AnonymousName(
  val name: String,
  val colors: Pair<String, String>,
)

fun decodeAnonymousName(author: String): AnonymousName? {
  val hex = ANONYMOUS_PATTERN.find(author)?.groupValues?.get(1) ?: return null

  val name = buildString {
    for ((table, at, width) in SEGMENTS) {
      val index = hex.substring(at, at + width).toInt(16)
      if (index < table.length) append(table[index])
    }
  }

  return AnonymousName(name, hex.substring(11, 17) to hex.substring(17, 23))
}

fun resolveAuthorName(author: String): String = decodeAnonymousName(author)?.name ?: author

fun isAnonymousAuthor(author: String): Boolean = ANONYMOUS_PATTERN.matches(author)

package com.chasel.ng2n.core.local

/**
 * 匿名还原:把 `#anony_<32 位 hex>` 解成六字假名。直译 `src/core/local/anonymous.ts`。
 *
 * 算法与两张字符表都照 **NGA 官方前端** `commonui.anonyName`(js_commonui.js)复刻,
 * 与表情映射表同一条来源约定:只从官方脚本取,不碰 GPL-2.0 的第三方客户端代码。
 *
 * hex 的取用位置是官方那段循环的直译(`i` 从 6 起步、每轮 +2,干支段只取后一位):
 *
 * ```text
 * hex 下标  0    1 2   3 4   5    6    7 8   9 10   11–16   17–22   23–31
 *          干支  百家姓 百家姓 跳过  干支  百家姓 百家姓   色1     色2     没人用
 * ```
 *
 * **hex[5] 官方就是跳过的,别「顺手修好」**——改了就和网页版对不上了(逆向怪癖勿修)。
 */

/** 天干地支 22 字;干支段只喂得进 0–15,后 6 个字实际取不到。 */
private const val STEMS = "甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉戌亥"

/**
 * 百家姓表。官方这张表只有 **255** 字,而下标来自一整字节,
 * 所以 `0xff` 落在表外——网页版此时就是少一个字,这里照抄,不补齐。
 */
private const val SURNAMES = "王李张刘陈杨黄吴赵周徐孙马朱胡林郭何高罗郑梁谢宋唐许邓冯韩曹曾彭萧蔡潘田董袁于余叶蒋杜苏魏程吕丁沈任姚卢傅钟姜崔谭廖范汪陆金石戴贾韦夏邱方侯邹熊孟秦白江阎薛尹段雷黎史龙陶贺顾毛郝龚邵万钱严赖覃洪武莫孔汤向常温康施文牛樊葛邢安齐易乔伍庞颜倪庄聂章鲁岳翟殷詹申欧耿关兰焦俞左柳甘祝包宁尚符舒阮柯纪梅童凌毕单季裴霍涂成苗谷盛曲翁冉骆蓝路游辛靳管柴蒙鲍华喻祁蒲房滕屈饶解牟艾尤阳时穆农司卓古吉缪简车项连芦麦褚娄窦戚岑景党宫费卜冷晏席卫米柏宗瞿桂全佟应臧闵苟邬边卞姬师和仇栾隋商刁沙荣巫寇桑郎甄丛仲虞敖巩明佘池查麻苑迟邝"

/** 官方 `commonui.htmlName` 判定匿名用的同一条正则:32 位**小写** hex。 */
private val ANONYMOUS_PATTERN = Regex("^#anony_([0-9a-f]{32})$")

/** 六个字各自的取法:查哪张表、从 hex 第几位起、吃几位。 */
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
  /** 还原出的假名,正常是六个字(255 表越界时会少字,见 [SURNAMES]) */
  val name: String,
  /** 官方给这个匿名身份配的两个颜色(6 位 hex,不带 `#`),画那对笑脸用 */
  val colors: Pair<String, String>,
)

/** 还原匿名作者名;不是匿名串(普通用户名、`#ANONYMOUS#`)返回 `null`。 */
fun decodeAnonymousName(author: String): AnonymousName? {
  val hex = ANONYMOUS_PATTERN.find(author)?.groupValues?.get(1) ?: return null

  val name = buildString {
    for ((table, at, width) in SEGMENTS) {
      val index = hex.substring(at, at + width).toInt(16)
      // JS 的 String.charAt 越界返回空串——255 字的百家姓表遇上 0xff 就是这条路,照抄
      if (index < table.length) append(table[index])
    }
  }

  return AnonymousName(name, hex.substring(11, 17) to hex.substring(17, 23))
}

/** 拿去显示的作者名:匿名的还原成假名,其余原样。 */
fun resolveAuthorName(author: String): String = decodeAnonymousName(author)?.name ?: author

/** 这个作者名是不是匿名串。 */
fun isAnonymousAuthor(author: String): Boolean = ANONYMOUS_PATTERN.matches(author)

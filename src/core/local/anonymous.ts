/**
 * 匿名还原（CONTEXT.md「匿名还原」）——把 `#anony_<32 位 hex>` 解成六字假名。
 *
 * 算法与两张字符表都照 **NGA 官方前端** `commonui.anonyName`
 * （https://img4.nga.cn/common_res/js_commonui.js）复刻，与表情映射表同一条来源约定：
 * 只从官方脚本取，不碰 GPL-2.0 的第三方客户端代码。
 *
 * hex 的取用位置是官方那段循环的直译（`i` 从 6 起步、每轮 +2，干支段只取后一位）：
 *
 * ```text
 * hex 下标  0    1 2   3 4   5    6    7 8   9 10   11–16   17–22   23–31
 *          干支  百家姓 百家姓 跳过  干支  百家姓 百家姓   色1     色2     没人用
 * ```
 *
 * hex[5] 官方就是跳过的，别「顺手修好」——改了就和网页版对不上了。
 */

/** 天干地支 22 字；干支段只喂得进 0–15，后 6 个字实际取不到。 */
const STEMS = '甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉戌亥'
/**
 * 百家姓表。官方这张表只有 255 字，而下标来自一整字节，
 * 所以 `0xff` 落在表外——网页版此时就是少一个字，这里照抄，不补齐。
 */
const SURNAMES = '王李张刘陈杨黄吴赵周徐孙马朱胡林郭何高罗郑梁谢宋唐许邓冯韩曹曾彭萧蔡潘田董袁于余叶蒋杜苏魏程吕丁沈任姚卢傅钟姜崔谭廖范汪陆金石戴贾韦夏邱方侯邹熊孟秦白江阎薛尹段雷黎史龙陶贺顾毛郝龚邵万钱严赖覃洪武莫孔汤向常温康施文牛樊葛邢安齐易乔伍庞颜倪庄聂章鲁岳翟殷詹申欧耿关兰焦俞左柳甘祝包宁尚符舒阮柯纪梅童凌毕单季裴霍涂成苗谷盛曲翁冉骆蓝路游辛靳管柴蒙鲍华喻祁蒲房滕屈饶解牟艾尤阳时穆农司卓古吉缪简车项连芦麦褚娄窦戚岑景党宫费卜冷晏席卫米柏宗瞿桂全佟应臧闵苟邬边卞姬师和仇栾隋商刁沙荣巫寇桑郎甄丛仲虞敖巩明佘池查麻苑迟邝'

/** 官方 `commonui.htmlName` 判定匿名用的同一条正则：32 位小写 hex。 */
const ANONYMOUS_PATTERN = /^#anony_([0-9a-f]{32})$/

/** 六个字各自的取法：查哪张表、从 hex 第几位起、吃几位。 */
const SEGMENTS = [
  { table: STEMS, at: 0, width: 1 },
  { table: SURNAMES, at: 1, width: 2 },
  { table: SURNAMES, at: 3, width: 2 },
  { table: STEMS, at: 6, width: 1 },
  { table: SURNAMES, at: 7, width: 2 },
  { table: SURNAMES, at: 9, width: 2 },
] as const

export interface AnonymousName {
  /** 还原出的假名，正常是六个字 */
  readonly name: string
  /** 官方给这个匿名身份配的两个颜色（6 位 hex，不带 #），画那对笑脸用 */
  readonly colors: readonly [string, string]
}

/** 还原匿名作者名；不是匿名串（普通用户名、`#ANONYMOUS#`）返回 undefined。 */
export function decodeAnonymousName(author: string): AnonymousName | undefined {
  const hex = ANONYMOUS_PATTERN.exec(author)?.[1]
  if (hex === undefined) return undefined

  const name = SEGMENTS.map(({ table, at, width }) =>
    table.charAt(Number.parseInt(hex.slice(at, at + width), 16)),
  ).join('')

  return { name, colors: [hex.slice(11, 17), hex.slice(17, 23)] }
}

/** 拿去显示的作者名：匿名的还原成假名，其余原样。 */
export function resolveAuthorName(author: string): string {
  return decodeAnonymousName(author)?.name ?? author
}

/** 这个作者名是不是匿名串。 */
export function isAnonymousAuthor(author: string): boolean {
  return ANONYMOUS_PATTERN.test(author)
}

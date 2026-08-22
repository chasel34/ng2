#!/usr/bin/env node
/**
 * `src/core/smilies/table.generated.ts` → `native/…/ui/bbcode/Smilies.kt`(票 11)。
 *
 * 为什么不再从 `js_bbscode_core.js` 重抓一遍(`scripts/fetch-smilies.mjs` 干的事):
 * 表情表与随包图片是**同一批产物**,重抓可能拿到官方新版而与 `assets/smilies/` 里
 * 已经下载好的 265 张图对不上——「表里有、包里没有」的那几个会静默退化成远程 URL。
 * 所以这里只做「同一份表换一种语言」,数据源是 RN 侧已经落盘的生成物。
 *
 * 用法(仓库根目录):
 *   node scripts/gen-smilies-kt.mjs
 *
 * 图片本体的搬运不在这个脚本里(一次性的 `cp assets/smilies/* native/app/src/main/assets/smilies/`):
 * 文件名即 CDN 原名,Kotlin 侧按 `file:///android_asset/smilies/<file>` 取。
 */

import { readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const SOURCE = join(root, 'src/core/smilies/table.generated.ts')
const TARGET = join(root, 'native/app/src/main/kotlin/com/chasel/ng2n/ui/bbcode/Smilies.kt')

/**
 * TS 生成物里除了 `import type` 与几个类型标注,全是 JS 字面量。
 * 把那几处削掉之后整段就是合法 JS,直接求值——比写一个 TS 解析器省事,
 * 也比正则抠数组稳(表里有中文名、引号、转义)。
 */
async function loadTable() {
  const ts = await readFile(SOURCE, 'utf8')
  const js = ts
    .replace(/^import type .*$/gm, '')
    .replace(/:\s*readonly SmileyCategoryData\[\]/g, '')
    .replace(/:\s*readonly string\[\]/g, '')
    .replace(/^export /gm, '')
  const factory = new Function(
    `${js}\nreturn { SMILEY_BASE_URL, SMILEY_CATEGORIES, BUNDLED_SMILEY_FILES }`,
  )
  return factory()
}

/** Kotlin 字符串字面量转义。表情名里有中文、没有换行,但引号与反斜杠得挡住。 */
const kt = (value) => `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\$/g, '\\$')}"`

const table = await loadTable()

const categories = table.SMILEY_CATEGORIES.map((category) => {
  const entries = category.entries
    .map(([name, file]) => `      ${kt(name)} to ${kt(file)},`)
    .join('\n')
  return [
    '  SmileyCategory(',
    `    key = ${kt(category.key)},`,
    `    label = ${kt(category.label)},`,
    '    entries = listOf(',
    entries,
    '    ),',
    '  ),',
  ].join('\n')
}).join('\n')

const bundled = table.BUNDLED_SMILEY_FILES.map((file) => `  ${kt(file)},`).join('\n')

const out = `package com.chasel.ng2n.ui.bbcode

/**
 * 本文件由 \`scripts/gen-smilies-kt.mjs\` 生成,请勿手改。
 * 数据源:\`src/core/smilies/table.generated.ts\`(它本身由 \`scripts/fetch-smilies.mjs\`
 * 从 https://img4.nga.cn/common_res/js_bbscode_core.js 抓出来)。
 *
 * ${table.SMILEY_CATEGORIES.length} 个套系、${table.SMILEY_CATEGORIES.reduce((sum, c) => sum + c.entries.length, 0)} 个表情、${table.BUNDLED_SMILEY_FILES.length} 个随包文件。
 * 图片在 \`app/src/main/assets/smilies/\`,文件名即 CDN 原名。
 */

/** 一个套系。\`entries\` 的顺序与官方表一致(表情面板按此排列)。 */
data class SmileyCategory(
  /** BBCode 里的分类标识,如 \`ac\`;\`"0"\` 是 \`[s:数字]\` 用的默认套。 */
  val key: String,
  /** 官方套系中文名,如 \`AC娘(v1)\`。 */
  val label: String,
  /** \`名称 to 文件名\`。 */
  val entries: List<Pair<String, String>>,
)

/** CDN 上表情图所在目录,远程兜底 URL 的前缀。 */
const val SMILEY_BASE_URL: String = ${kt(table.SMILEY_BASE_URL)}

/** 官方表里的套系顺序。 */
val SMILEY_CATEGORIES: List<SmileyCategory> = listOf(
${categories}
)

/** 实际随包下载成功的文件名。表里有、这里没有的走远程 URL。 */
val BUNDLED_SMILEY_FILES: Set<String> = setOf(
${bundled}
)
`

await writeFile(TARGET, out, 'utf8')
console.log(
  `Smilies.kt 已生成:${table.SMILEY_CATEGORIES.length} 套系 / ${table.BUNDLED_SMILEY_FILES.length} 随包文件`,
)

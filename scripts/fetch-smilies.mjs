#!/usr/bin/env node
/**
 * 表情资产管线:从 NGA 官方前端脚本抓映射表 → 从 CDN 批量下载表情图 → 生成查表数据。
 *
 * 幂等:已存在且非空的图片默认跳过,重跑只补缺失项;生成物不含时间戳,内容不变则 git 无 diff。
 *
 *   node scripts/fetch-smilies.mjs              # 增量
 *   node scripts/fetch-smilies.mjs --force      # 重下全部图片
 *   node scripts/fetch-smilies.mjs --source x.js  # 用本地 js 副本解析(离线;须是未转码的 GBK 原文件)
 *
 * 产物:
 *   assets/smilies/*.png|gif          表情图(扁平,与 CDN 目录同名;仅个人使用,不分发)
 *   assets/smilies/manifest.json      套系/数量/缺失项清单
 *   src/core/smilies/table.generated.ts   纯 TS 映射表(零 RN 依赖)
 *   src/ui/smilies.generated.ts           metro 静态 require 映射
 */

import { mkdir, readdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseSmiliesTable } from './lib/parse-smilies.mjs'

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)))

/** NGA 官方前端脚本,表情映射表的唯一合法来源(禁止从 GPL-2.0 的 Justwen 仓库复制)。 */
const SOURCE_URL = 'https://img4.nga.cn/common_res/js_bbscode_core.js'
/** 线上 `__IMGPATH`,取自 bbs.nga.cn 页面内联 JS。 */
const IMG_PATH = process.env.NGA_IMGPATH ?? 'https://img4.nga.cn/ngabbs'
const SMILE_DIR_URL = `${IMG_PATH}/post/smile`

const ASSET_DIR = join(ROOT, 'assets', 'smilies')
const MANIFEST_PATH = join(ASSET_DIR, 'manifest.json')
const CORE_TABLE_PATH = join(ROOT, 'src', 'core', 'smilies', 'table.generated.ts')
const UI_ASSETS_PATH = join(ROOT, 'src', 'ui', 'smilies.generated.ts')

const USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
const CONCURRENCY = 8
const RETRIES = 3

const GENERATED_HEADER = `// 本文件由 scripts/fetch-smilies.mjs 生成,请勿手改。\n// 数据源:${SOURCE_URL}(NGA 官方前端脚本)\n`

/**
 * @param {string} url
 * @returns {Promise<Response>}
 */
async function fetchWithRetry(url) {
  let lastError
  for (let attempt = 1; attempt <= RETRIES; attempt += 1) {
    try {
      const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      return res
    } catch (error) {
      lastError = error
      if (attempt < RETRIES) await new Promise((r) => setTimeout(r, 300 * attempt))
    }
  }
  throw new Error(`下载失败 ${url}: ${lastError instanceof Error ? lastError.message : lastError}`)
}

/**
 * NGA 全站 GBK,脚本本身也是 GBK,表情名是中文,必须显式解码。
 * @param {string | undefined} localPath
 * @returns {Promise<string>}
 */
async function loadSourceJs(localPath) {
  const buffer = localPath
    ? await readFile(localPath)
    : Buffer.from(await (await fetchWithRetry(SOURCE_URL)).arrayBuffer())
  return new TextDecoder('gbk').decode(buffer)
}

/**
 * 认一下文件头。CDN 偶尔会用 200 返回 HTML 错误页,存进 assets 就成了打不开的"表情"。
 * @param {Buffer} body
 * @returns {boolean}
 */
function looksLikeImage(body) {
  if (body.length < 8) return false
  const png = body.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))
  const gif = body.subarray(0, 6).toString('latin1').startsWith('GIF8')
  const jpeg = body[0] === 0xff && body[1] === 0xd8
  return png || gif || jpeg
}

/**
 * @param {string} file
 * @param {boolean} force
 * @returns {Promise<'skipped' | 'downloaded' | 'missing'>}
 */
async function downloadSmiley(file, force) {
  const target = join(ASSET_DIR, file)
  if (!force) {
    const existing = await stat(target).catch(() => null)
    if (existing?.isFile() && existing.size > 0) return 'skipped'
  }
  try {
    const res = await fetchWithRetry(`${SMILE_DIR_URL}/${file}`)
    const body = Buffer.from(await res.arrayBuffer())
    if (!looksLikeImage(body)) throw new Error(`响应不是图片(${body.length} 字节)`)
    // 先写临时文件再改名,中断时不会留下能骗过"已存在"判断的半截图。
    await writeFile(`${target}.part`, body)
    await rename(`${target}.part`, target)
    return 'downloaded'
  } catch (error) {
    await rm(`${target}.part`, { force: true })
    console.warn(`  ! ${file}: ${error instanceof Error ? error.message : error}`)
    return 'missing'
  }
}

/**
 * @template T, R
 * @param {readonly T[]} items
 * @param {(item: T) => Promise<R>} worker
 * @returns {Promise<R[]>}
 */
async function mapWithConcurrency(items, worker) {
  const results = new Array(items.length)
  let cursor = 0
  await Promise.all(
    Array.from({ length: Math.min(CONCURRENCY, items.length) }, async () => {
      while (cursor < items.length) {
        const index = cursor
        cursor += 1
        results[index] = await worker(items[index])
      }
    }),
  )
  return results
}

/**
 * 生成 TS 用的单引号字面量,和手写代码的引号风格一致(表情名是中文,不需要转义)。
 * @param {string} value
 */
const quote = (value) => `'${value.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`

/**
 * @param {import('./lib/parse-smilies.mjs').SmileyCategory[]} categories
 * @param {readonly string[]} bundled
 * @returns {string}
 */
function renderCoreTable(categories, bundled) {
  const blocks = categories.map((category) => {
    const entries = category.entries
      .map((entry) => `      [${quote(entry.name)}, ${quote(entry.file)}],`)
      .join('\n')
    return [
      '  {',
      `    key: ${quote(category.key)},`,
      `    label: ${quote(category.label)},`,
      '    entries: [',
      entries,
      '    ],',
      '  },',
    ].join('\n')
  })
  return `${GENERATED_HEADER}
import type { SmileyCategoryData } from './types'

/** CDN 上表情图所在目录(\`{__IMGPATH}/post/smile\`),远程兜底 URL 的前缀。 */
export const SMILEY_BASE_URL = ${quote(SMILE_DIR_URL)}

/** 官方表里的套系顺序,表情面板按此排列;\`'0'\` 是 \`[s:数字]\` 用的默认套。 */
export const SMILEY_CATEGORIES: readonly SmileyCategoryData[] = [
${blocks.join('\n')}
]

/** 已随包内置的图片文件名;不在此列的走远程 URL 兜底。 */
export const BUNDLED_SMILEY_FILES: readonly string[] = [
${bundled.map((file) => `  ${quote(file)},`).join('\n')}
]
`
}

/**
 * metro 只认字面量 require,所以这份映射必须整表展开。
 * @param {readonly string[]} bundled
 * @returns {string}
 */
function renderUiAssets(bundled) {
  const entries = bundled
    .map((file) => `  ${quote(file)}: require('../../assets/smilies/${file}'),`)
    .join('\n')
  return `${GENERATED_HEADER}
/**
 * 表情图文件名 → RN 静态资源。metro 要求 require 路径是字面量,故整表展开。
 * 键与 \`src/core/smilies\` 里 \`ResolvedSmiley.file\` 对齐:
 *
 * \`\`\`ts
 * const smiley = resolveSmiley(code)
 * if (smiley.kind === 'unresolved') return <Text>{smiley.raw}</Text>
 * const source = SMILEY_ASSETS[smiley.file] ?? { uri: smiley.remoteUrl }
 * \`\`\`
 */
export const SMILEY_ASSETS: Readonly<Record<string, number | undefined>> = {
${entries}
}
`
}

/**
 * 内容一致就不落盘,避免重跑把 mtime 搅乱。
 * @param {string} path
 * @param {string} content
 */
async function writeIfChanged(path, content) {
  const current = await readFile(path, 'utf8').catch(() => null)
  if (current === content) return false
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, content)
  return true
}

async function main() {
  const args = process.argv.slice(2)
  const force = args.includes('--force')
  const sourceArg = args.indexOf('--source')
  const localSource = sourceArg >= 0 ? args[sourceArg + 1] : undefined
  if (sourceArg >= 0 && !localSource) throw new Error('--source 后面要跟 js_bbscode_core.js 的本地路径')

  console.log(localSource ? `解析本地副本 ${localSource}` : `拉取 ${SOURCE_URL}`)
  const categories = parseSmiliesTable(await loadSourceJs(localSource))
  const files = [...new Set(categories.flatMap((c) => c.entries.map((e) => e.file)))]
  const entryCount = categories.reduce((n, c) => n + c.entries.length, 0)
  console.log(`映射表:${categories.length} 套 / ${entryCount} 条 / ${files.length} 个文件`)
  for (const c of categories) console.log(`  ${c.key.padEnd(4)} ${c.label} — ${c.entries.length}`)

  await mkdir(ASSET_DIR, { recursive: true })
  console.log(`下载到 ${ASSET_DIR}${force ? '(--force 全量重下)' : ''}`)
  const outcomes = await mapWithConcurrency(files, (file) => downloadSmiley(file, force))

  const missing = files.filter((_, i) => outcomes[i] === 'missing')
  const bundled = files.filter((_, i) => outcomes[i] !== 'missing')
  const downloaded = outcomes.filter((o) => o === 'downloaded').length
  console.log(`新下载 ${downloaded} / 已存在 ${outcomes.length - downloaded - missing.length} / 缺失 ${missing.length}`)

  const stray = (await readdir(ASSET_DIR))
    .filter((name) => name !== 'manifest.json' && !files.includes(name))
    .sort()

  const manifest = {
    source: SOURCE_URL,
    imgPath: IMG_PATH,
    smileBaseUrl: SMILE_DIR_URL,
    note: '表情图为 NGA 素材,仅个人使用、不分发(见 .scratch/app-v1/spec.md §7)',
    totals: {
      categories: categories.length,
      entries: entryCount,
      files: files.length,
      bundled: bundled.length,
      missing: missing.length,
    },
    categories: categories.map((c) => ({
      key: c.key,
      label: c.label,
      count: c.entries.length,
      filePrefixSample: c.entries[0]?.file ?? null,
    })),
    missing,
    /** 本地有、映射表里已没有的孤儿文件(官方下线了某个表情时会出现)。 */
    stray,
  }

  const written = [
    (await writeIfChanged(MANIFEST_PATH, `${JSON.stringify(manifest, null, 2)}\n`)) && MANIFEST_PATH,
    (await writeIfChanged(CORE_TABLE_PATH, renderCoreTable(categories, bundled))) && CORE_TABLE_PATH,
    (await writeIfChanged(UI_ASSETS_PATH, renderUiAssets(bundled))) && UI_ASSETS_PATH,
  ].filter(Boolean)
  console.log(written.length ? `已更新:\n  ${written.join('\n  ')}` : '生成物无变化')
  if (stray.length) console.log(`注意:${stray.length} 个孤儿文件不在映射表里 — ${stray.join(', ')}`)
  if (missing.length) process.exitCode = 1
}

await main()

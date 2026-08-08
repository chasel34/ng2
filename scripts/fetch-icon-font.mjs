#!/usr/bin/env node
/**
 * 图标字体管线:下载 Material Icons Outlined 字体 → 从设计稿扫出用到的图标名 → 生成码点表。
 *
 * 设计稿用的是 Google 的 Material Symbols Outlined(网页版可变字体),
 * 但 RN 只吃 ttf/otf 静态字体,可变字体又是 4 MB 起,所以这里用同一套图标的
 * 上一代静态版 Material Icons Outlined(331 KB,Apache-2.0)——图标名与轮廓造型一致。
 *
 * 幂等:字体已存在就跳过;生成物不含时间戳,内容不变则 git 无 diff。
 *
 *   node scripts/fetch-icon-font.mjs           # 增量
 *   node scripts/fetch-icon-font.mjs --force   # 重下字体
 *
 * 机器上配了 http_proxy 的话要加 NODE_USE_ENV_PROXY=1——node 的 fetch 默认不认代理环境变量。
 *
 * 产物:
 *   assets/fonts/MaterialIconsOutlined-Regular.otf   字体文件(随 APK 打包)
 *   src/ui/icons.generated.ts                        图标名 → 码点
 */

import { mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)))

/** google/material-design-icons 官方仓库(Apache-2.0)。 */
const REPO_RAW = 'https://raw.githubusercontent.com/google/material-design-icons/master/font'
const FONT_URL = `${REPO_RAW}/MaterialIconsOutlined-Regular.otf`
const CODEPOINTS_URL = `${REPO_RAW}/MaterialIconsOutlined-Regular.codepoints`

const DESIGN_PATH = join(ROOT, 'design', 'project', 'NGA客户端.dc.html')
const FONT_PATH = join(ROOT, 'assets', 'fonts', 'MaterialIconsOutlined-Regular.otf')
const GENERATED_PATH = join(ROOT, 'src', 'ui', 'icons.generated.ts')

const RETRIES = 3

const GENERATED_HEADER = `// 本文件由 scripts/fetch-icon-font.mjs 生成,请勿手改。
// 图标名扫自 design/project/NGA客户端.dc.html,码点取自 Material Icons Outlined(Apache-2.0)。
`

/**
 * @param {string} url
 * @returns {Promise<Buffer>}
 */
async function download(url) {
  let lastError
  for (let attempt = 1; attempt <= RETRIES; attempt += 1) {
    try {
      const res = await fetch(url)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      return Buffer.from(await res.arrayBuffer())
    } catch (error) {
      lastError = error
      if (attempt < RETRIES) await new Promise((r) => setTimeout(r, 300 * attempt))
    }
  }
  throw new Error(`下载失败 ${url}: ${lastError instanceof Error ? lastError.message : lastError}`)
}

/**
 * 设计稿里图标出现在三处:`<span class="ms">icon_name</span>`、JS 里的 `icon:'icon_name'`
 * (楼层平台图标写在 `plat:`),以及二级列表右上角按钮的 `trail:'icon_name'`
 * (LMETA 表,渲染进 `{{ listTrailIcon }}`)。扫出来的名字要拿码点表校验,过滤掉正则的误伤。
 * @param {string} html
 * @returns {string[]}
 */
function scanDesignIconNames(html) {
  const names = new Set()
  for (const [, name] of html.matchAll(/class="ms"[^>]*>([a-z0-9_]+)</g)) names.add(name)
  for (const [, name] of html.matchAll(/\b(?:icon|plat|trail):\s*'([a-z0-9_]+)'/g)) names.add(name)
  return [...names].sort()
}

/**
 * @param {string} text
 * @returns {Map<string, string>}
 */
function parseCodepoints(text) {
  const table = new Map()
  for (const line of text.trim().split('\n')) {
    const [name, hex] = line.trim().split(' ')
    if (name && hex) table.set(name, hex)
  }
  return table
}

/**
 * @param {[string, string][]} entries
 * @returns {string}
 */
function renderGenerated(entries) {
  const lines = entries.map(([name, hex]) => `  ${name}: '\\u${hex}',`).join('\n')
  return `${GENERATED_HEADER}
/**
 * 图标名 → 字体码点。字体本体是 assets/fonts/MaterialIconsOutlined-Regular.otf,
 * 由 src/ui/icon.tsx 加载后按码点渲染(不用连字,Android 上连字在部分 ROM 会失效)。
 *
 * 名字与设计稿里的 \`class="ms"\` 内容一一对应,加新图标就改设计稿后重跑脚本。
 */
export const ICON_GLYPHS = {
${lines}
} as const;

export type IconName = keyof typeof ICON_GLYPHS;
`
}

/**
 * @param {string} path
 * @param {string} content
 * @returns {Promise<boolean>}
 */
async function writeIfChanged(path, content) {
  const existing = await readFile(path, 'utf8').catch(() => null)
  if (existing === content) return false
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, content)
  return true
}

async function main() {
  const force = process.argv.slice(2).includes('--force')

  const html = await readFile(DESIGN_PATH, 'utf8')
  const wanted = scanDesignIconNames(html)
  console.log(`设计稿里扫到 ${wanted.length} 个图标名`)

  console.log(`拉取码点表 ${CODEPOINTS_URL}`)
  const table = parseCodepoints((await download(CODEPOINTS_URL)).toString('utf8'))
  const known = wanted.filter((name) => table.has(name))
  const unknown = wanted.filter((name) => !table.has(name))
  if (unknown.length) console.warn(`  ! 字体里没有,已跳过:${unknown.join(', ')}`)

  const existing = await stat(FONT_PATH).catch(() => null)
  if (force || !existing?.isFile() || existing.size === 0) {
    console.log(`下载字体 ${FONT_URL}`)
    const body = await download(FONT_URL)
    // OTF 以 'OTTO' 开头;CDN 偶尔用 200 返回错误页,别把 HTML 存成字体
    if (body.subarray(0, 4).toString('latin1') !== 'OTTO') {
      throw new Error(`响应不是 OpenType 字体(${body.length} 字节)`)
    }
    await mkdir(dirname(FONT_PATH), { recursive: true })
    await writeFile(`${FONT_PATH}.part`, body)
    await rename(`${FONT_PATH}.part`, FONT_PATH)
    await rm(`${FONT_PATH}.part`, { force: true })
    console.log(`  字体 ${(body.length / 1024).toFixed(0)} KB → ${FONT_PATH}`)
  } else {
    console.log(`字体已存在,跳过(${(existing.size / 1024).toFixed(0)} KB)`)
  }

  const entries = known.map((name) => [name, /** @type {string} */ (table.get(name))])
  const changed = await writeIfChanged(GENERATED_PATH, renderGenerated(entries))
  console.log(changed ? `已更新:${GENERATED_PATH}(${entries.length} 个图标)` : '生成物无变化')
}

if (import.meta.url === `file://${process.argv[1]}`) await main()

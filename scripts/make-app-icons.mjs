#!/usr/bin/env node
/**
 * App 图标与启动屏的生成管线:SVG 源在本文件里,渲染成 app.json 引用的那几张 PNG。
 *
 * 造型直接取自设计稿:关于屏(`isAbout`)的应用标识就是一块 primary 底、24 圆角的
 * 方块 + 白色「NG」两字;底纹取版块图标与资料页 banner 那套 135° 斜纹
 * (`repeating-linear-gradient`)。颜色全部是 src/ui/tokens.ts 里的 token 值,
 * 图标与 app 内是同一套色。
 *
 *   node scripts/make-app-icons.mjs
 *
 * 依赖机器上的 `rsvg-convert`(librsvg,`brew install librsvg`)——RN 侧没有能跑的
 * 光栅化器,而这一步一年也跑不了几次,不值得为它进一个构建期依赖。
 *
 * 产物(全部覆盖写):
 *   assets/images/icon.png                       1024  通用图标(满幅)
 *   assets/images/android-icon-background.png    1024  自适应图标 · 背景层
 *   assets/images/android-icon-foreground.png    1024  自适应图标 · 前景层(留安全区)
 *   assets/images/android-icon-monochrome.png    1024  自适应图标 · 单色层(主题图标)
 *   assets/images/splash-icon.png                 512  启动屏标识 · 浅色档(透明底)
 *   assets/images/splash-icon-dark.png            512  启动屏标识 · 深色档(透明底)
 *   assets/images/favicon.png                      48  web 预览用
 */

import { execFile } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'

const run = promisify(execFile)
const ROOT = dirname(dirname(fileURLToPath(import.meta.url)))
const OUT_DIR = join(ROOT, 'assets', 'images')

/** 与 src/ui/tokens.ts 一致。改这里之前先改 token。 */
const PRIMARY = '#14796B'
const PRIMARY_DARK = '#0F5D53'
const ON_PRIMARY = '#FFFFFF'
/** 深色档的 primary(tokens 的 darkColors.primary),启动屏深色版用它。 */
const PRIMARY_ON_DARK = '#1E9384'

/** 画布统一按 1024 建模,再由 rsvg 缩到目标尺寸。 */
const CANVAS = 1024

/**
 * 135° 斜纹。设计稿的写法是 `repeating-linear-gradient(135deg, A 0 5px, B 5px 6px)`
 * ——一条深色细线配一段底色。这里用 pattern 复刻:节距 pitch,线宽 line。
 * @param {string} id
 * @param {number} pitch
 * @param {number} line
 * @param {string} color
 * @param {number} opacity
 */
function stripePattern(id, pitch, line, color, opacity) {
  return `<pattern id="${id}" width="${pitch}" height="${pitch}" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
      <rect width="${line}" height="${pitch}" fill="${color}" opacity="${opacity}"/>
    </pattern>`
}

/**
 * 白色「NG」字标。设计稿是 700 字重的无衬线两字,这里按字宽居中。
 * @param {number} size 字号
 * @param {number} cy 基线所在的视觉中心
 */
function wordmark(size, cy, fill = ON_PRIMARY) {
  return `<text x="${CANVAS / 2}" y="${cy}" text-anchor="middle" dominant-baseline="central"
      font-family="Helvetica Neue, Helvetica, Arial, sans-serif" font-weight="700"
      font-size="${size}" fill="${fill}">NG</text>`
}

/** 满幅图标:整块 primary + 斜纹,字标占中间。 */
const ICON_SVG = `<svg xmlns="http://www.w3.org/2000/svg" width="${CANVAS}" height="${CANVAS}" viewBox="0 0 ${CANVAS} ${CANVAS}">
  <defs>${stripePattern('s', 40, 8, PRIMARY_DARK, 0.55)}</defs>
  <rect width="${CANVAS}" height="${CANVAS}" fill="${PRIMARY}"/>
  <rect width="${CANVAS}" height="${CANVAS}" fill="url(#s)"/>
  ${wordmark(400, CANVAS / 2)}
</svg>`

/** 自适应图标背景层:同一块底,不放字(前景层会盖上来)。 */
const BACKGROUND_SVG = `<svg xmlns="http://www.w3.org/2000/svg" width="${CANVAS}" height="${CANVAS}" viewBox="0 0 ${CANVAS} ${CANVAS}">
  <defs>${stripePattern('s', 40, 8, PRIMARY_DARK, 0.55)}</defs>
  <rect width="${CANVAS}" height="${CANVAS}" fill="${PRIMARY}"/>
  <rect width="${CANVAS}" height="${CANVAS}" fill="url(#s)"/>
</svg>`

/**
 * 自适应图标前景层:透明底 + 字标。
 *
 * Android 会按各家 ROM 的形状去裁这一层,只有中间 66%(直径约 676)是保证不被裁的,
 * 所以字标比满幅那版小一圈——不是留白留多了,是给圆形/水滴形的裁切留命。
 */
const FOREGROUND_SVG = `<svg xmlns="http://www.w3.org/2000/svg" width="${CANVAS}" height="${CANVAS}" viewBox="0 0 ${CANVAS} ${CANVAS}">
  ${wordmark(400, CANVAS / 2)}
</svg>`

/** 单色层(Android 13+ 主题图标):系统只认 alpha,填什么色都会被换掉。 */
const MONOCHROME_SVG = FOREGROUND_SVG

/**
 * 启动屏标识:透明底的字标,底色是页面背景(奶油 / 近黑)而不是墨绿——
 * 启动屏一收就是页面本身,底色一致才看不出那一下切换(27 票「冷启动无白屏闪烁」)。
 * 所以字标要用墨绿,深浅两档各一张。
 */
const splashSvg = (fill) => `<svg xmlns="http://www.w3.org/2000/svg" width="${CANVAS}" height="${CANVAS}" viewBox="0 0 ${CANVAS} ${CANVAS}">
  ${wordmark(560, CANVAS / 2, fill)}
</svg>`

/** @type {ReadonlyArray<{ file: string, svg: string, size: number }>} */
const TARGETS = [
  { file: 'icon.png', svg: ICON_SVG, size: 1024 },
  { file: 'android-icon-background.png', svg: BACKGROUND_SVG, size: 1024 },
  { file: 'android-icon-foreground.png', svg: FOREGROUND_SVG, size: 1024 },
  { file: 'android-icon-monochrome.png', svg: MONOCHROME_SVG, size: 1024 },
  { file: 'splash-icon.png', svg: splashSvg(PRIMARY), size: 512 },
  { file: 'splash-icon-dark.png', svg: splashSvg(PRIMARY_ON_DARK), size: 512 },
  { file: 'favicon.png', svg: ICON_SVG, size: 48 },
]

async function main() {
  try {
    await run('rsvg-convert', ['--version'])
  } catch {
    console.error('需要 rsvg-convert(brew install librsvg)')
    process.exitCode = 1
    return
  }

  await mkdir(OUT_DIR, { recursive: true })
  for (const { file, svg, size } of TARGETS) {
    const svgPath = join(OUT_DIR, `${file}.svg`)
    await writeFile(svgPath, svg)
    await run('rsvg-convert', [
      '--width',
      String(size),
      '--height',
      String(size),
      '--output',
      join(OUT_DIR, file),
      svgPath,
    ])
    // SVG 只是中间产物,产物目录里只留 PNG
    await run('rm', ['-f', svgPath])
    console.log(`  ${file} ${size}×${size}`)
  }
  console.log(`已生成 ${TARGETS.length} 张`)
}

await main()

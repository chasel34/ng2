#!/usr/bin/env node
/**
 * B2「阅读路径脚」的确定性图标导出管线。
 * release 是干净的玉色单脚；development 叠加朱红 DEV 徽标。
 * 依赖：rsvg-convert（librsvg）。
 */

import { execFile } from 'node:child_process'
import { mkdir, unlink, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'

const run = promisify(execFile)
const ROOT = dirname(dirname(fileURLToPath(import.meta.url)))
const OUT_DIR = join(ROOT, 'assets', 'images')
const CANVAS = 1024

const CREAM = '#FCF4E1'
const CREAM_DARK = '#1C1C1B'
const JADE = '#14796B'
const JADE_DARK = '#0F5D53'
const JADE_ON_DARK = '#1E9384'
const VERMILION = '#E4512E'
const WHITE = '#FFFFFF'

function svg(content, defs = '') {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${CANVAS}" height="${CANVAS}" viewBox="0 0 ${CANVAS} ${CANVAS}">
    <defs>${defs}</defs>${content}
  </svg>`
}

const paper = `<filter id="paper" x="-10%" y="-10%" width="120%" height="120%">
  <feTurbulence type="fractalNoise" baseFrequency="0.035" numOctaves="2" seed="12" result="noise"/>
  <feColorMatrix in="noise" type="saturate" values="0" result="gray"/>
  <feComponentTransfer in="gray" result="soft"><feFuncA type="table" tableValues="0 0.035"/></feComponentTransfer>
  <feBlend in="SourceGraphic" in2="soft" mode="multiply"/>
</filter>`

const shadow = `<filter id="shadow" x="-20%" y="-20%" width="140%" height="150%">
  <feDropShadow dx="0" dy="12" stdDeviation="12" flood-color="#573A21" flood-opacity="0.22"/>
</filter>`

const channelMask = `<mask id="channel-mask">
  <rect width="${CANVAS}" height="${CANVAS}" fill="white"/>
  <path d="M500 718 C468 652 493 598 526 536 C557 477 544 414 490 367 C538 393 588 445 576 518 C567 579 522 633 500 718 Z" fill="black"/>
</mask>`

const solePath = `M470 826
  C414 812 387 752 400 687
  C414 615 451 559 431 500
  C415 452 367 415 382 345
  C400 261 486 235 565 251
  C646 267 685 335 662 408
  C646 459 606 496 612 550
  C618 605 654 665 638 735
  C620 815 543 848 470 826 Z`

const toes = [
  [405, 196, 50, 68, -22],
  [485, 174, 42, 57, -8],
  [556, 188, 37, 51, 5],
  [620, 224, 32, 45, 16],
  [670, 270, 27, 39, 24],
]

function toeShapes() {
  return toes
    .map(([cx, cy, rx, ry, rotate]) =>
      `<ellipse cx="${cx}" cy="${cy}" rx="${rx}" ry="${ry}" transform="rotate(${rotate} ${cx} ${cy})"/>`,
    )
    .join('')
}

function footprint(fill, accent = VERMILION, withShadow = true) {
  const filter = withShadow ? ' filter="url(#shadow)"' : ''
  return `<g fill="${fill}"${filter}>
      <path d="${solePath}" mask="url(#channel-mask)"/>
      ${toeShapes()}
    </g>
    <circle cx="518" cy="558" r="28" fill="${CREAM}"/>
    <circle cx="518" cy="558" r="18" fill="${accent}"/>`
}

function devBadge(monochrome = false) {
  const fill = monochrome ? '#000000' : VERMILION
  const text = monochrome
    ? '<path d="M730 706 h22 v70 h-22 z M730 706 h42 c30 0 47 14 47 35 s-17 35-47 35 h-20 v-18 h18 c17 0 25-6 25-17 s-8-17-25-17 h-18 v-18 h-18 z" fill="#000000"/>'
    : `<text x="768" y="746" text-anchor="middle" dominant-baseline="central"
        font-family="Helvetica Neue, Helvetica, Arial, sans-serif" font-weight="800"
        font-size="44" letter-spacing="1" fill="${WHITE}">DEV</text>`
  return `<g>
    <circle cx="768" cy="742" r="82" fill="${CREAM}"/>
    <circle cx="768" cy="742" r="68" fill="${fill}"/>
    ${text}
  </g>`
}

function background(fill = CREAM) {
  return `<rect width="${CANVAS}" height="${CANVAS}" fill="${fill}" filter="url(#paper)"/>`
}

function fullIcon(development) {
  return svg(`${background()}${footprint(JADE)}${development ? devBadge() : ''}`, `${paper}${shadow}${channelMask}`)
}

function foreground(development) {
  return svg(`${footprint(JADE)}${development ? devBadge() : ''}`, `${shadow}${channelMask}`)
}

function monochrome(development) {
  return svg(
    `<g fill="#000000"><path d="${solePath}" mask="url(#channel-mask)"/>${toeShapes()}</g>${development ? devBadge(true) : ''}`,
    channelMask,
  )
}

function splash(fill, development) {
  return svg(
    `<g transform="translate(102 102) scale(0.8) translate(-102 -102)">${footprint(fill, VERMILION, false)}${development ? devBadge() : ''}</g>`,
    channelMask,
  )
}

const targets = [
  { file: 'icon.png', source: fullIcon(false), size: 1024 },
  { file: 'android-icon-background.png', source: svg(background(), paper), size: 1024 },
  { file: 'android-icon-foreground.png', source: foreground(false), size: 1024 },
  { file: 'android-icon-monochrome.png', source: monochrome(false), size: 1024 },
  { file: 'splash-icon.png', source: splash(JADE, false), size: 512 },
  { file: 'splash-icon-dark.png', source: splash(JADE_ON_DARK, false), size: 512 },
  { file: 'favicon.png', source: fullIcon(false), size: 48 },
  { file: 'icon-dev.png', source: fullIcon(true), size: 1024 },
  { file: 'android-icon-background-dev.png', source: svg(background(), paper), size: 1024 },
  { file: 'android-icon-foreground-dev.png', source: foreground(true), size: 1024 },
  { file: 'android-icon-monochrome-dev.png', source: monochrome(true), size: 1024 },
  { file: 'splash-icon-dev.png', source: splash(JADE, true), size: 512 },
  { file: 'splash-icon-dark-dev.png', source: splash(JADE_ON_DARK, true), size: 512 },
  { file: 'favicon-dev.png', source: fullIcon(true), size: 48 },
]

async function main() {
  try {
    await run('rsvg-convert', ['--version'])
  } catch {
    console.error('需要 rsvg-convert（brew install librsvg）')
    process.exitCode = 1
    return
  }

  await mkdir(OUT_DIR, { recursive: true })
  for (const target of targets) {
    const svgPath = join(OUT_DIR, `${target.file}.svg`)
    await writeFile(svgPath, target.source)
    await run('rsvg-convert', [
      '--width',
      String(target.size),
      '--height',
      String(target.size),
      '--output',
      join(OUT_DIR, target.file),
      svgPath,
    ])
    await unlink(svgPath)
    console.log(`  ${target.file} ${target.size}×${target.size}`)
  }

  const manifest = {
    sourceConcept: 'assets/images/concepts/jade-foot-dialogue.png',
    generator: 'scripts/make-app-icons.mjs',
    palette: {
      cream: CREAM,
      creamDark: CREAM_DARK,
      jade: JADE,
      jadeDark: JADE_DARK,
      jadeOnDark: JADE_ON_DARK,
      vermilion: VERMILION,
    },
    variants: {
      release: targets.filter(({ file }) => !file.includes('-dev')).map(({ file, size }) => ({ file, size })),
      development: targets.filter(({ file }) => file.includes('-dev')).map(({ file, size }) => ({ file, size })),
    },
  }
  await writeFile(join(OUT_DIR, 'icon-manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`)
  console.log(`已生成 ${targets.length} 张图标与 icon-manifest.json`)
}

await main()

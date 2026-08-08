import { describe, expect, it } from 'vitest'

import { DEFAULT_NGA_HOST, NGA_HOSTS } from '../net/constants'
import {
  APPEARANCE_SLIDERS,
  AVATAR_BASE_SIZE,
  DEFAULT_SETTINGS,
  SMILEY_BASE_HEIGHT,
  avatarSizeOf,
  clampSlider,
  formatSliderValue,
  parseSettings,
  sliderRatio,
  sliderValueAt,
  smileyHeightOf,
} from './settings'

const specOf = (key: string) => {
  const spec = APPEARANCE_SLIDERS.find((item) => item.key === key)
  if (spec === undefined) throw new Error(`没有这根滑杆：${key}`)
  return spec
}

describe('默认值', () => {
  it('默认域名就是 API 文档 §0.1 的首选域名', () => {
    expect(DEFAULT_SETTINGS.host).toBe(DEFAULT_NGA_HOST)
  })

  it('五根滑杆的默认值都落在自己的量程内、且正好是步长的整数倍', () => {
    for (const spec of APPEARANCE_SLIDERS) {
      const value = DEFAULT_SETTINGS.appearance[spec.key]
      expect(value).toBeGreaterThanOrEqual(spec.min)
      expect(value).toBeLessThanOrEqual(spec.max)
      expect(clampSlider(spec, value)).toBe(value)
    }
  })

  it('头像与表情的默认百分比换算回 tokens 里的现行尺寸', () => {
    expect(avatarSizeOf(DEFAULT_SETTINGS.appearance.avatarScale)).toBe(AVATAR_BASE_SIZE)
    expect(smileyHeightOf(DEFAULT_SETTINGS.appearance.smileyScale)).toBe(24)
    expect(SMILEY_BASE_HEIGHT).toBe(16)
  })
})

describe('clampSlider', () => {
  const lineHeight = specOf('bodyLineHeight')

  it('夹在量程内', () => {
    expect(clampSlider(lineHeight, 0.4)).toBe(lineHeight.min)
    expect(clampSlider(lineHeight, 9)).toBe(lineHeight.max)
  })

  it('量化到步长', () => {
    expect(clampSlider(specOf('smileyScale'), 143)).toBe(140)
    expect(clampSlider(specOf('bodyFontSize'), 15.3)).toBe(15.5)
  })

  // 0.02 步长连加会攒出 1.7000000000000002，气泡上就露出来了
  it('浮点步长不会攒出长尾小数', () => {
    let value = lineHeight.min
    for (let i = 0; i < 20; i += 1) value = clampSlider(lineHeight, value + lineHeight.step)
    expect(value).toBe(1.7)
  })

  it('拿到 NaN 时回落到默认值', () => {
    expect(clampSlider(lineHeight, Number.NaN)).toBe(DEFAULT_SETTINGS.appearance.bodyLineHeight)
  })
})

describe('滑杆比例', () => {
  const avatar = specOf('avatarScale')

  it('比例与值可以来回换算', () => {
    expect(sliderRatio(avatar, avatar.min)).toBe(0)
    expect(sliderRatio(avatar, avatar.max)).toBe(1)
    expect(sliderValueAt(avatar, 0.5)).toBe(clampSlider(avatar, (avatar.min + avatar.max) / 2))
  })

  it('拖出轨道两端的比例被夹住', () => {
    expect(sliderRatio(avatar, 999)).toBe(1)
    expect(sliderValueAt(avatar, -3)).toBe(avatar.min)
  })
})

describe('formatSliderValue', () => {
  it('整数档不带小数点，百分比档带 %', () => {
    expect(formatSliderValue(specOf('listFontSize'), 17)).toBe('17')
    expect(formatSliderValue(specOf('avatarScale'), 104)).toBe('104%')
    expect(formatSliderValue(specOf('bodyFontSize'), 15.5)).toBe('15.5')
    expect(formatSliderValue(specOf('bodyLineHeight'), 1.7)).toBe('1.70')
  })
})

describe('parseSettings', () => {
  it('存档不是对象时整份回落', () => {
    expect(parseSettings(null)).toEqual(DEFAULT_SETTINGS)
    expect(parseSettings('{}')).toEqual(DEFAULT_SETTINGS)
  })

  it('认得的域名留下，不认得的换回默认域名', () => {
    expect(parseSettings({ host: NGA_HOSTS[3] }).host).toBe(NGA_HOSTS[3])
    expect(parseSettings({ host: 'https://evil.example' }).host).toBe(DEFAULT_NGA_HOST)
  })

  // 加了新设置项的版本读旧存档，老项不能被整份默认值盖掉
  it('只认得一半的存档里，认得的那一半保留', () => {
    const parsed = parseSettings({ solidBackground: true, imageQuality: 'thumbnail' })
    expect(parsed.solidBackground).toBe(true)
    expect(parsed.imageQuality).toBe('thumbnail')
    expect(parsed.showSignature).toBe(DEFAULT_SETTINGS.showSignature)
  })

  it('类型不对的项各自回落，不牵连别项', () => {
    const parsed = parseSettings({
      wifiOnlyImages: 'yes',
      themeStyle: 'rainbow',
      keepScreenOn: true,
      appearance: { bodyFontSize: '大', avatarScale: 132 },
    })
    expect(parsed.wifiOnlyImages).toBe(DEFAULT_SETTINGS.wifiOnlyImages)
    expect(parsed.themeStyle).toBe(DEFAULT_SETTINGS.themeStyle)
    expect(parsed.keepScreenOn).toBe(true)
    expect(parsed.appearance.bodyFontSize).toBe(DEFAULT_SETTINGS.appearance.bodyFontSize)
    expect(parsed.appearance.avatarScale).toBe(132)
  })

  it('越界的滑杆值被夹回量程', () => {
    expect(parseSettings({ appearance: { listFontSize: 999 } }).appearance.listFontSize).toBe(
      specOf('listFontSize').max,
    )
  })

  it('一趟存读之后设置表原样', () => {
    const settings = {
      ...DEFAULT_SETTINGS,
      host: NGA_HOSTS[1],
      themeStyle: 'plain' as const,
      appearance: { ...DEFAULT_SETTINGS.appearance, bodyLineHeight: 1.9 },
    }
    expect(parseSettings(JSON.parse(JSON.stringify(settings)))).toEqual(settings)
  })
})

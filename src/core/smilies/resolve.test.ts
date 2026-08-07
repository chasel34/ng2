import { describe, expect, it } from 'vitest'
import { SMILEY_BASE_URL, SMILEY_CATEGORIES } from './table.generated'
import { resolveSmiley } from './resolve'

/** 官方表里应当稳定存在的六套具名分类。 */
const NAMED_CATEGORIES = ['ac', 'a2', 'ng', 'pst', 'dt', 'pg'] as const

/** @returns 该套系第一条 `[名称, 文件名]` */
function firstEntry(key: string): readonly [string, string] {
  const category = SMILEY_CATEGORIES.find((c) => c.key === key)
  if (!category?.entries[0]) throw new Error(`映射表里没有套系 ${key}`)
  return category.entries[0]
}

describe('resolveSmiley', () => {
  describe('六套具名分类', () => {
    it.each(NAMED_CATEGORIES)('%s 套的表情映射到内置图片', (key) => {
      const [name, file] = firstEntry(key)
      expect(resolveSmiley(`${key}:${name}`)).toMatchObject({
        kind: 'bundled',
        category: key,
        name,
        file,
        remoteUrl: `${SMILEY_BASE_URL}/${file}`,
      })
    })

    // 上面的用例是照公式算的,这里钉一个字面量,免得公式改错了也测不出来。
    it('远程 URL 就是 CDN 上表情图的真实地址', () => {
      expect(resolveSmiley('pst:举手')).toMatchObject({
        remoteUrl: 'https://img4.nga.cn/ngabbs/post/smile/pt00.png',
      })
    })

    it('每套都带上官方中文名,供表情面板分组', () => {
      const [name] = firstEntry('pg')
      expect(resolveSmiley(`pg:${name}`)).toMatchObject({ label: '企鹅' })
    })

    it('pst 套的文件名前缀是 pt 而不是 pst', () => {
      const resolved = resolveSmiley('pst:举手')
      expect(resolved).toMatchObject({ kind: 'bundled', file: 'pt00.png' })
    })

    it('整张表 265 条都能查到', () => {
      const unresolved = SMILEY_CATEGORIES.flatMap((category) =>
        category.entries
          .map(([name]) => resolveSmiley(category.key === '0' ? name : `${category.key}:${name}`))
          .filter((resolved) => resolved.kind === 'unresolved'),
      )
      expect(unresolved).toEqual([])
    })
  })

  describe('数字默认套', () => {
    it('[s:1] 走默认套', () => {
      expect(resolveSmiley('1')).toMatchObject({ kind: 'bundled', category: '0', file: 'smile.gif' })
    })

    it('省略分类的 [s::名称] 也走默认套', () => {
      expect(resolveSmiley(':1')).toMatchObject({ kind: 'bundled', category: '0', file: 'smile.gif' })
    })

    it('[s:0] 查不到 —— 官方 parseInt 判定为假,再当分类名查也没有名称', () => {
      expect(resolveSmiley('0')).toEqual({ kind: 'unresolved', raw: '[s:0]' })
    })

    it('默认套里不存在的编号查不到', () => {
      expect(resolveSmiley('999')).toEqual({ kind: 'unresolved', raw: '[s:999]' })
    })
  })

  describe('未知表情', () => {
    it('分类存在但名称不存在 → 原文标记', () => {
      expect(resolveSmiley('ac:根本没有这个')).toEqual({ kind: 'unresolved', raw: '[s:ac:根本没有这个]' })
    })

    it('分类不存在 → 原文标记', () => {
      expect(resolveSmiley('zz:笑')).toEqual({ kind: 'unresolved', raw: '[s:zz:笑]' })
    })

    it('只有分类没有名称 → 原文标记', () => {
      expect(resolveSmiley('ac')).toEqual({ kind: 'unresolved', raw: '[s:ac]' })
    })

    it('空串 → 原文标记', () => {
      expect(resolveSmiley('')).toEqual({ kind: 'unresolved', raw: '[s:]' })
    })
  })

  describe('未内置的图片回退远程 URL', () => {
    it('映射表里有、但没随包下下来的表情走 CDN', () => {
      const [name, file] = firstEntry('ac')
      const resolved = resolveSmiley(`ac:${name}`, { bundledFiles: new Set() })
      expect(resolved).toMatchObject({
        kind: 'remote',
        category: 'ac',
        name,
        file,
        remoteUrl: `${SMILEY_BASE_URL}/${file}`,
      })
    })

    it('查不到的仍然是原文标记,不会编出远程 URL', () => {
      expect(resolveSmiley('ac:不存在', { bundledFiles: new Set() })).toEqual({
        kind: 'unresolved',
        raw: '[s:ac:不存在]',
      })
    })
  })
})

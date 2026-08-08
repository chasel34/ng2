import { describe, expect, it } from 'vitest'

import {
  createFilterRule,
  filterMatchText,
  matchFilterRules,
  normalizeRuleValue,
  removeFilterRule,
  topicCategories,
  upsertFilterRule,
  validateFilterRule,
  type FilterRule,
} from './filters'

const rule = (over: Partial<FilterRule> & Pick<FilterRule, 'kind' | 'value'>): FilterRule => ({
  id: `${over.origin ?? 'local'}:${over.kind}:${over.value.toLowerCase()}`,
  origin: 'local',
  regex: false,
  ...over,
})

describe('matchFilterRules 关键词', () => {
  it('普通关键词按子串命中，标题与正文都算', () => {
    const rules = [rule({ kind: 'keyword', value: '内部消息' })]

    expect(matchFilterRules(rules, { title: '爆个内部消息' })?.value).toBe('内部消息')
    expect(matchFilterRules(rules, { title: '闲聊', content: '这是内部消息，别外传' })).toBeDefined()
    expect(matchFilterRules(rules, { title: '闲聊', content: '没什么可说的' })).toBeUndefined()
  })

  it('普通关键词大小写不敏感', () => {
    const rules = [rule({ kind: 'keyword', value: 'Steam' })]
    expect(matchFilterRules(rules, { title: 'STEAM 夏促' })).toBeDefined()
    expect(matchFilterRules(rules, { title: 'steam 夏促' })).toBeDefined()
  })

  it('正则关键词按正则命中，普通规则则把元字符当字面量', () => {
    const asRegex = [rule({ kind: 'keyword', value: '^\\[水\\]', regex: true })]
    const asPlain = [rule({ kind: 'keyword', value: '^\\[水\\]' })]

    expect(matchFilterRules(asRegex, { title: '[水]今天吃什么' })).toBeDefined()
    // 正则锚在开头：分类不在标题最前面就不该命中
    expect(matchFilterRules(asRegex, { title: '闲聊 [水]今天吃什么' })).toBeUndefined()
    // 同一个串当普通关键词时是在找字面量 `^\[水\]`，标题里没有
    expect(matchFilterRules(asPlain, { title: '[水]今天吃什么' })).toBeUndefined()
  })

  it('正则不带 g：同一条规则连判多次结果稳定（没有 lastIndex 残留）', () => {
    const rules = [rule({ kind: 'keyword', value: '搬运', regex: true })]
    for (let i = 0; i < 3; i++) {
      expect(matchFilterRules(rules, { title: '搬运工又来了' })).toBeDefined()
    }
  })

  it('非法正则不抛也不命中，其余规则照常生效', () => {
    const rules = [
      rule({ kind: 'keyword', value: '([未闭合', regex: true }),
      rule({ kind: 'keyword', value: '广告' }),
    ]

    expect(() => matchFilterRules(rules, { title: '([未闭合 的标题' })).not.toThrow()
    expect(matchFilterRules(rules, { title: '([未闭合 的标题' })).toBeUndefined()
    expect(matchFilterRules(rules, { title: '这是广告' })?.value).toBe('广告')
  })
})

describe('matchFilterRules 用户与分类', () => {
  it('用户规则按名字精确比对，不做子串', () => {
    const rules = [rule({ kind: 'user', value: 'xtl150ok' })]

    expect(matchFilterRules(rules, { author: 'XTL150OK' })).toBeDefined()
    expect(matchFilterRules(rules, { author: 'xtl150ok2' })).toBeUndefined()
    expect(matchFilterRules(rules, { title: 'xtl150ok 说得对' })).toBeUndefined()
  })

  it('带 uid 的用户规则以 uid 为准：改了名照样命中', () => {
    const rules = [rule({ kind: 'user', value: '旧名字', uid: 42 })]

    expect(matchFilterRules(rules, { author: '新名字', authorId: 42 })).toBeDefined()
    expect(matchFilterRules(rules, { author: '旧名字' })).toBeDefined()
    expect(matchFilterRules(rules, { author: '别人', authorId: 43 })).toBeUndefined()
  })

  it('分类规则比对标题里的方括号标签，正文里出现同名词不算', () => {
    const rules = [rule({ kind: 'category', value: '转帖' })]

    expect(matchFilterRules(rules, { title: '[转帖]某地新闻' })).toBeDefined()
    expect(matchFilterRules(rules, { title: '某地新闻', content: '转帖自隔壁' })).toBeUndefined()
    // 楼层没有标题，分类规则对它天然不生效
    expect(matchFilterRules(rules, { content: '[转帖]' })).toBeUndefined()
  })

  it('命中多条时返回排在前面的那条（本地规则排在官方之前）', () => {
    const rules = [
      rule({ kind: 'keyword', value: '广告' }),
      rule({ kind: 'keyword', value: '内部消息', origin: 'official' }),
    ]
    expect(matchFilterRules(rules, { title: '内部消息也是广告' })?.origin).toBe('local')
  })

  it('空规则表与空内容都不命中', () => {
    expect(matchFilterRules([], { title: '随便什么' })).toBeUndefined()
    expect(matchFilterRules([rule({ kind: 'keyword', value: '  ' })], { title: 'x' })).toBeUndefined()
  })
})

describe('topicCategories', () => {
  it('取出标题里的方括号标签', () => {
    expect(topicCategories('[讨论][转帖]显卡涨价')).toEqual(['讨论', '转帖'])
    expect(topicCategories('没有标签的标题')).toEqual([])
    expect(topicCategories('[]空标签')).toEqual([])
  })
})

describe('validateFilterRule', () => {
  it('空内容有提示', () => {
    expect(validateFilterRule({ kind: 'keyword', value: '   ' })).toBe('请输入要屏蔽的关键词')
    expect(validateFilterRule({ kind: 'user', value: '' })).toBe('请输入要屏蔽的用户')
  })

  it('非法正则就地报错，合法的放行', () => {
    expect(validateFilterRule({ kind: 'keyword', value: '([', regex: true })).toMatch(
      /^正则表达式不合法：/,
    )
    expect(validateFilterRule({ kind: 'keyword', value: '^\\[水\\]', regex: true })).toBeUndefined()
    // 同一个串不开正则时只是普通关键词，不该被正则语法拦下
    expect(validateFilterRule({ kind: 'keyword', value: '([' })).toBeUndefined()
  })
})

describe('规则表增删', () => {
  it('同一条规则重复添加是覆盖并挪到最前，不并存', () => {
    const first = createFilterRule({ kind: 'keyword', value: '广告' }, 100)
    const other = createFilterRule({ kind: 'user', value: '张三' }, 101)
    const again = createFilterRule({ kind: 'keyword', value: ' 广告 ' }, 102)

    const rules = upsertFilterRule(upsertFilterRule([first], other), again)
    expect(rules).toHaveLength(2)
    expect(rules[0]?.createdAt).toBe(102)
    expect(rules[0]?.value).toBe('广告')
  })

  it('普通规则压内部连续空白，正则原样保留', () => {
    expect(normalizeRuleValue('  张 \t 三  ')).toBe('张 三')
    expect(normalizeRuleValue('a\\s{2,}b', true)).toBe('a\\s{2,}b')
  })

  it('删除按 id，删不存在的是 no-op', () => {
    const one = createFilterRule({ kind: 'keyword', value: '广告' }, 100)
    expect(removeFilterRule([one], one.id)).toEqual([])
    expect(removeFilterRule([one], 'local:keyword:别的')).toEqual([one])
  })
})

describe('filterMatchText', () => {
  it('按类型给折叠行的灰字', () => {
    expect(filterMatchText(rule({ kind: 'user', value: '张三' }))).toBe('已屏蔽 张三 的楼层')
    expect(filterMatchText(rule({ kind: 'keyword', value: '广告' }))).toBe('已屏蔽含「广告」的楼层')
    expect(filterMatchText(rule({ kind: 'category', value: '转帖' }))).toBe(
      '已屏蔽分类「转帖」的楼层',
    )
  })
})

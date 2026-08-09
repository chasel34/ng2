import { describe, expect, it } from 'vitest'

import { extractServerError } from './errors'
import { stripServerHtml } from './server-text'

/**
 * M3 真机验收（2026-08-09）在 51 待审核主题上抓到的原文：说明区把标签连属性一起
 * 逐字显示了五行。这条就是缺陷 2 的样本。
 */
const AWAITING_REVIEW =
  "51:帖子正等待审核;<br/><a href='/nuke.php?func=account&amp;adminmode=1' " +
  "style='color:dimgray' target='_blank'>[查看所需的权限/条件]</a>"

describe('stripServerHtml · 服务端说明里的 HTML', () => {
  it('51 待审核：`<br/>` 变换行，`<a>` 只留里面那句话', () => {
    expect(stripServerHtml(AWAITING_REVIEW)).toBe('51:帖子正等待审核;\n[查看所需的权限/条件]')
  })

  it('`<br>` 的三种写法一视同仁', () => {
    expect(stripServerHtml('一<br>二<br/>三<br />四')).toBe('一\n二\n三\n四')
  })

  it('其余标签剥掉但留下文字', () => {
    expect(stripServerHtml('<b>权限不足</b>：<span style="color:red">需要 5 级</span>')).toBe(
      '权限不足：需要 5 级',
    )
  })

  it('解 HTML 实体（与正文同一套解码）', () => {
    expect(stripServerHtml('你没有权限&#39;访问&#39;&nbsp;这个版面&amp;合集')).toBe(
      "你没有权限'访问' 这个版面&合集",
    )
  })

  it('拿尖括号当引号的标题不当成标签吃掉', () => {
    expect(stripServerHtml('找不到主题《<第六感>》')).toBe('找不到主题《<第六感>》')
  })

  it('剥出来的连片空行压成一个，首尾空白去掉', () => {
    expect(stripServerHtml('<div>上</div><br/><br/><br/>下<br/>  ')).toBe('上\n\n下')
  })

  it('本来就是纯文本的原样返回', () => {
    expect(stripServerHtml('您没有浏览该版面的权限')).toBe('您没有浏览该版面的权限')
  })
})

describe('extractServerError · 剥标签发生在抽取口', () => {
  it('对象形态的 error 逐条剥（错误页、诊断日志拿到的都是人话）', () => {
    expect(extractServerError({ error: { code: 51, '0': AWAITING_REVIEW } })).toEqual({
      code: 51,
      message: '51:帖子正等待审核;\n[查看所需的权限/条件]',
    })
  })

  it('字符串形态的 error 同样剥', () => {
    expect(extractServerError({ error: '<b>未登录</b>' })).toEqual({
      code: '?',
      message: '未登录',
    })
  })

  it('整条说明全是标签时仍算错误，不当成成功放过去', () => {
    expect(extractServerError({ error: '<br/>' })).toEqual({
      code: '?',
      message: '未知错误（code=?）',
    })
  })
})

import { describe, expect, it } from 'vitest'

import { ngaLinkPath, parseNgaLink, type NgaLink, type NgaLinkFailure } from './deep-link'

/** 解出来的目标，解不出来就让断言直接失败（省得每条用例都写一遍 ok 判断）。 */
function link(input: string): NgaLink {
  const result = parseNgaLink(input)
  if (!result.ok) throw new Error(`应当解析成功，实际是 ${result.reason}：${input}`)
  return result.link
}

function reason(input: string): NgaLinkFailure | 'ok' {
  const result = parseNgaLink(input)
  return result.ok ? 'ok' : result.reason
}

describe('parseNgaLink：read.php', () => {
  it('只有 tid 时就只给 tid', () => {
    expect(link('https://bbs.nga.cn/read.php?tid=45150945')).toEqual({
      kind: 'topic',
      tid: 45150945,
    })
  })

  it('认 page / pid / fav 四件套', () => {
    expect(link('https://bbs.nga.cn/read.php?tid=45150945&page=3&pid=880123456&fav=1a2b3c')).toEqual(
      { kind: 'topic', tid: 45150945, page: 3, pid: 880123456, fav: '1a2b3c' },
    )
  })

  it('参数顺序无所谓，认不得的参数（authorid/opt）跳过', () => {
    expect(link('https://ngabbs.com/read.php?opt=512&page=2&authorid=41417929&tid=45150945')).toEqual(
      { kind: 'topic', tid: 45150945, page: 2 },
    )
  })

  it('query 里没 pid 时按网页锚点 #pid<pid>Anchor 定位', () => {
    expect(link('https://bbs.nga.cn/read.php?tid=45150945&page=2#pid880123456Anchor')).toEqual({
      kind: 'topic',
      tid: 45150945,
      page: 2,
      pid: 880123456,
    })
  })

  it('query 的 pid 压过锚点', () => {
    expect(link('https://bbs.nga.cn/read.php?tid=1&pid=222#pid333Anchor')).toEqual({
      kind: 'topic',
      tid: 1,
      pid: 222,
    })
  })

  it('page / pid 不是正整数就当没带（tid 还在，照样能开）', () => {
    expect(link('https://bbs.nga.cn/read.php?tid=1&page=0&pid=-3')).toEqual({ kind: 'topic', tid: 1 })
    expect(link('https://bbs.nga.cn/read.php?tid=1&page=abc')).toEqual({ kind: 'topic', tid: 1 })
  })

  it('fav 码不是十六进制串就丢掉，不往下带', () => {
    expect(link('https://bbs.nga.cn/read.php?tid=1&fav=zzzz')).toEqual({ kind: 'topic', tid: 1 })
    expect(link('https://bbs.nga.cn/read.php?tid=1&fav=DEADBEEF')).toEqual({
      kind: 'topic',
      tid: 1,
      fav: 'DEADBEEF',
    })
  })

  it('复制自网页正文的 &amp; 也能拆开', () => {
    expect(link('https://bbs.nga.cn/read.php?tid=1&amp;page=2')).toEqual({
      kind: 'topic',
      tid: 1,
      page: 2,
    })
  })

  it('没有 tid 就明说缺什么——只给 pid 的引用链接也走这条', () => {
    expect(reason('https://bbs.nga.cn/read.php?pid=880123456')).toBe('missing-id')
    expect(reason('https://bbs.nga.cn/read.php')).toBe('missing-id')
    expect(reason('https://bbs.nga.cn/read.php?tid=0')).toBe('missing-id')
    expect(reason('https://bbs.nga.cn/read.php?tid=abc')).toBe('missing-id')
  })
})

describe('parseNgaLink：thread.php', () => {
  it('fid 开普通版块', () => {
    expect(link('https://bbs.nga.cn/thread.php?fid=650')).toEqual({
      kind: 'board',
      id: 650,
      boardKind: 'board',
    })
  })

  it('stid 开合集', () => {
    expect(link('https://bbs.nga.cn/thread.php?stid=32871539')).toEqual({
      kind: 'board',
      id: 32871539,
      boardKind: 'collection',
    })
  })

  it('两个都在时 stid 优先（CONTEXT.md：互斥且 stid 优先）', () => {
    expect(link('https://bbs.nga.cn/thread.php?fid=650&stid=32871539')).toEqual({
      kind: 'board',
      id: 32871539,
      boardKind: 'collection',
    })
  })

  it('fid 可以是负数', () => {
    expect(link('https://bbs.nga.cn/thread.php?fid=-7')).toEqual({
      kind: 'board',
      id: -7,
      boardKind: 'board',
    })
  })

  it('stid 解不出来时回落到 fid', () => {
    expect(link('https://bbs.nga.cn/thread.php?stid=0&fid=650')).toEqual({
      kind: 'board',
      id: 650,
      boardKind: 'board',
    })
  })

  it('page / key / favor 这些场景参数不影响落地页，也不当成缺 id', () => {
    expect(link('https://bbs.nga.cn/thread.php?fid=650&page=4&order_by=postdatedesc')).toEqual({
      kind: 'board',
      id: 650,
      boardKind: 'board',
    })
  })

  it('fid 与 stid 都没有就是缺 id', () => {
    expect(reason('https://bbs.nga.cn/thread.php')).toBe('missing-id')
    expect(reason('https://bbs.nga.cn/thread.php?favor=1')).toBe('missing-id')
    expect(reason('https://bbs.nga.cn/thread.php?fid=0')).toBe('missing-id')
  })
})

describe('parseNgaLink：scheme 与域名', () => {
  it('四个官方域名（含 http 与 www.）都接管', () => {
    for (const origin of [
      'https://bbs.nga.cn',
      'http://ngabbs.com',
      'https://bbs.ngacn.cc',
      'http://nga.178.com',
      'https://nga.donews.com',
      'https://www.bbs.nga.cn',
      'https://bbs.nga.cn:443',
    ]) {
      expect(link(`${origin}/read.php?tid=1`)).toEqual({ kind: 'topic', tid: 1 })
    }
  })

  it('自定义 scheme 同构：端点在 host 位或路径位都行', () => {
    expect(link('ng2://read.php?tid=1&page=2')).toEqual({ kind: 'topic', tid: 1, page: 2 })
    expect(link('ng2:///thread.php?fid=650')).toEqual({
      kind: 'board',
      id: 650,
      boardKind: 'board',
    })
    expect(link('ng2://bbs.nga.cn/read.php?tid=1')).toEqual({ kind: 'topic', tid: 1 })
  })

  it('手粘时省了 scheme 也认', () => {
    expect(link('bbs.nga.cn/read.php?tid=1')).toEqual({ kind: 'topic', tid: 1 })
    expect(link('read.php?tid=1')).toEqual({ kind: 'topic', tid: 1 })
    expect(link('/thread.php?fid=650')).toEqual({ kind: 'board', id: 650, boardKind: 'board' })
    expect(link('  https://bbs.nga.cn/read.php?tid=1  ')).toEqual({ kind: 'topic', tid: 1 })
  })

  it('别人家的域名不接管——哪怕路径长得一模一样', () => {
    expect(reason('https://evil.example.com/read.php?tid=1')).toBe('foreign-host')
    expect(reason('https://nga.cn.evil.com/read.php?tid=1')).toBe('foreign-host')
    expect(reason('evil.example.com/read.php?tid=1')).toBe('foreign-host')
    // 图片域名也在 NGA 名下，但不是论坛入口
    expect(reason('https://img.nga.cn/read.php?tid=1')).toBe('foreign-host')
  })

  it('别的 scheme 一律不认', () => {
    expect(reason('ftp://bbs.nga.cn/read.php?tid=1')).toBe('unsupported-scheme')
    expect(reason('javascript://read.php?tid=1')).toBe('unsupported-scheme')
  })

  it('官方域名下的其他端点不接管（交给系统浏览器）', () => {
    expect(reason('https://bbs.nga.cn/nuke.php?func=ucp&uid=1')).toBe('unsupported-path')
    expect(reason('https://bbs.nga.cn/')).toBe('unsupported-path')
    expect(reason('https://bbs.nga.cn')).toBe('unsupported-path')
    // 冷启动时 expo-router 递进来的就是这个（Linking.createURL('/')）
    expect(reason('ng2:///')).toBe('unsupported-path')
  })

  it('空串与纯空白是 empty，不是别的错', () => {
    expect(reason('')).toBe('empty')
    expect(reason('   ')).toBe('empty')
    // 粘进来的不是链接时，host 位上那坨东西认不出是官方域名，就按「不是 NGA 链接」说
    expect(reason('随便一句话')).toBe('foreign-host')
  })
})

describe('ngaLinkPath', () => {
  it('主题只拼带上的参数', () => {
    expect(ngaLinkPath({ kind: 'topic', tid: 45150945 })).toBe('/topic/45150945')
    expect(ngaLinkPath({ kind: 'topic', tid: 1, page: 3, pid: 22, fav: 'ab12' })).toBe(
      '/topic/1?page=3&pid=22&fav=ab12',
    )
    expect(ngaLinkPath({ kind: 'topic', tid: 1, pid: 22 })).toBe('/topic/1?pid=22')
  })

  it('版块带上 kind，列表页据此区分合集与普通版块', () => {
    expect(ngaLinkPath({ kind: 'board', id: 650, boardKind: 'board' })).toBe('/board/650?kind=board')
    expect(ngaLinkPath({ kind: 'board', id: 32871539, boardKind: 'collection' })).toBe(
      '/board/32871539?kind=collection',
    )
  })

  it('拼出来的路径再解一遍不会串味（read.php ↔ 路由参数同构）', () => {
    expect(ngaLinkPath(link('https://bbs.nga.cn/read.php?tid=1&page=2#pid333Anchor'))).toBe(
      '/topic/1?page=2&pid=333',
    )
  })
})

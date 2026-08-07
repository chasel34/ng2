import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { createNgaFetcher } from './fetcher'
import { gbk } from './query'
import { createFetchTransport } from './transport'

/**
 * 联网冒烟：真的打一次 NGA。**默认不跑**——需要同时满足
 * 1) 仓库根目录的 `.env.local` 里有 NGA_UID / NGA_CID（gitignored）；
 * 2) 显式开开关：`NGA_INTEGRATION=1 pnpm test`。
 */

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '../../..')

function loadLocalEnv(): Record<string, string> {
  try {
    const text = readFileSync(join(repoRoot, '.env.local'), 'utf8')
    const env: Record<string, string> = {}
    for (const line of text.split('\n')) {
      const match = /^\s*([A-Z_][A-Z0-9_]*)\s*=\s*(.*?)\s*$/i.exec(line)
      if (match) env[match[1]!] = match[2]!.replace(/^["']|["']$/g, '')
    }
    return env
  } catch {
    return {}
  }
}

const env = loadLocalEnv()
const credentials =
  env.NGA_UID && env.NGA_CID ? { uid: env.NGA_UID, token: env.NGA_CID } : null
const enabled = process.env.NGA_INTEGRATION === '1' && credentials !== null

describe.skipIf(!enabled)('联网冒烟（NGA_INTEGRATION=1 才跑）', () => {
  const fetchNga = createNgaFetcher({
    transport: createFetchTransport(),
    getCredentials: () => credentials,
    authMode: 'cookie',
  })

  it('通知接口用测试 cookie 拿到合法 data', async () => {
    const result = await fetchNga({
      path: 'nuke.php',
      query: { __lib: 'noti', __act: 'get_all' },
    })

    expect(result.via).toBe('direct')
    expect(result.data).toBeDefined()
    // 登录态下 data 是对象；没登录会走 error 分支抛「未登录」
    expect(typeof result.data).toBe('object')
  }, 20_000)

  it('另一种认证方式（form）同样能通', async () => {
    const formFetch = createNgaFetcher({
      transport: createFetchTransport(),
      getCredentials: () => credentials,
      authMode: 'form',
    })

    const result = await formFetch({
      path: 'nuke.php',
      query: { __lib: 'ucp', __act: 'get', uid: credentials!.uid },
      referer: 'https://bbs.nga.cn/nuke.php?func=ucp',
    })

    const user = (result.data as Record<string, Record<string, unknown>>)['0']
    expect(String(user?.uid)).toBe(credentials!.uid)
  }, 20_000)

  it('GBK 编码的 author 参数能筛到人（thread.php 的 author 走 GBK）', async () => {
    const result = await fetchNga({
      path: 'thread.php',
      query: { author: gbk('春曰影') },
    })
    const topics = (result.data as Record<string, Record<string, Record<string, unknown>>>).__T

    expect(topics?.['0']?.author).toBe('春曰影')
  }, 20_000)

  it('未声明 charset 的 GBK 主题列表能解出中文版块名', async () => {
    const result = await fetchNga({ path: 'thread.php', query: { fid: 650, page: 1 } })
    const data = result.data as Record<string, Record<string, unknown>>

    expect(data.__F?.name).toBe('原神')
    expect(JSON.stringify(data)).not.toContain('�')
  }, 20_000)
})

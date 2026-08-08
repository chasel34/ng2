import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

import { createFetchTransport, createNgaFetcher } from '../net'
import { fetchBoardSearch, fetchTopicSearch } from './search'
import { fetchUserProfileByName } from './user-profile'

/**
 * 联网冒烟：真的搜一次。**默认不跑**——同其它冒烟：
 * `.env.local` 里要有 NGA_UID / NGA_CID，且 `NGA_INTEGRATION=1 pnpm test`。
 *
 * fixture 单测锁的是解析与编码字节，这里锁的是**编码在真实服务端还认不认**：
 * thread.php 吃 UTF-8 的 key、forum.php 吃 GBK 的 key、ucp 按中文名查得到人。
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
const credentials = env.NGA_UID && env.NGA_CID ? { uid: env.NGA_UID, token: env.NGA_CID } : null
const enabled = process.env.NGA_INTEGRATION === '1' && credentials !== null

describe.skipIf(!enabled)('搜索联网冒烟（NGA_INTEGRATION=1 才跑）', () => {
  const fetchNga = createNgaFetcher({
    transport: createFetchTransport(),
    getCredentials: () => credentials,
    authMode: 'cookie',
  })

  it('中文关键词搜主题（UTF-8 key)搜得到,标题没解坏', async () => {
    const list = await fetchTopicSearch(fetchNga, { key: '炉石', page: 1 })

    expect(list.topics.length).toBeGreaterThan(0)
    expect(list.totalRows).toBeGreaterThan(list.topics.length)
    expect(JSON.stringify(list.topics.map((topic) => topic.subject))).not.toContain('�')
  }, 20_000)

  it('本版含正文的组合照样有结果(fid=-7 网事杂谈)', async () => {
    const list = await fetchTopicSearch(fetchNga, {
      key: '显卡',
      page: 1,
      boardId: -7,
      kind: 'board',
      searchContent: true,
    })
    expect(list.topics.length).toBeGreaterThan(0)
  }, 20_000)

  it('中文关键词搜版块(GBK key)搜得到「炉石传说」', async () => {
    const items = await fetchBoardSearch(fetchNga, { key: '炉石' })

    expect(items.length).toBeGreaterThan(0)
    expect(items.some((item) => item.board.name.includes('炉石'))).toBe(true)
  }, 20_000)

  it('按中文用户名查用户(UTF-8 + __inchst=UTF8)', async () => {
    const profile = await fetchUserProfileByName(fetchNga, { username: '冷面比面筋好吃' })
    expect(profile.uid).toBe(64870845)
  }, 20_000)
})

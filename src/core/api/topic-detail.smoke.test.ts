import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

import { createFetchTransport, createNgaFetcher } from '../net'
import { fetchTopicDetail } from './topic-detail'

/**
 * 联网冒烟：真的打一次 `read.php`。**默认不跑**——同 core/net 的冒烟：
 * `.env.local` 里要有 NGA_UID / NGA_CID，且 `NGA_INTEGRATION=1 pnpm test`。
 *
 * fixture 单测锁的是解析，这里锁的是**请求本身还通不通**：
 * 参数拼装、`windowsPhone` UA 档、以及被封时会最先垮掉的那一层。
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

/** spec §6 的对拍样例主题。 */
const SAMPLE_TID = 45150945

describe.skipIf(!enabled)('read.php 联网冒烟（NGA_INTEGRATION=1 才跑）', () => {
  const fetchNga = createNgaFetcher({
    transport: createFetchTransport(),
    getCredentials: () => credentials,
    authMode: 'cookie',
  })

  it('拉到第 1 页楼层、附件域名与分页信息', async () => {
    const detail = await fetchTopicDetail(fetchNga, { tid: SAMPLE_TID, page: 1 })

    expect(detail.tid).toBe(SAMPLE_TID)
    expect(detail.floors.length).toBeGreaterThan(0)
    expect(detail.floors[0]?.lou).toBe(0)
    // 附件域名必须是响应给的，不是兜底常量硬拼出来的
    expect(detail.attachBase).toMatch(/^https:\/\/\S+\/attachments$/)
    expect(detail.totalPages).toBe(Math.ceil(detail.totalRows / 20))
    // 中文没解坏
    expect(JSON.stringify(detail)).not.toContain('�')
  }, 20_000)

  it('每个楼层都查得到作者', async () => {
    const detail = await fetchTopicDetail(fetchNga, { tid: SAMPLE_TID, page: 1 })
    for (const floor of detail.floors) {
      expect(detail.users[floor.authorKey]).toBeDefined()
    }
  }, 20_000)
})

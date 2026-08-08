import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

import { NgaError, createFetchTransport, createNgaFetcher } from '../net'
import { fetchUserAvatar, fetchUserProfile } from './user-profile'
import { fetchUserTopics } from './user-topics'

/**
 * 联网冒烟：真的打一次 ucp 与 thread.php。**默认不跑**——同其它冒烟：
 * `.env.local` 里要有 NGA_UID / NGA_CID，且 `NGA_INTEGRATION=1 pnpm test`。
 *
 * fixture 单测锁的是解析，这里锁的是**请求本身还通不通**：
 * Referer、`__lib=ucp` 的参数拼装，以及「找不到用户」那条假错误分支。
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

/** ticket 14 的对拍样例用户。 */
const SAMPLE_UID = 41417929

describe.skipIf(!enabled)('用户资料联网冒烟（NGA_INTEGRATION=1 才跑）', () => {
  const fetchNga = createNgaFetcher({
    transport: createFetchTransport(),
    getCredentials: () => credentials,
    authMode: 'cookie',
  })

  it('拉到样例用户的资料，中文没解坏', async () => {
    const profile = await fetchUserProfile(fetchNga, { uid: SAMPLE_UID })

    expect(profile.uid).toBe(SAMPLE_UID)
    expect(profile.name).not.toBe('')
    expect(JSON.stringify(profile)).not.toContain('�')
  }, 20_000)

  it('不存在的用户报「找不到用户」而不是当成一份空资料', async () => {
    await expect(fetchUserProfile(fetchNga, { uid: 999999999 })).rejects.toThrow(NgaError)
  }, 20_000)

  it('头像补充查询给得出一个图片地址', async () => {
    const url = await fetchUserAvatar(fetchNga, { uid: SAMPLE_UID })
    expect(url).toMatch(/^https?:\/\//)
  }, 20_000)

  it('某人的回复每条都带着 __P 里那条回复', async () => {
    const list = await fetchUserTopics(fetchNga, { uid: SAMPLE_UID, kind: 'replies', page: 1 })

    expect(list.topics.length).toBeGreaterThan(0)
    expect(list.topics.every((topic) => topic.reply !== undefined)).toBe(true)
  }, 20_000)
})

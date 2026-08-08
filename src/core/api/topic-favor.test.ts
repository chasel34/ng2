import { describe, expect, it, vi } from 'vitest'

import { decodeGb18030 } from '../net'
import { createNgaFetcher } from '../net/fetcher'
import type { HttpRequest, HttpResponse } from '../net/transport'
import { fixtureContentType, readFixtureBytes, type ApiFixtureName } from './__fixtures__'
import {
  addTopicFavorite,
  createFavoriteFolder,
  deleteFavoriteFolder,
  fetchFavoriteFolders,
  fetchFavoriteTopics,
  modifyFavoriteFolder,
  parseFavoriteFolders,
  removeTopicFavorite,
} from './topic-favor'

/** 用真实抓包字节应答的假传输层，顺带把请求录下来。 */
function fixtureTransport(fixture: ApiFixtureName | { readonly body: string }) {
  const requests: HttpRequest[] = []
  const transport = vi.fn(async (request: HttpRequest): Promise<HttpResponse> => {
    requests.push(request)
    return typeof fixture === 'object'
      ? {
          // 内联样本是 TextEncoder 出来的 UTF-8 字节，charset 要如实声明
          status: 200,
          contentType: 'text/javascript; charset=UTF-8',
          body: new TextEncoder().encode(fixture.body),
        }
      : {
          status: 200,
          contentType: fixtureContentType(fixture),
          body: readFixtureBytes(fixture),
        }
  })
  return { transport, requests }
}

const dataOf = (name: ApiFixtureName): unknown =>
  (JSON.parse(decodeGb18030(readFixtureBytes(name))) as { data: unknown }).data

describe('parseFavoriteFolders（真实样本）', () => {
  it('解出两个夹，default 键标出默认夹，length 是主题数', () => {
    const folders = parseFavoriteFolders(dataOf('favorFolders'))
    expect(folders).toHaveLength(2)
    expect(folders[1]).toEqual({
      id: 4699990,
      name: '娴嬭瘯澶笰',
      count: 1,
      isDefault: true,
    })
    expect(folders[0]?.isDefault).toBe(false)
    expect(folders[0]?.count).toBe(0)
  })

  it('一个夹都没有时返回空数组（data["0"] 是空对象）', () => {
    expect(parseFavoriteFolders(dataOf('favorFoldersEmpty'))).toEqual([])
  })

  it('坏条目跳过，整体不炸', () => {
    const folders = parseFavoriteFolders({
      '0': {
        '0': { id: 1, name: '好的' },
        '1': { name: '没有 id' },
        '2': '不是对象',
      },
    })
    expect(folders).toEqual([{ id: 1, name: '好的', count: 0, isDefault: false }])
  })

  it('data 不是对象时返回空数组', () => {
    expect(parseFavoriteFolders(undefined)).toEqual([])
    expect(parseFavoriteFolders('')).toEqual([])
  })
})

describe('fetchFavoriteFolders', () => {
  it('打 nuke.php topic_favor_v2 list_folder，GBK 响应一路解出来', async () => {
    const { transport, requests } = fixtureTransport('favorFolders')
    const folders = await fetchFavoriteFolders(createNgaFetcher({ transport }))

    expect(requests[0]?.url).toContain('/nuke.php?')
    expect(requests[0]?.url).toContain('__lib=topic_favor_v2')
    expect(requests[0]?.url).toContain('__act=list_folder')
    expect(requests[0]?.url).toContain('page=1')
    expect(folders.map((folder) => folder.id)).toEqual([4699991, 4699990])
  })

  it('响应里没有 data 时报解析错，交给上层兜底', async () => {
    const { transport } = fixtureTransport({ body: '{"data":"","time":1}' })
    await expect(fetchFavoriteFolders(createNgaFetcher({ transport }))).rejects.toThrow(/没有 data/)
  })
})

describe('fetchFavoriteTopics', () => {
  it('打 thread.php?favor=<夹id>，复用主题列表解析', async () => {
    const { transport, requests } = fixtureTransport('favorTopics')
    const list = await fetchFavoriteTopics(createNgaFetcher({ transport }), {
      folderId: 4699990,
      page: 1,
    })

    expect(requests[0]?.url).toContain('/thread.php?')
    expect(requests[0]?.url).toContain('favor=4699990')
    expect(requests[0]?.url).toContain('page=1')
    expect(list.topics).toHaveLength(1)
    expect(list.topics[0]?.tid).toBe(45150945)
    expect(list.totalRows).toBe(1)
    expect(list.rowsPerPage).toBe(35)
  })
})

const OK = { body: '{"data":{"0":"操作成功"},"time":1}' }

/** 写操作的请求都录下来给断言用。 */
async function record(
  run: (fetchNga: ReturnType<typeof createNgaFetcher>) => Promise<unknown>,
  fixture: ApiFixtureName | { readonly body: string } = OK,
) {
  const { transport, requests } = fixtureTransport(fixture)
  await run(createNgaFetcher({ transport }))
  const first = requests[0]
  if (!first) throw new Error('一条请求都没发出去')
  return first
}

describe('收藏写操作', () => {
  it('add：form 带 tid 与 folder', async () => {
    const request = await record((fetchNga) =>
      addTopicFavorite(fetchNga, { tid: 45150945, folderId: 7 }),
    )
    expect(request.url).toContain('__act=add')
    expect(request.body).toContain('tid=45150945')
    expect(request.body).toContain('folder=7')
  })

  it('del：参数名是 tidarray 不是 tid（API 文档 §5.1 的坑）', async () => {
    const request = await record((fetchNga) =>
      removeTopicFavorite(fetchNga, { tid: 45150945, folderId: 7 }),
    )
    expect(request.url).toContain('__act=del')
    expect(request.body).toContain('tidarray=45150945')
    expect(request.body).not.toContain('tid=45150945')
    expect(request.body).toContain('folder=7')
  })

  it('new_folder：带 raw=3，opt 按是否设默认取 2/0，返回 data["1"] 里的新夹 id', async () => {
    const { transport, requests } = fixtureTransport('favorNewFolder')
    const id = await createFavoriteFolder(createNgaFetcher({ transport }), { name: '装机' })

    expect(requests[0]?.url).toContain('__act=new_folder')
    expect(requests[0]?.url).toContain('raw=3')
    // buildQueryString 会剔除空值，opt=0 会被剔掉——服务端把缺省当 0（实测行为一致）
    expect(requests[0]?.body).not.toContain('opt=2')
    expect(id).toBe(4699991)

    const asDefault = await record((fetchNga) =>
      createFavoriteFolder(fetchNga, { name: '装机', asDefault: true }),
    )
    expect(asDefault.body).toContain('opt=2')
  })

  it('modify_folder：重命名与设默认同一个 act，name 始终要带', async () => {
    const rename = await record((fetchNga) =>
      modifyFavoriteFolder(fetchNga, { folderId: 7, name: '新名字' }),
    )
    expect(rename.url).toContain('__act=modify_folder')
    expect(rename.body).toContain('folder=7')
    expect(rename.body).toContain(`name=${encodeURIComponent('新名字')}`)

    const setDefault = await record((fetchNga) =>
      modifyFavoriteFolder(fetchNga, { folderId: 7, name: '现名', asDefault: true }),
    )
    expect(setDefault.body).toContain('opt=2')
  })

  it('del_folder：form 只带 folder', async () => {
    const request = await record((fetchNga) => deleteFavoriteFolder(fetchNga, { folderId: 7 }))
    expect(request.url).toContain('__act=del_folder')
    expect(request.url).toContain('raw=3')
    expect(request.body).toContain('folder=7')
  })

  it('服务端语义错误原样抛出（envelope 兜底）', async () => {
    const { transport } = fixtureTransport({
      body: '{"error":{"0":"收藏夹数量已达上限"},"time":1}',
    })
    await expect(
      createFavoriteFolder(createNgaFetcher({ transport }), { name: '第 21 个' }),
    ).rejects.toThrow('收藏夹数量已达上限')
  })
})

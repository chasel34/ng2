/**
 * 子版块订阅 / 屏蔽(CONTEXT.md「子版块」,API 文档 §1.4)。
 *
 * ```
 * POST nuke.php?__lib=user_option&__act=set&raw=3&{del|add}=<filterId>
 * form: fid=<父版块 fid>, type=<0|1>, info=add_to_block_tids
 * ```
 *
 * 这个接口有两处反直觉,都是从两份客户端源码里试出来的,没有文档:
 *
 * 1. **参数名即操作,而且语义是反的**:`del=<id>` 是订阅(把它从屏蔽表里删掉),
 *    `add=<id>` 是屏蔽。请求体里的 `info=add_to_block_tids` 说明了这一点——
 *    服务端维护的是一张**屏蔽** tid 表,订阅只是「不在表里」。
 * 2. **`type` 会把 1 再反转一次**:Android 源码里 `type==1` 时订阅用 `del`,
 *    其它 type 反过来(原注释:「NGA 后台好变态啊,某个板块的操作居然是反的」)。
 *    type 由子版块有没有 `filter_id` 决定(topic-list.ts 的 `parseSubBoard`)。
 *
 * 状态判定同样靠魔法数(`attributes`),MNGA 源码那行的注释是
 * "how can I fucking know this ??"——**随时可能随 NGA 更新失效**,
 * 失效的表现是状态显示反了/按钮不可点,不影响其它功能。
 */

import type { NgaFetcher } from '../net'
import type { SubBoard } from './types'

/** `attributes` 命中这几个值视为已订阅(API 文档 §13 第 13 条)。 */
export const SUBSCRIBED_ATTRIBUTES: readonly number[] = [7, 558, 542, 2606, 2590, 4654]

/** `attributes` 大于这个值才谈得上订阅/屏蔽,小的那些服务端不让改。 */
export const FILTERABLE_ATTRIBUTES_MIN = 40

export interface SubBoardState {
  /** 当前是否已订阅(未订阅 = 被屏蔽,服务端只有这两态) */
  readonly subscribed: boolean
  /** 能不能改。false 时 UI 只展示,不给开关 */
  readonly filterable: boolean
}

/** 按魔法数判定一个子版块的订阅状态。 */
export function subBoardState(attributes: number): SubBoardState {
  return {
    subscribed: SUBSCRIBED_ATTRIBUTES.includes(attributes),
    filterable: attributes > FILTERABLE_ATTRIBUTES_MIN,
  }
}

/** 用户视角的两个动作。 */
export type SubBoardAction = 'subscribe' | 'block'

/**
 * 这次操作该用哪个参数名。基准规则是「订阅 = del」,`type` 为 0 时整个反过来。
 */
export function subBoardOptionParam(action: SubBoardAction, filterType: 0 | 1): 'add' | 'del' {
  const subscribeParam = filterType === 1 ? 'del' : 'add'
  return action === 'subscribe' ? subscribeParam : subscribeParam === 'del' ? 'add' : 'del'
}

/** 操作后本地该显示的状态(服务端不回新的 attributes,只回一句「操作成功」)。 */
export function nextSubBoardState(state: SubBoardState, action: SubBoardAction): SubBoardState {
  return { ...state, subscribed: action === 'subscribe' }
}

export interface SetSubBoardOptionOptions {
  readonly subBoard: SubBoard
  /** 父版块的 fid(子版块列表是从哪个版块的 `__F` 里来的) */
  readonly parentFid: number
  readonly action: SubBoardAction
  readonly signal?: AbortSignal
}

/** 订阅或屏蔽一个子版块。失败由 envelope 抛 `kind: 'server'`,能返回就是成功。 */
export async function setSubBoardOption(
  fetchNga: NgaFetcher,
  options: SetSubBoardOptionOptions,
): Promise<void> {
  const { subBoard, parentFid, action, signal } = options
  const param = subBoardOptionParam(action, subBoard.filterType)

  await fetchNga({
    path: 'nuke.php',
    query: {
      __lib: 'user_option',
      __act: 'set',
      raw: 3,
      // 参数名本身就是操作,所以只放这一个,另一个连键都不能出现
      [param]: subBoard.filterId,
    },
    form: { fid: parentFid, type: subBoard.filterType, info: 'add_to_block_tids' },
    ...(signal === undefined ? {} : { signal }),
  })
}

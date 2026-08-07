/** 生成文件 `table.generated.ts` 的数据形状。 */
export interface SmileyCategoryData {
  /** BBCode 里的分类标识,如 `ac`;`'0'` 是 `[s:数字]` 用的默认套。 */
  readonly key: string
  /** 官方套系中文名,如 `AC娘(v1)`。 */
  readonly label: string
  /** `[名称, 文件名]`,顺序与官方表一致(表情面板按此排列)。 */
  readonly entries: readonly (readonly [name: string, file: string])[]
}

/** 命中映射表的表情。 */
interface KnownSmiley {
  /** 分类标识,如 `ac`;默认套是 `'0'`。 */
  readonly category: string
  /** 分类中文名,如 `AC娘(v1)`。 */
  readonly label: string
  /** 表情名,如 `笑`;默认套里是数字串。 */
  readonly name: string
  /** 图片文件名,用它去 `src/ui/smilies.generated.ts` 的 `SMILEY_ASSETS` 取静态资源。 */
  readonly file: string
  /** CDN 上的原图地址。 */
  readonly remoteUrl: string
}

/** `[s:...]` 的解析结果。 */
export type ResolvedSmiley =
  | ({
      /**
       * `bundled` = 图片已随包,走 `SMILEY_ASSETS[file]`;
       * `remote` = 表里有但图片没随包(官方新加的、或下载缺失),走 `remoteUrl`。
       */
      readonly kind: 'bundled' | 'remote'
    } & KnownSmiley)
  | {
      /** 映射表里查不到,原样显示 BBCode 文本。 */
      readonly kind: 'unresolved'
      /** 还原出的原文标记,如 `[s:ac:不存在]`。 */
      readonly raw: string
    }

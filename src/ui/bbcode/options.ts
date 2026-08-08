import type { AttachmentUrlOptions } from '@/core/api';
import type { DiceNode } from '@/core/bbcode';
import type { DiceOutcome } from '@/core/local';

/**
 * 渲染一段 BBCode 需要的、正文本身给不出的东西——全都来自楼层或它所在的那一页。
 *
 * 单独一个文件是为了让卡片组件(`./media`、`./blocks`)能引用它而不用回头 import
 * `./render`,免得两个模块互相引用。
 */
export interface BBCodeRenderOptions {
  /** 附件图片基址,来自 `read.php` 的 `__GLOBAL._ATTACH_BASE_VIEW`(每页都可能变) */
  readonly attachBase: string;
  /** 所在楼层的发帖时间,`[noimg]` 的相对路径要靠它补 `mon_YYYYMM/DD/` */
  readonly postedAt?: number;
  /**
   * 骰子点数,由 `resolveDice` 按整个楼层一次算好(CONTEXT.md「骰子」)。
   * 按节点身份查表——同一楼层里两个 `[dice]d100[/dice]` 写法一样、点数不同。
   */
  readonly dice?: ReadonlyMap<DiceNode, DiceOutcome>;
  /** 点图片(25 票的大图查看器接进来) */
  readonly onOpenImage?: (uri: string) => void;
  /**
   * 正文字号(22 票的「帖子内字体大小」)。`[size=150%]` 这类相对字号按它算,
   * 不给就按 token 里的 `body` 那一档。
   */
  readonly bodyFontSize?: number;
  /** 同上,正文行高的绝对像素值 */
  readonly bodyLineHeight?: number;
}

/** 把渲染参数削成 `attachmentUrl` 要的那两项——正文图、附件、相册都走同一份。 */
export const attachOptions = (options: BBCodeRenderOptions): AttachmentUrlOptions => ({
  base: options.attachBase,
  ...(options.postedAt === undefined ? {} : { postedAt: options.postedAt }),
});

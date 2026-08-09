import {
  attachmentUrl,
  thumbnailUrl,
  type AttachmentUrlOptions,
  type FloorAttachment,
} from '@/core/api';
import { childNodeLists, type BBCodeNode } from '@/core/bbcode';

import type { ViewerImage } from '../image-viewer-request';
import { albumImageUrls } from './album';

/**
 * 收集一个楼层的全部图片(25 票:查看器要「本楼全部图片列表 + 当前下标」)。
 *
 * 口径与渲染器一致:正文里的 `[img]`/`[noimg]`(含嵌在引用块、粗体、相册里的)
 * 走 `attachmentUrl` 拼地址,附件宫格里 `kind === 'img'` 的那些排在正文之后——
 * 和它们在屏上的出现顺序相同,查看器里翻页的次序才对得上直觉。
 *
 * 纯函数,不碰组件:点击处拿 URL 反查下标,列表口径错了单测就能钉住。
 */
export function collectFloorImages(
  nodes: readonly BBCodeNode[],
  attachments: readonly FloorAttachment[],
  options: AttachmentUrlOptions,
): ViewerImage[] {
  const seen = new Set<string>();
  const images: ViewerImage[] = [];

  const push = (url: string, thumbnail: string | undefined) => {
    // 同一张图可能既写在正文又挂在附件里,按第一次出现去重
    if (seen.has(url)) return;
    seen.add(url);
    images.push(
      thumbnail === undefined || thumbnail === url ? { url } : { url, thumbnailUrl: thumbnail },
    );
  };

  const visit = (list: readonly BBCodeNode[]) => {
    for (const node of list) {
      if (node.type === 'image') {
        const url = attachmentUrl(node, options);
        push(url, thumbnailUrl(url, options.base));
      } else if (node.type === 'album') {
        for (const url of albumImageUrls(node.value, options)) {
          push(url, thumbnailUrl(url, options.base));
        }
      } else {
        for (const children of childNodeLists(node)) visit(children);
      }
    }
  };
  visit(nodes);

  for (const attachment of attachments) {
    if (attachment.kind !== 'img') continue;
    push(attachment.url, attachment.thumbnailUrl);
  }

  return images;
}

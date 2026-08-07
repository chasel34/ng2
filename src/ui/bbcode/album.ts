import { attachmentUrl, type AttachmentUrlOptions } from '@/core/api';

/**
 * `[album]` 里那一串图片地址的提取。
 *
 * 解析器把 `[album]` 的内容原样留在 `value` 里(03 票的约定),因为相册的内容
 * 有两种写法:要么是一串 `[img]`/`[url]` 标签,要么是**裸地址**堆在一起。
 * 取法照 NGA 官方 `js_bbscode_core.js` 的 `[album]` 分支:
 * 里面出现过 `[img]`/`[url]` 就只认标签之间的地址,否则退回扫裸地址。
 *
 * 纯字符串处理,不碰组件,这样这套判断能单测。
 */

/** 内容里出现过 `[img]` / `[url]` 时,只认「`]` 与 `[` 之间」的地址。 */
const TAGGED_PATTERN = /\]\s*((?:https?:\/\/|\.\/)[^[]+?)\s*\[/gi;

/** 官方扫裸地址用的那条:地址前面必须是行首或一个「不属于地址」的字符。 */
const BARE_PATTERN =
  /(?:^|[^a-zA-Z0-9\-_+=.$;/?:@&#%])((?:https?:\/\/|\.\/)[a-zA-Z0-9\-_+=.$;/?:@&#%]+)/gi;

const HAS_TAG = /\[(?:img|url)\]/i;

/** 相册里的一张图,已拼好可以直接喂给 `<Image>`。 */
export function albumImageUrls(value: string, options: AttachmentUrlOptions): string[] {
  const pattern = HAS_TAG.test(value) ? TAGGED_PATTERN : BARE_PATTERN;
  const urls: string[] = [];
  for (const match of value.matchAll(pattern)) {
    const raw = match[1]!;
    const relative = raw.startsWith('./');
    urls.push(
      attachmentUrl({ src: relative ? raw.slice(2) : raw, needsAttachBase: relative }, options),
    );
  }
  return urls;
}

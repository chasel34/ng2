/**
 * 正文图片的真实尺寸缓存。
 *
 * 服务端不给图片宽高(API 文档 §3 的 `attachs` 只有 `size`/`type`,是字节数不是像素),
 * 所以只能等 `onLoad`。问题是同一张图在一次浏览里会被反复挂载——列表回收后
 * 滚回来、翻页再翻回来、引用块里又引一遍——每次都要从 4:3 占位跳到真实比例,
 * 单元格高度跟着变,列表就得重新量算、内容跳位。
 *
 * 记下来之后第二次起首帧就按真实比例画,不再跳。
 *
 * 纯函数模块、不碰组件,这样能单测——本仓库跑不了组件渲染测试。
 */

export interface ImageSize {
  readonly width: number;
  readonly height: number;
}

/**
 * 上限。一张 entry 只有两个数,几百条也就几 KB;设上限只是防「一路翻几百页」
 * 这种极端情况下无限涨。到顶了丢最早的一条(Map 按插入序迭代)。
 */
const LIMIT = 512;

const sizes = new Map<string, ImageSize>();

/** 记下一张图的真实像素尺寸。宽高有一边是 0(解码失败)的不记。 */
export function rememberImageSize(uri: string, size: ImageSize): void {
  if (size.width <= 0 || size.height <= 0) return;

  if (!sizes.has(uri) && sizes.size >= LIMIT) {
    const oldest = sizes.keys().next();
    if (!oldest.done) sizes.delete(oldest.value);
  }
  sizes.set(uri, { width: size.width, height: size.height });
}

/**
 * 查一张图的真实尺寸,没见过就是 undefined(调用方回落到占位比例)。
 *
 * key 是**实际加载的那个地址**而不是原图地址:省流量档拉的是缩略图,像素尺寸
 * 跟原图不是一回事,混在一起会让「小图按原尺寸摆」的判断认错。
 */
export const imageSizeOf = (uri: string): ImageSize | undefined => sizes.get(uri);

/** 只给测试用:清空,免得用例之间互相影响。 */
export const clearImageSizes = (): void => sizes.clear();

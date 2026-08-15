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
 * 会话内是 Map;持久化(跨启动)通过 `attachImageSizePersistence` 注入——
 * 「首次进入帖子的比例跳变」大头在**下次启动重看同一批图**,不落盘每次冷启动
 * 都要重跳一遍。存储实现(MMKV)在 `./image-size.persist.ts`,这里不 import RN,
 * 保持纯函数模块可单测——本仓库跑不了组件渲染测试。
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

export interface ImageSizePersistence {
  /** 启动时读出上次存的全部条目;坏数据返回空数组即可 */
  load(): readonly (readonly [string, ImageSize])[];
  /** 全量覆盖写入(条目数被 LIMIT 钳住,整包也就几十 KB) */
  save(entries: readonly (readonly [string, ImageSize])[]): void;
}

let persistence: ImageSizePersistence | undefined;
let saveTimer: ReturnType<typeof setTimeout> | undefined;

/** 图片是成批加载的,攒一秒一起写,别每张图都撞一次存储。 */
const SAVE_DEBOUNCE_MS = 1000;

/** 接上持久层并回灌缓存。本会话已量到的条目优先(它们更新)。 */
export function attachImageSizePersistence(store: ImageSizePersistence): void {
  persistence = store;
  for (const [uri, size] of store.load()) {
    if (!sizes.has(uri) && sizes.size < LIMIT && size.width > 0 && size.height > 0) {
      sizes.set(uri, size);
    }
  }
}

function scheduleSave(): void {
  if (persistence === undefined || saveTimer !== undefined) return;
  saveTimer = setTimeout(() => {
    saveTimer = undefined;
    persistence?.save([...sizes.entries()]);
  }, SAVE_DEBOUNCE_MS);
}

/** 记下一张图的真实像素尺寸。宽高有一边是 0(解码失败)的不记。 */
export function rememberImageSize(uri: string, size: ImageSize): void {
  if (size.width <= 0 || size.height <= 0) return;

  if (!sizes.has(uri) && sizes.size >= LIMIT) {
    const oldest = sizes.keys().next();
    if (!oldest.done) sizes.delete(oldest.value);
  }
  sizes.set(uri, { width: size.width, height: size.height });
  scheduleSave();
}

/**
 * 查一张图的真实尺寸,没见过就是 undefined(调用方回落到占位比例)。
 *
 * key 是**实际加载的那个地址**而不是原图地址:省流量档拉的是缩略图,像素尺寸
 * 跟原图不是一回事,混在一起会让「小图按原尺寸摆」的判断认错。
 */
export const imageSizeOf = (uri: string): ImageSize | undefined => sizes.get(uri);

/** 只给测试用:清空缓存与持久层挂接,免得用例之间互相影响。 */
export const clearImageSizes = (): void => {
  sizes.clear();
  persistence = undefined;
  if (saveTimer !== undefined) {
    clearTimeout(saveTimer);
    saveTimer = undefined;
  }
};

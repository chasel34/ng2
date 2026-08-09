/**
 * 大图查看器(25 票)的进场参数。
 *
 * 一次要看的是「本楼全部图片 + 从哪张开始」,几十条 URL 塞进路由参数会被
 * expo-router 序列化进导航状态(还得转义),所以走模块级暂存:调用方先
 * `stageImageViewer` 再 push 路由,查看器挂载时取走。暂存不清空——
 * Fast Refresh 重挂载还能拿到同一份;下一次打开自然覆盖。
 */

export interface ViewerImage {
  /** 原图地址,已拼好可直接请求 */
  readonly url: string;
  /** 缩略图变体;站外图床没有这套约定时缺席 */
  readonly thumbnailUrl?: string;
}

export interface ImageViewerRequest {
  /** 本楼的全部图片,按正文 → 附件宫格的出现顺序 */
  readonly images: readonly ViewerImage[];
  /** 点开的那张在列表里的下标 */
  readonly index: number;
}

let staged: ImageViewerRequest | undefined;

export function stageImageViewer(request: ImageViewerRequest): void {
  staged = request;
}

/** 查看器挂载时读取。深链等异常路径直接打开查看器时拿到 undefined,由页面兜底。 */
export function stagedImageViewer(): ImageViewerRequest | undefined {
  return staged;
}

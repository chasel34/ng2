import { File, Paths } from 'expo-file-system';
import { Album, Asset, requestPermissionsAsync } from 'expo-media-library';
import { shareAsync } from 'expo-sharing';

import { imageFileName, imageMimeType } from '@/core/api';

/**
 * 大图查看器(25 票)的落盘动作:保存到相册、系统分享、批量下载。
 *
 * 相册与分享吃的都是本地文件,所以先把原图下到缓存目录中转;文件名由
 * `imageFileName` 从 URL 稳定推出,同一张图重复保存不会在缓存里越积越多。
 */

/** 设计稿 toast 说的「相册/NGA」:保存的目标相册名。 */
export const ALBUM_NAME = 'NGA';

/** 相册权限被拒。单独一个类型,调用方好把它区别于网络失败给不同的提示。 */
export class MediaPermissionError extends Error {
  constructor() {
    super('需要相册权限才能保存图片');
    this.name = 'MediaPermissionError';
  }
}

/** 把原图下到缓存目录,返回本地文件。已存在的直接复用(expo-image 不暴露它的缓存路径)。 */
export async function downloadImage(url: string): Promise<File> {
  const file = new File(Paths.cache, imageFileName(url));
  if (file.exists && (file.size ?? 0) > 0) return file;
  if (file.exists) file.delete();
  return File.downloadFileAsync(url, file);
}

/**
 * Android 的相册写权限。写这一侧走 MediaStore,API 30+ 本不需要运行时权限,
 * 但 expo-media-library 的 `Asset.create` 统一按「有没有授权」把关,所以照常请求;
 * `writeOnly` + 只要 photo 一档,弹的授权框最小。
 */
async function ensureWritePermission(): Promise<void> {
  const response = await requestPermissionsAsync(true, ['photo']);
  if (!response.granted) throw new MediaPermissionError();
}

/**
 * 相册里已有的文件名。文件名由 `imageFileName` 从 URL 稳定推出,
 * 所以「同名」就是「同一张图存过了」(M4 验收 G8:重复保存不该在相册里堆两套)。
 */
async function albumFilenames(album: Album | null): Promise<Set<string>> {
  if (album === null) return new Set();
  const assets = await album.getAssets();
  return new Set(await Promise.all(assets.map((asset) => asset.getFilename())));
}

/** 下载中转 + 写进「相册/NGA」,返回落进去的相册。`moveAssets: false`——中转文件留在缓存里给分享/重存复用。 */
async function saveDownloadedImage(url: string, album: Album | null): Promise<Album> {
  const file = await downloadImage(url);
  if (album === null) return Album.create(ALBUM_NAME, [file.uri], false);
  await Asset.create(file.uri, album);
  return album;
}

/** 把一张图存进「相册/NGA」;已经存过的不再存第二份。 */
export async function saveImageToAlbum(url: string): Promise<'saved' | 'duplicate'> {
  await ensureWritePermission();
  const album = await Album.get(ALBUM_NAME);
  const existing = await albumFilenames(album);
  if (existing.has(imageFileName(url))) return 'duplicate';
  await saveDownloadedImage(url, album);
  return 'saved';
}

/** 调起系统分享面板分享图片文件本体(不是分享一条链接)。 */
export async function shareImage(url: string): Promise<void> {
  const file = await downloadImage(url);
  await shareAsync(file.uri, { mimeType: imageMimeType(file.name) });
}

export interface BatchSaveResult {
  saved: number;
  /** 相册里已经有同一张,这次跳过没存 */
  skipped: number;
  failed: number;
}

/**
 * 批量下载本楼全部图片进相册。顺序下,一张失败不拦着后面的;
 * 已经在相册里的跳过(G8);权限被拒是整批的事,直接抛出去。
 */
export async function saveImagesToAlbum(urls: readonly string[]): Promise<BatchSaveResult> {
  await ensureWritePermission();
  let album = await Album.get(ALBUM_NAME);
  const existing = await albumFilenames(album);
  let saved = 0;
  let skipped = 0;
  let failed = 0;
  for (const url of urls) {
    const name = imageFileName(url);
    if (existing.has(name)) {
      skipped += 1;
      continue;
    }
    try {
      album = await saveDownloadedImage(url, album);
      existing.add(name);
      saved += 1;
    } catch {
      failed += 1;
    }
  }
  return { saved, skipped, failed };
}

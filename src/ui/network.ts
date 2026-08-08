import { useOnMeteredNetwork } from '@/store/network';
import { useSettings } from '@/store/settings';

/**
 * 图片能不能直接铺出来(「仅 Wi-Fi 下加载图片」,22 票)。
 * false = 折成「点击显示」,点了照样能看。
 */
export function useImagesUnlocked(): boolean {
  const wifiOnly = useSettings((state) => state.settings.wifiOnlyImages);
  const metered = useOnMeteredNetwork();
  return !wifiOnly || !metered;
}

/**
 * 正文图该拉原图还是缩略图(「图片加载策略」)。
 * `smart` 一档按当前网络分:Wi-Fi 原图、流量缩略图。
 */
export function usePreferThumbnail(): boolean {
  const quality = useSettings((state) => state.settings.imageQuality);
  const metered = useOnMeteredNetwork();
  if (quality === 'thumbnail') return true;
  if (quality === 'original') return false;
  return metered;
}

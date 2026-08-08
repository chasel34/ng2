import { memo } from 'react';
import { Image, Text } from 'react-native';

import { resolveSmiley } from '@/core/smilies';

import { useSmileyHeight } from '../appearance';
import { SMILEY_ASSETS } from '../smilies.generated';
import { createThemedStyles } from '../theme';

/**
 * 按原图长宽比算出显示宽度。随包资源能同步拿到尺寸
 * (`Image.resolveAssetSource` 读的是打包期写进 bundle 的元数据);
 * 远程兜底时拿不到尺寸,只能按正方形占位。
 *
 * 高度由「表情大小」设置定(22 票),默认 150% = 24,与一行正文(15.5 · 1.68 ≈ 26)相当。
 */
function widthOf(asset: number | undefined, height: number): number {
  if (asset === undefined) return height;
  const source = Image.resolveAssetSource(asset);
  if (source === undefined || source.height === 0) return height;
  return Math.round((source.width / source.height) * height);
}

/**
 * `[s:分类:名称]`。查表三级兜底照 06 票的约定:随包图片 → CDN 远程 URL → 原文
 * (`resolveSmiley` 已经把三种情况分好,这里只负责画)。
 *
 * 用 RN 的 `Image` 而不是 expo-image:表情要嵌在 `<Text>` 里跟文字混排,
 * 只有 RN 自带的 Image 在 Android 上会被当成 ImageSpan 处理。
 */
export const Smiley = memo(function Smiley({ code }: { code: string }) {
  const styles = useStyles();
  const height = useSmileyHeight();
  const smiley = resolveSmiley(code);

  if (smiley.kind === 'unresolved') {
    return <Text style={styles.raw}>{smiley.raw}</Text>;
  }

  const asset = SMILEY_ASSETS[smiley.file];
  return (
    <Image
      source={asset ?? { uri: smiley.remoteUrl }}
      style={{ width: widthOf(asset, height), height }}
      resizeMode="contain"
      accessibilityIgnoresInvertColors
    />
  );
});

const useStyles = createThemedStyles((theme) => ({
  /** 查不到的表情原样显示原文,用次级色标出来「这不是正文」 */
  raw: {
    color: theme.colors.meta,
  },
}));

import { Image } from 'expo-image';
import { memo, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import type { Board } from '@/core/api';

import { initialOf } from './initial';
import { createThemedStyles, useTheme } from './theme';

/** 设计稿:32 见方的圆形图标。 */
const ICON_SIZE = 32;
/** 占位底纹的条纹周期(设计稿 repeating-linear-gradient 的 6px,垂直于条纹量) */
const STRIPE_PERIOD = 6;
/** 条纹转了 45°,水平方向的间距要放大 √2 才等于设计稿那个垂直周期 */
const STRIPE_STEP = STRIPE_PERIOD * Math.SQRT2;

/**
 * 斜纹底。RN 没有 repeating-linear-gradient,项目也没装渐变库,
 * 所以用几条旋转 45° 的细线铺出同样的纹理——只有没图标/加载失败的格子才会画。
 */
const StripedBackground = memo(function StripedBackground({ color }: { color: string }) {
  // 线条绕自身中心转 45°,两侧各要多铺一个图标宽度才不会露出空角
  const count = Math.ceil((ICON_SIZE * 2) / STRIPE_STEP);
  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="none">
      {Array.from({ length: count }, (_, index) => (
        <View
          key={index}
          style={{
            position: 'absolute',
            top: -ICON_SIZE / 2,
            left: index * STRIPE_STEP - ICON_SIZE / 2,
            width: 1,
            height: ICON_SIZE * 2,
            backgroundColor: color,
            transform: [{ rotate: '45deg' }],
          }}
        />
      ))}
    </View>
  );
});

/**
 * 版块图标。服务端登记过图标就远程加载(expo-image 带磁盘缓存),
 * 没登记或加载失败一律回落到设计稿那个「斜纹圆底 + 首字」的占位。
 */
export function BoardIcon({ board }: { board: Board }) {
  const styles = useStyles();
  const theme = useTheme();
  const [failed, setFailed] = useState(false);

  if (board.iconUrl === undefined || failed) {
    return (
      <View style={styles.placeholder}>
        <StripedBackground color={theme.colors.surface} />
        <Text style={styles.initial} allowFontScaling={false}>
          {initialOf(board.name)}
        </Text>
      </View>
    );
  }

  return (
    <Image
      source={{ uri: board.iconUrl }}
      style={styles.icon}
      contentFit="contain"
      // memory-disk 而不是 disk:32 见方的小图,一屏三十来个、切 tab 来回换,
      // disk 档每次上屏都要重新读盘 + 解码。解码后一张才 4KB 上下,进内存很划算
      cachePolicy="memory-disk"
      transition={120}
      // 列表复用时换 id 就重新走一遍加载,不会串图
      recyclingKey={String(board.id)}
      onError={() => setFailed(true)}
      accessibilityIgnoresInvertColors
    />
  );
}

const useStyles = createThemedStyles((theme) => ({
  icon: {
    width: ICON_SIZE,
    height: ICON_SIZE,
    borderRadius: ICON_SIZE / 2,
  },
  placeholder: {
    width: ICON_SIZE,
    height: ICON_SIZE,
    borderRadius: ICON_SIZE / 2,
    borderWidth: 1,
    borderColor: theme.colors.accent,
    backgroundColor: theme.colors.surface2,
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  },
  initial: {
    ...theme.typography.initial,
    color: theme.colors.fg2,
  },
}));

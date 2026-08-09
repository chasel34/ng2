import { useEffect, useRef } from 'react';
import { Animated, Pressable, StyleSheet, type ViewStyle } from 'react-native';

import { duration, easeStandard, POP_SCALE } from './motion';
import { createThemedStyles } from './theme';

/**
 * 对话框类浮层的入场动效。
 *
 * 设计稿(952–953 行)把遮罩和面板分成两个动画:遮罩 `omfade .18s`、面板
 * `ompop .2s`,两条同时起跑、遮罩先落。五个对话框原本各写各的 `Animated.timing`,
 * 而且遮罩都是静态底色(开框瞬间满黑,只有面板在弹)——收敛到这里。
 */

export interface OverlayAnimation {
  /** 遮罩的 0→1 */
  readonly scrim: Animated.Value;
  /** 面板的 0→1(同时驱动 opacity 与 scale) */
  readonly panel: Animated.Value;
}

export function useOverlayAnimation(open: boolean): OverlayAnimation {
  const scrim = useRef(new Animated.Value(0)).current;
  const panel = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    if (!open) {
      // 关的时候直接归零:对话框是条件渲染的,退场动画放不出来
      scrim.setValue(0);
      panel.setValue(0);
      return;
    }
    const animation = Animated.parallel([
      Animated.timing(scrim, {
        toValue: 1,
        duration: duration.quick,
        easing: easeStandard,
        useNativeDriver: true,
      }),
      Animated.timing(panel, {
        toValue: 1,
        duration: duration.base,
        easing: easeStandard,
        useNativeDriver: true,
      }),
    ]);
    animation.start();
    return () => animation.stop();
  }, [open, scrim, panel]);

  return { scrim, panel };
}

/** 面板的 ompop:淡入 + 从 .94 放到 1。 */
export function popStyle(panel: Animated.Value): Animated.WithAnimatedObject<ViewStyle> {
  return {
    opacity: panel,
    transform: [{ scale: panel.interpolate({ inputRange: [0, 1], outputRange: [POP_SCALE, 1] }) }],
  };
}

/** 淡入的遮罩,兼点击关闭。铺在对话框根容器里、面板下面。 */
export function OverlayScrim({
  progress,
  onPress,
}: {
  progress: Animated.Value;
  onPress: () => void;
}) {
  const styles = useStyles();
  return (
    <Animated.View style={[StyleSheet.absoluteFill, styles.scrim, { opacity: progress }]}>
      <Pressable style={StyleSheet.absoluteFill} onPress={onPress} accessibilityLabel="关闭对话框" />
    </Animated.View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  scrim: {
    backgroundColor: theme.colors.scrim,
  },
}));

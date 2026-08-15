import { useEffect } from 'react';
import { Pressable, StyleSheet, type ViewStyle } from 'react-native';
import Reanimated, {
  interpolate,
  useAnimatedStyle,
  useSharedValue,
  withTiming,
  type AnimatedStyle,
} from 'react-native-reanimated';

import { duration, easeStandardWorklet, POP_SCALE } from './motion';
import { createThemedStyles } from './theme';

/**
 * 对话框类浮层的入场动效。
 *
 * 设计稿(952–953 行)把遮罩和面板分成两个动画:遮罩 `omfade .18s`、面板
 * `ompop .2s`,两条同时起跑、遮罩先落。五个对话框原本各写各的 `Animated.timing`,
 * 而且遮罩都是静态底色(开框瞬间满黑,只有面板在弹)——收敛到这里。
 *
 * 动画引擎必须是 Reanimated,不是 RN 自带 Animated(60fps 量化,CLAUDE.md「动画」节)。
 */

export interface OverlayAnimation {
  /** 遮罩的淡入样式,交给 `OverlayScrim` */
  readonly scrimStyle: AnimatedStyle<ViewStyle>;
  /** 面板的 ompop 样式(淡入 + 从 .94 放到 1),叠在面板自己的静态样式后面 */
  readonly panelStyle: AnimatedStyle<ViewStyle>;
}

export function useOverlayAnimation(open: boolean): OverlayAnimation {
  const scrim = useSharedValue(0);
  const panel = useSharedValue(0);

  useEffect(() => {
    if (!open) {
      // 关的时候直接归零:对话框是条件渲染的,退场动画放不出来
      scrim.value = 0;
      panel.value = 0;
      return;
    }
    scrim.value = withTiming(1, { duration: duration.quick, easing: easeStandardWorklet });
    panel.value = withTiming(1, { duration: duration.base, easing: easeStandardWorklet });
  }, [open, scrim, panel]);

  const scrimStyle = useAnimatedStyle(() => ({ opacity: scrim.value }));
  const panelStyle = useAnimatedStyle(() => ({
    opacity: panel.value,
    transform: [{ scale: interpolate(panel.value, [0, 1], [POP_SCALE, 1]) }],
  }));

  return { scrimStyle, panelStyle };
}

/** 淡入的遮罩,兼点击关闭。铺在对话框根容器里、面板下面。 */
export function OverlayScrim({
  style,
  onPress,
}: {
  style: AnimatedStyle<ViewStyle>;
  onPress: () => void;
}) {
  const styles = useStyles();
  return (
    <Reanimated.View style={[StyleSheet.absoluteFill, styles.scrim, style]}>
      <Pressable style={StyleSheet.absoluteFill} onPress={onPress} accessibilityLabel="关闭对话框" />
    </Reanimated.View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  scrim: {
    backgroundColor: theme.colors.scrim,
  },
}));

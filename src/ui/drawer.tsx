import { useEffect, useRef, type ReactNode } from 'react';
import { BackHandler, PanResponder, Pressable, StyleSheet, View } from 'react-native';
import Reanimated, {
  interpolate,
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';

import { duration, easeStandardWorklet } from './motion';
import { createThemedStyles } from './theme';

/**
 * 设计稿:抽屉宽 300,面板滑入 .22s、遮罩淡入 .2s。
 *
 * 面板走横推而不是设计稿 omup 的上浮——左抽屉的横推是 Android 的系统语言,
 * 也是「边缘右滑拉出、左滑关掉」那套手势成立的前提(手势拖的就是这个位移)。
 * 遮罩与面板共用一个 progress(设计稿是分开的两条 .2s / .22s):拖动时遮罩必须
 * 跟着手指一起深浅,拆开就对不上了,差的那 20ms 换手势跟手值。
 *
 * 动画引擎是 Reanimated 而不是 RN 自带的 Animated:RN `Animated.timing` 会把
 * 缓动曲线预采样成 60fps 关键帧,原生侧按 16.67ms 桶取值,120Hz 屏上位移与
 * 遮罩每两个 vsync 才动一步,肉眼就是「不连贯」(与 anzong 对拍实锤)。
 * `withTiming` 每个 vsync 现算,才吃得满 120Hz。
 */
const DRAWER_WIDTH = 300;
const OPEN_DURATION = duration.panel;
const CLOSE_DURATION = duration.base;
/** 左边缘多宽的一条可以拉出抽屉 */
const EDGE_WIDTH = 22;
/** 手势判定:横向位移超过这个值才认,免得和纵向滚动打架 */
const GESTURE_SLOP = 12;
/** 松手时超过这个比例(或甩得够快)就完成动作 */
const COMMIT_RATIO = 0.4;
const COMMIT_VELOCITY = 0.5;

export interface DrawerProps {
  open: boolean;
  onClose: () => void;
  children: ReactNode;
}

/**
 * 左侧抽屉:遮罩 + 300pt 面板,支持点遮罩关闭、左划关闭、系统返回键关闭。
 */
export function Drawer({ open, onClose, children }: DrawerProps) {
  const styles = useStyles();
  // 0 = 全关,1 = 全开
  const progress = useSharedValue(open ? 1 : 0);

  useEffect(() => {
    progress.value = withTiming(open ? 1 : 0, {
      duration: open ? OPEN_DURATION : CLOSE_DURATION,
      easing: easeStandardWorklet,
    });
  }, [open, progress]);

  useEffect(() => {
    if (!open) return;
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      onClose();
      return true;
    });
    return () => subscription.remove();
  }, [open, onClose]);

  const panResponder = useRef(
    PanResponder.create({
      // 只接横向左划;纵向留给面板里的滚动
      onMoveShouldSetPanResponder: (_event, gesture) =>
        gesture.dx < -GESTURE_SLOP && Math.abs(gesture.dx) > Math.abs(gesture.dy) * 1.3,
      onPanResponderMove: (_event, gesture) => {
        progress.value = Math.min(1, Math.max(0, 1 + gesture.dx / DRAWER_WIDTH));
      },
      onPanResponderRelease: (_event, gesture) => {
        const closing =
          -gesture.dx > DRAWER_WIDTH * COMMIT_RATIO || gesture.vx < -COMMIT_VELOCITY;
        if (closing) {
          onClose();
          return;
        }
        progress.value = withTiming(1, {
          duration: CLOSE_DURATION,
          easing: easeStandardWorklet,
        });
      },
    }),
  ).current;

  const scrimStyle = useAnimatedStyle(() => ({ opacity: progress.value }));
  const panelStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: interpolate(progress.value, [0, 1], [-DRAWER_WIDTH, 0]) }],
  }));

  return (
    <View
      style={StyleSheet.absoluteFill}
      pointerEvents={open ? 'auto' : 'none'}
      accessibilityElementsHidden={!open}
      importantForAccessibility={open ? 'yes' : 'no-hide-descendants'}
    >
      <Reanimated.View style={[styles.scrim, scrimStyle]}>
        <Pressable style={StyleSheet.absoluteFill} onPress={onClose} accessibilityLabel="关闭抽屉" />
      </Reanimated.View>
      <Reanimated.View
        {...panResponder.panHandlers}
        renderToHardwareTextureAndroid
        style={[styles.panel, panelStyle]}
      >
        {children}
      </Reanimated.View>
    </View>
  );
}

/**
 * 屏幕左边缘的一条拉出区。放在页面最外层的最后面,盖在内容上但只有 22pt 宽,
 * 不影响正常点击。
 */
export function DrawerEdgeHandle({ onOpen }: { onOpen: () => void }) {
  const styles = useStyles();
  const panResponder = useRef(
    PanResponder.create({
      onMoveShouldSetPanResponder: (_event, gesture) =>
        gesture.dx > GESTURE_SLOP && Math.abs(gesture.dx) > Math.abs(gesture.dy) * 1.3,
      onPanResponderRelease: (_event, gesture) => {
        if (gesture.dx > EDGE_WIDTH || gesture.vx > COMMIT_VELOCITY) onOpen();
      },
    }),
  ).current;

  return <View {...panResponder.panHandlers} style={styles.edge} />;
}

const useStyles = createThemedStyles((theme) => ({
  scrim: {
    position: 'absolute',
    left: 0,
    right: 0,
    top: 0,
    bottom: 0,
    backgroundColor: theme.colors.scrim,
  },
  panel: {
    position: 'absolute',
    left: 0,
    top: 0,
    bottom: 0,
    width: DRAWER_WIDTH,
    backgroundColor: theme.colors.surface,
    boxShadow: theme.shadows.elevation2,
  },
  edge: {
    position: 'absolute',
    left: 0,
    top: 0,
    bottom: 0,
    width: EDGE_WIDTH,
  },
}));

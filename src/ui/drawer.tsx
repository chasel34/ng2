import { useEffect, useRef, useState, type ReactNode } from 'react';
import {
  Animated,
  BackHandler,
  Easing,
  PanResponder,
  Pressable,
  StyleSheet,
  View,
} from 'react-native';

import { createThemedStyles } from './theme';

/** 设计稿:抽屉宽 300,滑入 .22s、遮罩淡入 .2s。 */
const DRAWER_WIDTH = 300;
const OPEN_DURATION = 220;
const CLOSE_DURATION = 200;
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
 *
 * 用 RN 自带的 Animated + PanResponder 而不是 reanimated/gesture-handler:
 * 位移和透明度都跑得动原生驱动,省掉一层 babel 插件与 New Arch 的适配假设。
 */
export function Drawer({ open, onClose, children }: DrawerProps) {
  const styles = useStyles();
  // 0 = 全关,1 = 全开
  const progress = useRef(new Animated.Value(0)).current;
  const [mounted, setMounted] = useState(open);

  useEffect(() => {
    if (open) setMounted(true);
    const animation = Animated.timing(progress, {
      toValue: open ? 1 : 0,
      duration: open ? OPEN_DURATION : CLOSE_DURATION,
      easing: Easing.out(Easing.cubic),
      useNativeDriver: true,
    });
    animation.start(({ finished }) => {
      // 关完再卸载,否则收起动画会被打断成瞬移
      if (finished && !open) setMounted(false);
    });
    return () => animation.stop();
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
        progress.setValue(Math.min(1, Math.max(0, 1 + gesture.dx / DRAWER_WIDTH)));
      },
      onPanResponderRelease: (_event, gesture) => {
        const closing =
          -gesture.dx > DRAWER_WIDTH * COMMIT_RATIO || gesture.vx < -COMMIT_VELOCITY;
        if (closing) {
          onClose();
          return;
        }
        Animated.timing(progress, {
          toValue: 1,
          duration: CLOSE_DURATION,
          easing: Easing.out(Easing.cubic),
          useNativeDriver: true,
        }).start();
      },
    }),
  ).current;

  if (!mounted) return null;

  return (
    <View style={StyleSheet.absoluteFill}>
      <Animated.View style={[styles.scrim, { opacity: progress }]}>
        <Pressable style={StyleSheet.absoluteFill} onPress={onClose} accessibilityLabel="关闭抽屉" />
      </Animated.View>
      <Animated.View
        {...panResponder.panHandlers}
        style={[
          styles.panel,
          {
            transform: [
              {
                translateX: progress.interpolate({
                  inputRange: [0, 1],
                  outputRange: [-DRAWER_WIDTH, 0],
                }),
              },
            ],
          },
        ]}
      >
        {children}
      </Animated.View>
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

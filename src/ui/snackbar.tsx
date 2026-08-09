import { useEffect, useRef } from 'react';
import { Animated, Pressable, Text, View } from 'react-native';
import { create } from 'zustand';

import { duration, easeStandard, RISE_OFFSET } from './motion';
import { createThemedStyles, useTheme } from './theme';
import { snackbarColors } from './tokens';

/** 设计稿:snack 条距底 92(给 FAB 让路)、左右 16,滑入走 omup 的 .22s。 */
const BOTTOM_OFFSET = 92;
const SHOW_DURATION = duration.panel;
/** 自动消失时长。设计稿的 mock 不会自己关,真机上带撤销的提示给 4 秒反应时间。 */
const AUTO_DISMISS_MS = 4000;

export interface SnackbarAction {
  label: string;
  onPress: () => void;
}

interface SnackbarItem {
  /** 每次 show 递增,同文案连点两次也会重置消失计时 */
  id: number;
  text: string;
  action?: SnackbarAction;
}

interface SnackbarState {
  current: SnackbarItem | null;
  show: (text: string, action?: SnackbarAction) => void;
  hide: () => void;
}

let nextId = 0;

const useSnackbar = create<SnackbarState>()((set) => ({
  current: null,
  show: (text, action) =>
    set({ current: { id: ++nextId, text, ...(action === undefined ? {} : { action }) } }),
  hide: () => set({ current: null }),
}));

/**
 * 弹一条设计稿样式的 snack 条(深底浅字 + 右侧一枚薄荷绿动作)。
 * 与 `showToast`(系统 ToastAndroid)的分工:需要带动作(撤销/打开/去登录)
 * 或要在浅深主题下与设计稿 1:1 的提示走这里;纯气泡提示维持原状。
 */
export function showSnackbar(text: string, action?: SnackbarAction): void {
  useSnackbar.getState().show(text, action);
}

/**
 * Snackbar 宿主,挂在根布局最外层——提示要盖在所有页面上,
 * 并且在发起它的页面退场后还能活着把「撤销」等到。
 */
export function SnackbarHost() {
  const styles = useStyles();
  const theme = useTheme();
  const current = useSnackbar((state) => state.current);
  const hide = useSnackbar((state) => state.hide);
  const progress = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    if (current === null) return;
    progress.setValue(0);
    const animation = Animated.timing(progress, {
      toValue: 1,
      duration: SHOW_DURATION,
      easing: easeStandard,
      useNativeDriver: true,
    });
    animation.start();
    const timer = setTimeout(hide, AUTO_DISMISS_MS);
    return () => {
      animation.stop();
      clearTimeout(timer);
    };
  }, [current, progress, hide]);

  if (current === null) return null;

  const bg = snackbarColors.bg[theme.scheme];

  return (
    <View style={styles.root} pointerEvents="box-none">
      <Animated.View
        style={[
          styles.panel,
          { backgroundColor: bg },
          {
            opacity: progress,
            transform: [
              { translateY: progress.interpolate({ inputRange: [0, 1], outputRange: [RISE_OFFSET, 0] }) },
            ],
          },
        ]}
      >
        <Text style={styles.text}>{current.text}</Text>
        {current.action !== undefined && (
          <Pressable
            onPress={() => {
              hide();
              current.action?.onPress();
            }}
            hitSlop={10}
            accessibilityRole="button"
            accessibilityLabel={current.action.label}
          >
            <Text style={styles.action}>{current.action.label}</Text>
          </Pressable>
        )}
      </Animated.View>
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  root: {
    position: 'absolute',
    left: theme.spacing.lg,
    right: theme.spacing.lg,
    bottom: BOTTOM_OFFSET,
  },
  panel: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.md,
    borderRadius: theme.radius.lg,
    paddingVertical: theme.spacing.row,
    paddingHorizontal: theme.spacing.lg,
    boxShadow: theme.shadows.elevation2,
  },
  // 设计稿:文字 13.5 · 1.4 行高,动作 13 · 700
  text: {
    ...theme.typography.notice,
    lineHeight: 18.9,
    color: snackbarColors.fg,
    flex: 1,
  },
  action: {
    fontSize: 13,
    fontWeight: '700',
    color: snackbarColors.action,
  },
}));

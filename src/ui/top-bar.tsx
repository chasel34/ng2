import type { ReactNode } from 'react';
import { Pressable, Text, View, type StyleProp, type ViewStyle } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { Icon, type IconName } from './icon';
import { createThemedStyles, useTheme } from './theme';

/** 设计稿:顶栏一行 54 高,圆形图标按钮 46 见方。 */
const BAR_HEIGHT = 54;
const BUTTON_SIZE = 46;

export interface TopBarProps {
  /** 顶栏那一行的内容,自左向右排 */
  children: ReactNode;
  /** 顶栏色块里、行下面的东西(首页的分组 tab、帖子详情的页码条) */
  below?: ReactNode;
  /** 设计稿里首页是 6、二级页是 4 */
  paddingHorizontal?: number;
}

/**
 * 顶栏色块。状态栏是透明的,所以顶栏自己撑开安全区高度——
 * 每个页面都这么干一遍太容易走形,统一放这儿。
 */
export function TopBar({ children, below, paddingHorizontal = 6 }: TopBarProps) {
  const styles = useStyles();
  const insets = useSafeAreaInsets();

  return (
    <View style={[styles.bar, { paddingTop: insets.top }]}>
      <View style={[styles.row, { paddingHorizontal }]}>{children}</View>
      {below}
    </View>
  );
}

export interface TopBarButtonProps {
  icon: IconName;
  /** 设计稿逐个标了图标字号:菜单/返回 24、搜索 23、更多 22–23 */
  size: number;
  onPress: () => void;
  accessibilityLabel: string;
  style?: StyleProp<ViewStyle>;
}

export function TopBarButton({
  icon,
  size,
  onPress,
  accessibilityLabel,
  style,
}: TopBarButtonProps) {
  const styles = useStyles();
  const theme = useTheme();

  return (
    <Pressable
      onPress={onPress}
      accessibilityLabel={accessibilityLabel}
      accessibilityRole="button"
      android_ripple={{ color: theme.colors.onTopbar, borderless: true, radius: BUTTON_SIZE / 2 }}
      style={[styles.button, style]}
    >
      <Icon name={icon} size={size} color={theme.colors.onTopbar} />
    </Pressable>
  );
}

/** 顶栏标题。`main` 是首页那档 18/600,二级页是 17/600。 */
export function TopBarTitle({
  children,
  variant = 'main',
}: {
  children: ReactNode;
  variant?: 'main' | 'sub';
}) {
  const styles = useStyles();
  return (
    <Text style={variant === 'main' ? styles.title : styles.subTitle} numberOfLines={1}>
      {children}
    </Text>
  );
}

/** 把右侧按钮推到底,与设计稿的 `margin-left:auto` 对应。 */
export const topBarSpacer = { marginLeft: 'auto' } as const;

const useStyles = createThemedStyles((theme) => ({
  bar: {
    backgroundColor: theme.colors.topbar,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    height: BAR_HEIGHT,
  },
  button: {
    width: BUTTON_SIZE,
    height: BUTTON_SIZE,
    borderRadius: theme.radius.full,
    alignItems: 'center',
    justifyContent: 'center',
  },
  title: {
    ...theme.typography.title,
    color: theme.colors.onTopbar,
    marginLeft: 6,
    flexShrink: 1,
  },
  subTitle: {
    ...theme.typography.section,
    fontWeight: '600',
    color: theme.colors.onTopbar,
    marginLeft: theme.spacing.xs,
    flexShrink: 1,
  },
}));

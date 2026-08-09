import type { ReactNode } from 'react';
import { Pressable, Text, View, type StyleProp, type ViewStyle } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { Icon, type IconName } from './icon';
import { createThemedStyles, useTheme } from './theme';

/**
 * 设计稿:顶栏一行 54 高。圆形图标按钮有 46 与 44 两档——最左边那枚(菜单/返回)
 * 一律 46,右侧的动作钮一律 44(例外:大图查看器的保存/分享是 46,只有更多是 44)。
 */
const BAR_HEIGHT = 54;
export type TopBarButtonBox = 44 | 46;
const DEFAULT_BUTTON_BOX: TopBarButtonBox = 44;

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
  /** 图标颜色,默认 onTopbar。带状态的按钮(列表页星标的已收藏态)用它点亮 */
  color?: string;
  /** 触控盒边长。返回/菜单那枚传 46,右侧动作钮不传(默认 44) */
  box?: TopBarButtonBox;
  style?: StyleProp<ViewStyle>;
}

export function TopBarButton({
  icon,
  size,
  onPress,
  accessibilityLabel,
  color,
  box = DEFAULT_BUTTON_BOX,
  style,
}: TopBarButtonProps) {
  const styles = useStyles();
  const theme = useTheme();

  return (
    <Pressable
      onPress={onPress}
      accessibilityLabel={accessibilityLabel}
      accessibilityRole="button"
      android_ripple={{ color: theme.colors.onTopbar, borderless: true, radius: box / 2 }}
      style={[box === 46 ? styles.button46 : styles.button44, style]}
    >
      <Icon name={icon} size={size} color={color ?? theme.colors.onTopbar} />
    </Pressable>
  );
}

/**
 * 顶栏标题。设计稿按屏分了三档:首页 18/600、二级页 17/600、
 * 帖子详情 16.5/600(它标题后面还跟着两枚图标,所以再矮半档)、
 * 网页兜底 15.5/600(那条顶栏下面还压着一行 URL)。
 */
export function TopBarTitle({
  children,
  variant = 'main',
  maxWidth,
}: {
  children: ReactNode;
  variant?: 'main' | 'sub' | 'article' | 'web';
  /** 设计稿给二级页标题标了截断宽度(列表 150、详情 190),不给就按剩余空间收缩 */
  maxWidth?: number;
}) {
  const styles = useStyles();
  return (
    <Text
      style={[styles[TITLE_STYLE[variant]], maxWidth === undefined ? null : { maxWidth }]}
      numberOfLines={1}
    >
      {children}
    </Text>
  );
}

const TITLE_STYLE = {
  main: 'title',
  sub: 'subTitle',
  article: 'articleTitle',
  web: 'webTitle',
} as const;

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
  button46: {
    width: 46,
    height: 46,
    borderRadius: theme.radius.full,
    alignItems: 'center',
    justifyContent: 'center',
  },
  button44: {
    width: 44,
    height: 44,
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
  articleTitle: {
    ...theme.typography.articleTitle,
    color: theme.colors.onTopbar,
    marginLeft: theme.spacing.xs,
    flexShrink: 1,
  },
  webTitle: {
    ...theme.typography.webTitle,
    color: theme.colors.onTopbar,
    flexShrink: 1,
  },
}));

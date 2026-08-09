import { ActivityIndicator, Pressable, Text, View } from 'react-native';

import { Icon, type IconName } from './icon';
import { createThemedStyles, useTheme } from './theme';

/**
 * 空态与加载态的统一口径。
 *
 * 设计稿没有单独画空屏,但 isError 那一屏定下了这套语言:一枚 meta 色的大图标 +
 * 居中说明文字(+ 可选的一个出路)。各屏原本各写各的 `styles.empty`,尺寸从
 * 36 到 44、间距从 8 到 16 都有——这里收成两个组件,页面只挑图标和文案。
 *
 * 与 `error-screen.tsx` 的分工:那边是「拉失败了」(有错误对象、要给重试/网页版/
 * 重新登录),这边是「拉成功但没有内容」和「正在拉」。
 */

/** 设计稿 isError 的图标是 34,空态没有那圈 72 的底,所以放大到 40 撑住版面。 */
const EMPTY_ICON_SIZE = 40;

export interface EmptyStateAction {
  label: string;
  onPress: () => void;
}

export interface EmptyStateProps {
  icon: IconName;
  /** 一句话说清「这儿为什么是空的」。要换行就直接在文案里写 `\n`,第二行是补充说明 */
  text: string;
  /** 可选的出路(去登录 / 去看看 / 新建一个) */
  action?: EmptyStateAction;
  /**
   * `screen` 撑满剩余高度并垂直居中(整屏没内容时用);
   * `inline` 只占一段固定高度(嵌在列表里、上面还有筛选条或分组头时用)。
   */
  variant?: 'screen' | 'inline';
}

/** 「这儿还没有内容」。 */
export function EmptyState({ icon, text, action, variant = 'screen' }: EmptyStateProps) {
  const styles = useStyles();
  const theme = useTheme();

  return (
    <View style={variant === 'screen' ? styles.screen : styles.inline}>
      <Icon name={icon} size={EMPTY_ICON_SIZE} color={theme.colors.meta} />
      <Text style={styles.text}>{text}</Text>
      {action !== undefined && (
        <Pressable
          style={styles.action}
          onPress={action.onPress}
          accessibilityRole="button"
          accessibilityLabel={action.label}
        >
          <Text style={styles.actionLabel}>{action.label}</Text>
        </Pressable>
      )}
    </View>
  );
}

export interface LoadingStateProps {
  /** 转圈下面的一行字。首屏加载通常不给——转圈本身已经说明问题了 */
  text?: string;
  variant?: 'screen' | 'inline';
}

/** 「正在拉」。整屏首次加载与列表内的分段加载共用同一个转圈。 */
export function LoadingState({ text, variant = 'screen' }: LoadingStateProps) {
  const styles = useStyles();
  const theme = useTheme();

  return (
    <View style={variant === 'screen' ? styles.screen : styles.inline}>
      <ActivityIndicator color={theme.colors.primary} />
      {text !== undefined && <Text style={styles.text}>{text}</Text>}
    </View>
  );
}

/**
 * 列表底部「正在载入下一页」的那一行。
 *
 * 设计稿(isList 底部提示)是一行 12.5 的 meta 字,不带转圈;翻页时把转圈也带上,
 * 但整行高度维持设计稿的 20 内距,免得列表底部跳一下。
 */
export function LoadingFooter({ text }: { text: string }) {
  const styles = useStyles();
  const theme = useTheme();

  return (
    <View style={styles.footer}>
      <ActivityIndicator size="small" color={theme.colors.meta} />
      <Text style={styles.footerText}>{text}</Text>
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  screen: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: theme.spacing.md,
    padding: theme.spacing.xl,
  },
  /** 与 `LoadFailedNotice` 的纵向 56 对齐,列表里两种块换着出现时高度不跳 */
  inline: {
    alignItems: 'center',
    gap: theme.spacing.md,
    paddingVertical: 56,
    paddingHorizontal: theme.spacing.xl,
  },
  text: {
    ...theme.typography.notice,
    color: theme.colors.fg2,
    textAlign: 'center',
  },
  /** 出路按钮照 LoadFailedNotice 的重试钮:40 高的胶囊 */
  action: {
    height: 40,
    paddingHorizontal: theme.spacing.xl,
    borderRadius: theme.radius.full,
    backgroundColor: theme.colors.primary,
    alignItems: 'center',
    justifyContent: 'center',
  },
  actionLabel: {
    ...theme.typography.drawerItem,
    fontWeight: '600',
    color: theme.colors.onPrimary,
  },
  /** 设计稿 isList 底部提示:纵向 20、居中、12.5 meta */
  footer: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: theme.spacing.sm,
    paddingVertical: theme.spacing.xl,
  },
  footerText: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
  },
}));

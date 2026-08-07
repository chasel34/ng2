import { useState, type ReactNode } from 'react';
import { Pressable, Text, View, type StyleProp, type ViewStyle } from 'react-native';

import { Icon, type IconName } from './icon';
import { createThemedStyles, useTheme } from './theme';

/**
 * 「一行提要 + 点开才显示内容」的那种块。
 *
 * 正文里有三处长这样:`[collapse]` 折叠块、`[lessernuke]` 版规处罚提示、`[album]` 相册。
 * 三者默认都收起——折叠块是作者主动要藏,相册和处罚内容则是不该一进楼就拉图/铺开,
 * 与楼层附件宫格同一条理由(设计稿把折叠按钮定成 surface-2 底 + radius/md)。
 */
export interface CollapsibleCardProps {
  icon: IconName;
  /** 提要行的文字 */
  title: string;
  /** 展开前的行动文案,如「点击展开」「点击查看」 */
  openLabel: string;
  /** 收起状态下不画内容,所以内容用回调给,省得白算一遍 */
  children: () => ReactNode;
  /** 提要行的文字颜色;默认 fg2,版规处罚块给 danger */
  tone?: 'normal' | 'danger';
  style?: StyleProp<ViewStyle>;
}

export function CollapsibleCard({
  icon,
  title,
  openLabel,
  children,
  tone = 'normal',
  style,
}: CollapsibleCardProps) {
  const styles = useStyles();
  const theme = useTheme();
  const [open, setOpen] = useState(false);
  const accent = tone === 'danger' ? theme.colors.danger : theme.colors.fg2;

  return (
    <View style={[tone === 'danger' ? styles.danger : styles.card, style]}>
      <Pressable
        style={styles.header}
        onPress={() => setOpen((shown) => !shown)}
        accessibilityRole="button"
        accessibilityState={{ expanded: open }}
      >
        <Icon name={icon} size={17} color={accent} />
        <Text style={[styles.title, { color: accent }]} numberOfLines={2}>
          {title}
        </Text>
        <Text style={styles.action}>{open ? '收起' : openLabel}</Text>
      </Pressable>
      {open && <View style={styles.body}>{children()}</View>}
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  card: {
    marginTop: 11,
    borderRadius: theme.radius.md,
    backgroundColor: theme.colors.surface2,
    overflow: 'hidden',
  },
  danger: {
    marginTop: 11,
    borderRadius: theme.radius.md,
    borderWidth: 1,
    borderColor: theme.colors.danger,
    overflow: 'hidden',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    minHeight: 42,
    paddingVertical: theme.spacing.sm,
    paddingHorizontal: theme.spacing.md,
  },
  title: {
    ...theme.typography.notice,
    fontWeight: '600',
    flex: 1,
  },
  action: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
  },
  body: {
    paddingHorizontal: theme.spacing.md,
    paddingBottom: theme.spacing.md,
  },
}));

import { Pressable, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { useThemeMode, type ThemeMode } from '@/store/theme';
import { createThemedStyles, useTheme } from '@/ui/theme';

const TABS = ['我的收藏', '魔兽世界', '网事杂谈'];

const MODES: ReadonlyArray<readonly [ThemeMode, string]> = [
  ['system', '跟随系统'],
  ['light', '浅色'],
  ['dark', '深色'],
];

/**
 * M1 骨架页,同时充当 token 样板:顶栏、Tab、公告条、卡片各取一档颜色/字号/圆角/阴影,
 * 后续页面照着这里的取值方式写。真正的首页由 04(版块树+抽屉)接管。
 */
export default function Home() {
  const theme = useTheme();
  const styles = useStyles();
  const insets = useSafeAreaInsets();
  const mode = useThemeMode((state) => state.mode);
  const setMode = useThemeMode((state) => state.setMode);

  return (
    <View style={styles.screen}>
      <View style={[styles.topbar, { paddingTop: insets.top }]}>
        <View style={styles.appbar}>
          <Text style={styles.appbarTitle}>NGA 阅读器</Text>
        </View>
        <View style={styles.tabs}>
          {TABS.map((tab, index) => (
            <View key={tab} style={[styles.tab, index === 0 && styles.tabActive]}>
              <Text style={[styles.tabLabel, index !== 0 && styles.tabLabelIdle]}>{tab}</Text>
            </View>
          ))}
        </View>
      </View>

      <View style={styles.notice}>
        <View style={styles.noticeMark} />
        <Text style={styles.noticeText}>建议登录多个账号,可有效改善跳转系统浏览器的问题</Text>
      </View>

      <View style={styles.body}>
        <View style={styles.card}>
          <Text style={styles.cardTitle}>M1 骨架 · 等待首页实现</Text>
          <Text style={styles.cardBody}>
            版块树与抽屉见 04,主题列表见 05。所有颜色、字号、圆角、阴影都取自 src/ui/tokens.ts。
          </Text>
          <Text style={styles.cardMeta}>
            当前配色 {theme.scheme === 'dark' ? '深色' : '浅色'} · 模式{' '}
            {MODES.find(([value]) => value === mode)?.[1]}
          </Text>
        </View>

        <View style={styles.modes}>
          {MODES.map(([value, label]) => (
            <Pressable
              key={value}
              onPress={() => setMode(value)}
              style={[styles.chip, value === mode && styles.chipActive]}
            >
              <Text style={[styles.chipLabel, value === mode && styles.chipLabelActive]}>
                {label}
              </Text>
            </Pressable>
          ))}
        </View>
      </View>
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  screen: {
    flex: 1,
    backgroundColor: theme.colors.bg,
  },
  topbar: {
    backgroundColor: theme.colors.topbar,
  },
  appbar: {
    height: 54,
    justifyContent: 'center',
    paddingHorizontal: theme.spacing.page,
  },
  appbarTitle: {
    ...theme.typography.title,
    color: theme.colors.onTopbar,
  },
  tabs: {
    flexDirection: 'row',
    paddingHorizontal: theme.spacing.sm,
  },
  tab: {
    height: 44,
    justifyContent: 'center',
    paddingHorizontal: theme.spacing.lg,
    borderBottomWidth: 3,
    borderBottomColor: 'transparent',
  },
  tabActive: {
    borderBottomColor: theme.colors.onTopbar,
  },
  tabLabel: {
    ...theme.typography.tab,
    color: theme.colors.onTopbar,
  },
  tabLabelIdle: {
    opacity: 0.62,
  },
  notice: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.md,
    paddingVertical: theme.spacing.row,
    paddingHorizontal: theme.spacing.page,
    backgroundColor: theme.colors.surface2,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  // 设计稿此处是 Material Symbols 的 campaign 图标,图标字体尚未接入,先用 accent 色条占位
  noticeMark: {
    width: 3,
    height: 16,
    borderRadius: theme.radius.full,
    backgroundColor: theme.colors.accent,
  },
  noticeText: {
    ...theme.typography.note,
    flex: 1,
    color: theme.colors.fg2,
  },
  body: {
    flex: 1,
    gap: theme.spacing.lg,
    padding: theme.spacing.page,
  },
  card: {
    gap: theme.spacing.sm,
    padding: theme.spacing.lg,
    borderRadius: theme.radius.lg,
    backgroundColor: theme.colors.surface,
    boxShadow: theme.shadows.elevation1,
  },
  cardTitle: {
    ...theme.typography.listTitle,
    color: theme.colors.fg,
  },
  cardBody: {
    ...theme.typography.body,
    color: theme.colors.fg2,
  },
  cardMeta: {
    ...theme.typography.meta,
    color: theme.colors.meta,
  },
  modes: {
    flexDirection: 'row',
    gap: theme.spacing.sm,
  },
  chip: {
    paddingVertical: theme.spacing.sm,
    paddingHorizontal: theme.spacing.md,
    borderRadius: theme.radius.sm,
    backgroundColor: theme.colors.surface2,
  },
  chipActive: {
    backgroundColor: theme.colors.primaryContainer,
  },
  chipLabel: {
    ...theme.typography.note,
    color: theme.colors.fg2,
  },
  chipLabelActive: {
    color: theme.colors.primary,
  },
}));

import { Pressable, ScrollView, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { Icon, type IconName } from './icon';
import { createThemedStyles, useTheme } from './theme';
import { showNotAvailable } from './toast';

interface DrawerEntry {
  key: string;
  icon: IconName;
  label: string;
  onPress: () => void;
}

/**
 * 抽屉条目,顺序与图标照抄设计稿。
 *
 * 每一项背后的页面都还没做(登录 09、收藏夹 11、通知 13、设置与关于 22、由 URL 读取 24),
 * 所以现在一律 toast「本版本未开放」——入口先立在这儿,后续票各自换掉自己那一行的 onPress。
 */
const ENTRIES: readonly DrawerEntry[] = [
  { key: 'login', icon: 'person_add', label: '登录账号', onPress: showNotAvailable },
  { key: 'add-board', icon: 'library_add', label: '添加版面 ID', onPress: showNotAvailable },
  { key: 'from-url', icon: 'arrow_forward', label: '由 URL 读取', onPress: showNotAvailable },
  { key: 'folders', icon: 'folder_special', label: '收藏夹管理', onPress: showNotAvailable },
  { key: 'clear-favor', icon: 'warning', label: '清空我的收藏', onPress: showNotAvailable },
  { key: 'notifications', icon: 'notifications_active', label: '最近被喷', onPress: showNotAvailable },
  { key: 'settings', icon: 'settings', label: '设置', onPress: showNotAvailable },
  { key: 'about', icon: 'info', label: '关于', onPress: showNotAvailable },
];

/**
 * 抽屉正文。头部是账号区——多账号左右滑动切换要等 09,
 * 现在固定渲染未登录态,点一下同样 toast。
 */
export function AppDrawerContent() {
  const styles = useStyles();
  const theme = useTheme();
  const insets = useSafeAreaInsets();

  return (
    <ScrollView
      style={styles.root}
      contentContainerStyle={styles.content}
      showsVerticalScrollIndicator={false}
    >
      <View style={[styles.header, { paddingTop: insets.top + 22 }]}>
        <View style={styles.avatarRow}>
          <View style={styles.avatar}>
            <Icon name="person_add" size={26} color={theme.colors.onPrimary} />
          </View>
        </View>
        <Text style={styles.headerCaption}>未登录 · 登录多个账号可少跳系统浏览器</Text>
        <Text style={styles.headerTitle} onPress={showNotAvailable}>
          点此登录账号
        </Text>
      </View>

      <Text style={styles.sectionCaption}>论坛功能</Text>
      {ENTRIES.map((entry) => (
        <Pressable
          key={entry.key}
          style={styles.entry}
          onPress={entry.onPress}
          android_ripple={{ color: theme.colors.divider }}
        >
          <Icon name={entry.icon} size={21} color={theme.colors.fg2} />
          <Text style={styles.entryLabel} numberOfLines={1}>
            {entry.label}
          </Text>
        </Pressable>
      ))}
      <View style={styles.tail} />
    </ScrollView>
  );
}

const useStyles = createThemedStyles((theme) => ({
  root: {
    flex: 1,
  },
  content: {
    paddingBottom: theme.spacing.xl,
  },
  header: {
    backgroundColor: theme.colors.primary,
    paddingHorizontal: theme.spacing.xl,
    paddingBottom: 18,
  },
  avatarRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.row,
  },
  avatar: {
    width: 64,
    height: 64,
    borderRadius: 22,
    backgroundColor: 'rgba(255, 255, 255, 0.22)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  headerCaption: {
    ...theme.typography.note,
    color: theme.colors.onPrimary,
    opacity: 0.8,
    marginTop: theme.spacing.row,
  },
  headerTitle: {
    ...theme.typography.tab,
    color: theme.colors.onPrimary,
    marginTop: 3,
  },
  sectionCaption: {
    ...theme.typography.caption,
    color: theme.colors.primary,
    paddingTop: theme.spacing.lg,
    paddingHorizontal: theme.spacing.xl,
    paddingBottom: 6,
  },
  entry: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 18,
    height: 52,
    paddingHorizontal: theme.spacing.xl,
  },
  entryLabel: {
    ...theme.typography.drawerItem,
    color: theme.colors.fg,
  },
  tail: {
    height: theme.spacing.xl,
  },
}));

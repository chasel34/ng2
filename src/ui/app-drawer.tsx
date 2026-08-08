import { useRouter, type Href } from 'expo-router';
import { useRef } from 'react';
import { PanResponder, Pressable, ScrollView, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { currentAccountOf, cycleAccountUid } from '@/core/account';
import type { UserPostKind } from '@/core/api';
import { useAccounts } from '@/store/accounts';
import { useCheckIn, useCheckedInToday } from '@/store/check-in';
import { useNotificationsUnread } from '@/store/notifications';

import { Icon, type IconName } from './icon';
import { nameAbbrev } from './initial';
import { showLoginPrompt } from './login-prompt';
import { createThemedStyles, useTheme } from './theme';
import { showNotAvailable, showToast } from './toast';
import { topbarOverlay } from './tokens';

/** 手势判定:横向位移超过这个值才认,免得和纵向滚动打架。 */
const GESTURE_SLOP = 12;
/** 松手时滑过这么远(或甩得够快)才算一次切换。 */
const SWIPE_COMMIT = 40;
const SWIPE_VELOCITY = 0.5;

/**
 * 抽屉头部左右滑动切换当前账号(设计稿:「左右滑动切换」)。
 * 只读 getState,放在组件外让 PanResponder 保持稳定。
 */
function swipeAccount(step: 1 | -1): void {
  const state = useAccounts.getState();
  const uid = cycleAccountUid(state, step);
  if (uid === null) return;
  state.switchTo(uid);
  const name = state.accounts.find((account) => account.uid === uid)?.name ?? `UID ${uid}`;
  showToast(`已切换到 ${name}`);
}

interface DrawerEntry {
  key: string;
  icon: IconName;
  label: string;
  /** 落在真实路由上的条目;没有就走 showNotAvailable */
  href?: Href;
  /** 右侧未读角标(设计稿短消息屏那颗红底数字);目前只有通知入口有 */
  badge?: 'notifications';
  /** 右侧的状态灰字;目前只有签到入口有(今天签没签) */
  status?: 'check-in';
  /** 「我的主题」「我的回复」:目标 uid 是当前账号,登录后才知道,所以只记 kind */
  mine?: UserPostKind;
}

/**
 * 抽屉条目,顺序与图标照抄设计稿。
 *
 * 还没做的页面(设置与关于 22)
 * 一律 toast「本版本未开放」——入口先立在这儿,后续票各自换掉自己那一行。
 */
const ENTRIES: readonly DrawerEntry[] = [
  { key: 'login', icon: 'person_add', label: '登录账号', href: '/login' },
  // 签到是设计稿缺失页面(spec §1:抽屉「登录账号」下加一行),按现有条目的设计语言延伸
  { key: 'check-in', icon: 'workspace_premium', label: '每日签到', status: 'check-in' },
  { key: 'add-board', icon: 'library_add', label: '添加版面 ID' },
  { key: 'from-url', icon: 'arrow_forward', label: '由 URL 读取' },
  { key: 'folders', icon: 'folder_special', label: '收藏夹管理', href: '/favorites/folders' },
  { key: 'clear-favor', icon: 'warning', label: '清空我的收藏' },
  // 我的主题/我的回复是同一个屏,只差一个 kind(14 票)
  { key: 'my-topics', icon: 'article', label: '我的主题', mine: 'topics' },
  { key: 'my-replies', icon: 'reply', label: '我的回复', mine: 'replies' },
  // 设计稿把「我的缓存」放在首页菜单里(MENUS.home),抽屉这条是顺手的第二入口——
  // 抽屉在每一屏都拉得出来,不必先退回首页再开菜单
  { key: 'caches', icon: 'cached', label: '我的缓存', href: '/caches' },
  {
    key: 'notifications',
    icon: 'notifications_active',
    label: '最近被喷',
    href: '/notifications',
    badge: 'notifications',
  },
  { key: 'settings', icon: 'settings', label: '设置' },
  { key: 'about', icon: 'info', label: '关于' },
];

export interface AppDrawerContentProps {
  /** 点条目要跳页时先关抽屉(不关的话返回时抽屉还敞着) */
  onNavigate?: () => void;
  /** 「添加版面 ID」(10 票):设计稿是关抽屉再由宿主页面弹输入框 */
  onAddBoard?: () => void;
  /** 「清空我的收藏」(10 票):同上,宿主页面弹确认框 */
  onClearFavorites?: () => void;
  /** 「由 URL 读取」(24 票):同上,宿主页面弹粘链接的输入框 */
  onOpenUrl?: () => void;
}

/**
 * 抽屉正文。头部是账号区:游客态提示登录,登录后展示当前账号,
 * 左右滑动(或点两侧箭头)切换,点头像/账号名进账号管理页。
 */
export function AppDrawerContent({
  onNavigate,
  onAddBoard,
  onClearFavorites,
  onOpenUrl,
}: AppDrawerContentProps) {
  const styles = useStyles();
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const accounts = useAccounts((state) => state.accounts);
  const currentUid = useAccounts((state) => state.currentUid);
  const current = currentAccountOf({ accounts, currentUid });
  const unread = useNotificationsUnread();
  const checkedInToday = useCheckedInToday(currentUid);
  const checkInPending = useCheckIn((state) => state.pendingUid === currentUid);

  const go = (href: Href) => {
    onNavigate?.();
    router.push(href);
  };

  /** 「我的主题/我的回复」查的是当前账号,游客态没有 uid 可查 */
  const openMine = (kind: UserPostKind) => {
    if (current === null) {
      onNavigate?.();
      showLoginPrompt(router, '登录后才能看自己的主题与回复');
      return;
    }
    onNavigate?.();
    router.push({
      pathname: '/user/posts',
      params: { uid: current.uid, kind, name: current.name },
    });
  };

  /**
   * 每日签到(CONTEXT.md「签到」)。抽屉不关:签完那行就地变成「今天已签到」,
   * 关掉再弹提示反而看不见结果。
   *
   * 「今天已经签到」服务端当假错误回,这里和成功一样处理,只换一句话说。
   */
  const checkInNow = () => {
    if (current === null) {
      onNavigate?.();
      showLoginPrompt(router, '登录后才能签到');
      return;
    }
    void useCheckIn
      .getState()
      .checkIn(current.uid)
      .then(
        (outcome) => {
          // 本地记录已是今天、或上一次还在途,都不该再打接口(重复点击就落在这两支)
          if (outcome.kind === 'in-flight') return;
          if (outcome.kind === 'already-today') {
            showToast('今天已经签到过了');
            return;
          }
          showToast(
            outcome.result.alreadyCheckedIn
              ? '今天已经签到过了'
              : (outcome.result.message ?? '签到成功'),
          );
        },
        (error: unknown) => showToast(error instanceof Error ? error.message : '签到失败'),
      );
  };

  const headerPan = useRef(
    PanResponder.create({
      onMoveShouldSetPanResponder: (_event, gesture) =>
        Math.abs(gesture.dx) > GESTURE_SLOP &&
        Math.abs(gesture.dx) > Math.abs(gesture.dy) * 1.3,
      onPanResponderRelease: (_event, gesture) => {
        // 左滑看下一个,右滑看上一个,循环
        if (gesture.dx <= -SWIPE_COMMIT || gesture.vx < -SWIPE_VELOCITY) swipeAccount(1);
        else if (gesture.dx >= SWIPE_COMMIT || gesture.vx > SWIPE_VELOCITY) swipeAccount(-1);
      },
    }),
  ).current;

  return (
    <ScrollView
      style={styles.root}
      contentContainerStyle={styles.content}
      showsVerticalScrollIndicator={false}
    >
      {current === null ? (
        <View style={[styles.header, { paddingTop: insets.top + 22 }]}>
          <View style={styles.avatarRow}>
            <View style={styles.avatar}>
              <Icon name="person_add" size={26} color={theme.colors.onPrimary} />
            </View>
          </View>
          <Text style={styles.headerCaption}>未登录 · 登录多个账号可少跳系统浏览器</Text>
          <Text style={styles.headerTitle} onPress={() => go('/login')}>
            点此登录账号
          </Text>
        </View>
      ) : (
        <View
          style={[styles.header, { paddingTop: insets.top + 22 }]}
          {...headerPan.panHandlers}
        >
          <View style={styles.avatarRow}>
            <Pressable
              onPress={() => swipeAccount(-1)}
              hitSlop={10}
              disabled={accounts.length < 2}
              accessibilityLabel="上一个账号"
            >
              <Icon name="chevron_left" size={18} color={theme.colors.onPrimary} style={styles.chevron} />
            </Pressable>
            <Pressable
              style={styles.avatar}
              onPress={() => go('/accounts')}
              accessibilityLabel="账号管理"
            >
              <Text style={styles.avatarText} allowFontScaling={false}>
                {nameAbbrev(current.name, 4)}
              </Text>
            </Pressable>
            <Pressable
              onPress={() => swipeAccount(1)}
              hitSlop={10}
              disabled={accounts.length < 2}
              accessibilityLabel="下一个账号"
            >
              <Icon name="chevron_right" size={18} color={theme.colors.onPrimary} style={styles.chevron} />
            </Pressable>
          </View>
          <Text style={styles.headerCaption}>
            已登录 {accounts.length} 个账号 · 左右滑动切换
          </Text>
          <Text style={styles.headerTitle} onPress={() => go('/accounts')}>
            当前：{current.name}({current.uid})
          </Text>
        </View>
      )}

      <Text style={styles.sectionCaption}>论坛功能</Text>
      {ENTRIES.map((entry) => {
        // 要弹对话框的条目(版块收藏 10、由 URL 读取 24)由宿主页面接管;
        // 没接的条目维持「本版本未开放」
        const action =
          entry.key === 'add-board'
            ? onAddBoard
            : entry.key === 'clear-favor'
              ? onClearFavorites
              : entry.key === 'from-url'
                ? onOpenUrl
                : entry.key === 'check-in'
                  ? checkInNow
                  : undefined;
        const href = entry.href;
        const mine = entry.mine;
        const onPress =
          href !== undefined
            ? () => go(href)
            : mine !== undefined
              ? () => openMine(mine)
              : (action ?? showNotAvailable);
        return (
          <Pressable
            key={entry.key}
            style={styles.entry}
            onPress={onPress}
            android_ripple={{ color: theme.colors.divider }}
          >
            <Icon name={entry.icon} size={21} color={theme.colors.fg2} />
            <Text style={styles.entryLabel} numberOfLines={1}>
              {entry.label}
            </Text>
            {entry.status === 'check-in' && current !== null ? (
              <Text style={styles.entryStatus} numberOfLines={1}>
                {checkedInToday ? '今天已签到' : checkInPending ? '签到中…' : '今天还没签'}
              </Text>
            ) : null}
            {entry.badge === 'notifications' && unread > 0 ? (
              <View style={styles.entryBadge}>
                <Text style={styles.entryBadgeText} allowFontScaling={false}>
                  {unread > 99 ? '99+' : unread}
                </Text>
              </View>
            ) : null}
          </Pressable>
        );
      })}
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
    backgroundColor: topbarOverlay,
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarText: {
    fontSize: 22,
    fontWeight: '700',
    color: theme.colors.onPrimary,
  },
  chevron: {
    opacity: 0.55,
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
  /** 签到那行右侧的状态灰字,与未读角标一样顶到行尾 */
  entryStatus: {
    ...theme.typography.meta,
    color: theme.colors.meta,
    marginLeft: 'auto',
  },
  /** 未读角标照设计稿短消息屏:18 高胶囊、红底白字,顶到行尾 */
  entryBadge: {
    marginLeft: 'auto',
    minWidth: 18,
    height: 18,
    paddingHorizontal: 5,
    borderRadius: theme.radius.sm,
    backgroundColor: theme.colors.danger,
    alignItems: 'center',
    justifyContent: 'center',
  },
  entryBadgeText: {
    ...theme.typography.unreadBadge,
    color: theme.colors.onPrimary,
  },
  tail: {
    height: theme.spacing.xl,
  },
}));

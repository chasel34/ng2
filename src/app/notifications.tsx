import { useRouter } from 'expo-router';
import { useEffect } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';

import type { NgaNotification, NotificationKind } from '@/core/api';
import { groupNotifications } from '@/core/local';
import { useAccounts } from '@/store/accounts';
import { useNotifications } from '@/store/notifications';
import { avatarColorFor } from '@/ui/avatar';
import { Icon, type IconName } from '@/ui/icon';
import { initialOf } from '@/ui/initial';
import { LoadFailedNotice } from '@/ui/error-screen';
import { EmptyState, LoadingState } from '@/ui/state-view';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { relativeTimeText } from '@/ui/time-text';
import { showNotAvailable, showToast } from '@/ui/toast';
import { TopBar, TopBarButton, TopBarTitle, topBarSpacer } from '@/ui/top-bar';

/**
 * 「最近被喷」页(CONTEXT.md「通知」:UI 文案沿用设计稿,代码统一叫通知)。
 *
 * 设计稿 isNotify 屏 1:1:顶栏「我的被喷」+ 删除按钮;正文按类型分组,
 * 分组头是 图标+组名+条数,条目是 头像 + 三行(谁干了什么 / 主题 / 页码·时间)。
 * 设计稿条目第二行画的是对方内容摘要,但 noti 接口不给正文(API 文档 §9.1),
 * 这一行放主题标题,第三行放「第 N 页 · 时间」。
 *
 * 进页即把当前条目全部标记已读(角标就是为了引到这儿);条目点击跳
 * 对方楼层所在页,短信类点击 toast 占位(spec §1 短消息不在 v1)。
 */

/** 分组的展示顺序与文案。组名照设计稿,设计稿没画的组(评价/短信)按同款式补。 */
const GROUPS: Record<NotificationKind, { label: string; icon: IconName }> = {
  reply: { label: '回复我的', icon: 'reply' },
  mention: { label: '@ 我的', icon: 'alternate_email' },
  comment: { label: '给我贴条的', icon: 'sticky_note_2' },
  rating: { label: '收到的评价', icon: 'thumb_up' },
  message: { label: '短消息', icon: 'sms' },
  other: { label: '其他通知', icon: 'notifications_active' },
};

const GROUP_ORDER: readonly NotificationKind[] = [
  'reply',
  'mention',
  'comment',
  'rating',
  'message',
  'other',
];

/** 「谁干了什么」的动词,按原始类型码分。@ 的文案照设计稿原字。 */
function verbOf(item: NgaNotification): string {
  switch (item.type) {
    case 1:
      return '回复了你的主题';
    case 2:
      return '回复了你的楼层';
    case 3:
      return '给你的主题贴条';
    case 4:
      return '给你的楼层贴条';
    case 7:
    case 8:
      return '在帖子里 @ 了你';
    case 10:
      return '发来一条短消息';
    case 11:
      return '回复了你的短消息';
    case 17:
      return '评价了你的帖子';
    default:
      return '发来一条通知';
  }
}

export default function NotificationsScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();

  const loggedIn = useAccounts((state) => state.currentUid !== null);
  const items = useNotifications((state) => state.items);
  const refreshing = useNotifications((state) => state.refreshing);
  const error = useNotifications((state) => state.error);

  // 进页刷一次,不等下一个轮询周期
  useEffect(() => {
    void useNotifications.getState().refresh();
  }, []);

  // 页面开着就算看过:当前条目(含轮询期间新到的)全部记已读,角标随之熄灭
  useEffect(() => {
    if (items.length === 0) return;
    useNotifications.getState().markRead(items.map((item) => item.id));
  }, [items]);

  const openItem = (item: NgaNotification) => {
    // 短消息不在 v1(spec §1);没有 tid 的条目也没处可跳
    if (item.kind === 'message' || item.tid === 0) {
      showNotAvailable();
      return;
    }
    router.push({
      pathname: '/topic/[tid]',
      params: { tid: String(item.tid), title: item.subject, page: String(item.page) },
    });
  };

  const clearAll = () => {
    void (async () => {
      try {
        await useNotifications.getState().clearAll();
        showToast('已清空全部通知');
      } catch {
        showToast('清空失败,稍后再试');
      }
    })();
  };

  const body = () => {
    if (!loggedIn) {
      return (
        <EmptyState
          icon="person_add"
          text="登录后才能收通知"
          action={{ label: '去登录', onPress: () => router.push('/login') }}
        />
      );
    }
    if (items.length === 0) {
      if (refreshing) return <LoadingState />;
      // 拉失败也是空列表,得说清是「没人喷」还是「没拉到」
      if (error !== null) {
        return (
          <View style={styles.center}>
            <LoadFailedNotice
              error={error}
              onRetry={() => void useNotifications.getState().refresh()}
            />
          </View>
        );
      }
      return <EmptyState icon="notifications_active" text="最近没人喷你" />;
    }

    const now = Date.now();
    const groups = groupNotifications(items, GROUP_ORDER);
    return (
      <ScrollView style={styles.list} showsVerticalScrollIndicator={false}>
        {groups.map((group) => (
          <View key={group.kind}>
            <View style={styles.groupHeader}>
              <Icon name={GROUPS[group.kind].icon} size={18} color={theme.colors.primary} />
              <Text style={styles.groupName}>{GROUPS[group.kind].label}</Text>
              <Text style={styles.groupCount}>{group.items.length} 条</Text>
            </View>
            {group.items.map((item) => (
              <Pressable
                key={item.id}
                style={styles.item}
                onPress={() => openItem(item)}
                android_ripple={{ color: theme.colors.divider }}
              >
                <View
                  style={[
                    styles.itemAvatar,
                    { backgroundColor: avatarColorFor(String(item.userId ?? item.userName)) },
                  ]}
                >
                  <Text style={styles.itemAvatarText} allowFontScaling={false}>
                    {initialOf(item.userName)}
                  </Text>
                </View>
                <View style={styles.itemBody}>
                  <Text style={styles.itemTitle}>
                    <Text style={styles.itemWho}>{item.userName}</Text> {verbOf(item)}
                  </Text>
                  <Text style={styles.itemSubject} numberOfLines={1}>
                    {item.subject}
                  </Text>
                  <Text style={styles.itemMeta}>
                    {item.kind === 'message' || item.tid === 0
                      ? relativeTimeText(item.timestamp, now)
                      : `第 ${item.page} 页 · ${relativeTimeText(item.timestamp, now)}`}
                  </Text>
                </View>
              </Pressable>
            ))}
          </View>
        ))}
        <View style={styles.tail} />
      </ScrollView>
    );
  };

  return (
    <View style={styles.root}>
      <TopBar paddingHorizontal={4}>
        <TopBarButton
          icon="arrow_back"
          box={46}
          size={24}
          onPress={() => router.back()}
          accessibilityLabel="返回"
        />
        <TopBarTitle variant="sub">我的被喷</TopBarTitle>
        <TopBarButton
          icon="delete"
          size={23}
          onPress={clearAll}
          accessibilityLabel="清空全部通知"
          style={topBarSpacer}
        />
      </TopBar>
      {body()}
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  root: {
    flex: 1,
    backgroundColor: theme.colors.bg,
  },
  list: {
    flex: 1,
  },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: theme.spacing.md,
    padding: theme.spacing.xl,
  },
  /** 设计稿:分组头 padding 15/16/9,底 surface-2,压一条分隔线 */
  groupHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.sm,
    paddingTop: 15,
    paddingHorizontal: theme.spacing.lg,
    paddingBottom: 9,
    backgroundColor: theme.colors.surface2,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  groupName: {
    ...theme.typography.caption,
    // 抽屉的「论坛功能」那种分节标题带 .4 字间距,设计稿这处组名没有
    letterSpacing: 0,
    color: theme.colors.primary,
    flex: 1,
  },
  groupCount: {
    ...theme.typography.meta,
    color: theme.colors.meta,
  },
  /** 设计稿:条目 padding 13/16,头像与正文 gap 12 */
  item: {
    flexDirection: 'row',
    gap: theme.spacing.md,
    paddingVertical: 13,
    paddingHorizontal: theme.spacing.lg,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  itemAvatar: {
    width: 36,
    height: 36,
    borderRadius: theme.radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  itemAvatarText: {
    ...theme.typography.notifyInitial,
    color: theme.colors.onPrimary,
  },
  itemBody: {
    flex: 1,
    minWidth: 0,
  },
  itemTitle: {
    ...theme.typography.notice,
    color: theme.colors.fg,
  },
  itemWho: {
    fontWeight: '700',
    color: theme.colors.link,
  },
  itemSubject: {
    ...theme.typography.listMeta,
    color: theme.colors.fg2,
    marginTop: 4,
  },
  itemMeta: {
    ...theme.typography.notifyMeta,
    color: theme.colors.meta,
    marginTop: 5,
  },
  tail: {
    height: 26,
  },
}));

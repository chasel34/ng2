import { LegendList } from '@legendapp/list/react-native';
import { useRouter } from 'expo-router';
import { memo, useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Pressable, Text, View } from 'react-native';

import { formatHistoryTime, historyProgressLabel, type HistoryEntry } from '@/core/local';
import { clearHistory, useHistoryEntries } from '@/store/history';
import { Icon } from '@/ui/icon';
import { EmptyState } from '@/ui/state-view';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { showToast } from '@/ui/toast';
import { TopBar, TopBarButton, TopBarTitle, topBarSpacer } from '@/ui/top-bar';

/**
 * 浏览历史页(设计稿 isSimpleList 的 history 档):副标题条 + 行列表,
 * 右侧一格是阅读进度「读到 N 楼 / 读完」,点行重新打开主题。纯本地,零请求。
 */
export default function HistoryScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();
  const entries = useHistoryEntries();

  // 「N 分钟前」这类相对时间会过期,页面停留时每分钟刷一次基准
  const now = useMinuteTick();

  const openEntry = useCallback(
    (entry: HistoryEntry) => {
      router.push({
        pathname: '/topic/[tid]',
        params: {
          tid: String(entry.tid),
          title: entry.subject,
          ...(entry.favCode === undefined ? {} : { fav: entry.favCode }),
        },
      });
    },
    [router],
  );

  // renderItem / 头尾组件都稳住:元素每次渲染都新建的话,列表每次都要重挂它们
  const renderItem = useCallback(
    ({ item }: { item: HistoryEntry }) => (
      <HistoryRow entry={item} now={now} onPress={openEntry} />
    ),
    [now, openEntry],
  );
  const header = useMemo(
    () => <Text style={styles.subtitle}>本机记录 · 保留最近 200 条</Text>,
    [styles.subtitle],
  );
  const footer = useMemo(() => <View style={styles.footerSpacer} />, [styles.footerSpacer]);

  const confirmClear = () => {
    if (entries.length === 0) return;
    // 设计稿是「立即清空 + 可撤销 toast」,但 Android 原生 toast 挂不了撤销按钮,
    // 200 条记录一抖就没太伤,退一步改成先问一句
    Alert.alert('清空浏览历史', `${entries.length} 条本机记录将被删除。`, [
      { text: '取消', style: 'cancel' },
      {
        text: '清空',
        style: 'destructive',
        onPress: () => {
          clearHistory();
          showToast('已清空浏览历史');
        },
      },
    ]);
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
        <TopBarTitle variant="sub">浏览历史</TopBarTitle>
        <TopBarButton
          icon="delete_sweep"
          size={22}
          onPress={confirmClear}
          accessibilityLabel="清空浏览历史"
          style={topBarSpacer}
        />
      </TopBar>

      {entries.length === 0 ? (
        <EmptyState icon="history" text={'还没有浏览记录\n看过的主题会自动出现在这里'} />
      ) : (
        <View style={styles.body}>
          <LegendList
            data={entries}
            keyExtractor={(entry) => String(entry.tid)}
            recycleItems
            // 行是同构的(每行都是同一个 HistoryRow),不需要 getItemType
            renderItem={renderItem}
            ListHeaderComponent={header}
            ListFooterComponent={footer}
          />
        </View>
      )}
    </View>
  );
}

/**
 * memo:`now` 每分钟才走一格,其余时候整屏重渲染(清空对话框、返回手势)不该
 * 把每一行都重画一遍。`onPress` 收的是「拿 entry 的回调」而不是现成的闭包——
 * 一行一个 `() => open(item)` 的话 props 恒不等,memo 白包。
 */
const HistoryRow = memo(function HistoryRow({
  entry,
  now,
  onPress,
}: {
  entry: HistoryEntry;
  now: number;
  onPress: (entry: HistoryEntry) => void;
}) {
  const styles = useStyles();
  const theme = useTheme();

  return (
    <Pressable
      style={styles.row}
      onPress={() => onPress(entry)}
      android_ripple={{ color: theme.colors.divider }}
    >
      <Text style={styles.title}>
        {entry.subject}
        {entry.boardName !== undefined && (
          <Text style={styles.titleTag}>{` [${entry.boardName}]`}</Text>
        )}
      </Text>
      <View style={styles.metaRow}>
        <Icon name="person" size={15} color={theme.colors.meta} />
        {/* 从非第 1 页进过来的主题拿不到楼主名,这一格留白 */}
        <Text style={styles.author} numberOfLines={1}>
          {entry.author ?? ''}
        </Text>
        <Text style={styles.time}>{formatHistoryTime(entry.updatedAt, now)}</Text>
        <Text style={styles.progress}>{historyProgressLabel(entry)}</Text>
      </View>
    </Pressable>
  );
});

/** 每分钟走一格的时钟(秒)。只在整页级别订阅一次,行组件拿它当纯参数。 */
function useMinuteTick(): number {
  const [now, setNow] = useState(() => Math.floor(Date.now() / 1000));
  useEffect(() => {
    const timer = setInterval(() => setNow(Math.floor(Date.now() / 1000)), 60_000);
    return () => clearInterval(timer);
  }, []);
  return now;
}

const useStyles = createThemedStyles((theme) => ({
  root: {
    flex: 1,
    backgroundColor: theme.colors.bg,
  },
  body: {
    flex: 1,
  },
  /** 设计稿 listSub:11/16 内边距、surface2 底、下分隔线 */
  subtitle: {
    ...theme.typography.listSubtitle,
    color: theme.colors.meta,
    paddingVertical: 11,
    paddingHorizontal: theme.spacing.lg,
    backgroundColor: theme.colors.surface2,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  /** 设计稿行:padding 14 16 12,下分隔线 */
  row: {
    paddingTop: theme.spacing.row,
    paddingHorizontal: theme.spacing.lg,
    paddingBottom: theme.spacing.md,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  title: {
    ...theme.typography.listTitle,
    color: theme.colors.fg,
  },
  titleTag: {
    color: theme.colors.tag,
  },
  /** 设计稿:上距 9、gap 6、12.5 号 */
  metaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    marginTop: 9,
  },
  author: {
    ...theme.typography.listMeta,
    color: theme.colors.link,
    maxWidth: 118,
  },
  time: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
    marginLeft: 'auto',
  },
  progress: {
    ...theme.typography.listMeta,
    color: theme.colors.link,
  },
  /** 设计稿列表末尾留 26 */
  footerSpacer: {
    height: 26,
  },
}));

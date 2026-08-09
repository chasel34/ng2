import { FlashList } from '@shopify/flash-list';
import { useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { Alert, Pressable, Text, View } from 'react-native';

import {
  cachePagesLabel,
  cacheTotalBytes,
  formatCacheSize,
  formatHistoryTime,
  type CachedTopic,
} from '@/core/local';
import { clearTopicCache, deleteCachedTopic, useCachedTopics } from '@/store/topic-cache';
import { Icon } from '@/ui/icon';
import { EmptyState } from '@/ui/state-view';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { showToast } from '@/ui/toast';
import { TopBar, TopBarButton, TopBarTitle, topBarSpacer } from '@/ui/top-bar';

/**
 * 「我的缓存」(设计稿 isSimpleList 的 cache 档):副标题报总占用,一行一个主题,
 * 右侧一格是体积、行尾是删除钮。点行离线打开——落在这个主题已缓存的第一页上,
 * 断网时反封锁链的缓存档会把它还原出来(ADR-0002)。
 */
export default function CachesScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();
  const topics = useCachedTopics();

  // 「N 分钟前」这类相对时间会过期,页面停留时每分钟刷一次基准
  const now = useMinuteTick();
  const total = cacheTotalBytes(topics);

  const openTopic = (topic: CachedTopic) => {
    const first = topic.pages[0] ?? 1;
    router.push({
      pathname: '/topic/[tid]',
      params: {
        tid: String(topic.tid),
        title: topic.subject,
        page: String(first),
        ...(topic.favCode === undefined ? {} : { fav: topic.favCode }),
      },
    });
  };

  const removeTopic = (topic: CachedTopic) => {
    deleteCachedTopic(topic.tid);
    showToast(`已删除「${topic.subject}」的缓存`);
  };

  const confirmClear = () => {
    if (topics.length === 0) return;
    Alert.alert(
      '清空全部缓存',
      `${topics.length} 个主题、共 ${formatCacheSize(total)} 的离线数据将被删除。`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '清空',
          style: 'destructive',
          onPress: () => {
            clearTopicCache();
            showToast('已清空全部缓存');
          },
        },
      ],
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
        <TopBarTitle variant="sub">我的缓存</TopBarTitle>
        <TopBarButton
          icon="delete_sweep"
          size={22}
          onPress={confirmClear}
          accessibilityLabel="清空全部缓存"
          style={topBarSpacer}
        />
      </TopBar>

      {topics.length === 0 ? (
        <EmptyState
          icon="cached"
          text={'还没有缓存的主题\n看过的帖子会自动存一份,断网时也能打开'}
        />
      ) : (
        <View style={styles.body}>
          <FlashList
            data={topics}
            keyExtractor={(topic) => String(topic.tid)}
            renderItem={({ item }) => (
              <CacheRow
                topic={item}
                now={now}
                onPress={() => openTopic(item)}
                onDelete={() => removeTopic(item)}
              />
            )}
            ListHeaderComponent={
              <Text style={styles.subtitle}>离线可读 · 已占用 {formatCacheSize(total)}</Text>
            }
            ListFooterComponent={<View style={styles.footerSpacer} />}
          />
        </View>
      )}
    </View>
  );
}

function CacheRow({
  topic,
  now,
  onPress,
  onDelete,
}: {
  topic: CachedTopic;
  now: number;
  onPress: () => void;
  onDelete: () => void;
}) {
  const styles = useStyles();
  const theme = useTheme();

  return (
    <Pressable style={styles.row} onPress={onPress} android_ripple={{ color: theme.colors.divider }}>
      <View style={styles.rowBody}>
        <Text style={styles.title}>
          {topic.subject}
          {topic.boardName !== undefined && (
            <Text style={styles.titleTag}>{` [${topic.boardName}]`}</Text>
          )}
        </Text>
        <View style={styles.metaRow}>
          <Icon name="download" size={15} color={theme.colors.meta} />
          <Text style={styles.pages} numberOfLines={1}>
            {cachePagesLabel(topic)}
          </Text>
          <Text style={styles.time}>{formatHistoryTime(topic.usedAt, now)}</Text>
          <Text style={styles.size}>{formatCacheSize(topic.bytes)}</Text>
        </View>
      </View>
      <Pressable
        onPress={onDelete}
        hitSlop={10}
        accessibilityLabel={`删除「${topic.subject}」的缓存`}
        style={styles.delete}
      >
        <Icon name="delete" size={19} color={theme.colors.meta} />
      </Pressable>
    </Pressable>
  );
}

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
  /** 设计稿行:padding 14 16 12,下分隔线;行尾多一格删除钮 */
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingTop: theme.spacing.row,
    paddingHorizontal: theme.spacing.lg,
    paddingBottom: theme.spacing.md,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  rowBody: {
    flex: 1,
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
  /** 设计稿 isSimpleList 的信息行整行走 link 色,`who` 槽也继承它 */
  pages: {
    ...theme.typography.listMeta,
    color: theme.colors.link,
    maxWidth: 150,
  },
  time: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
    marginLeft: 'auto',
  },
  size: {
    ...theme.typography.listMeta,
    color: theme.colors.link,
  },
  delete: {
    paddingLeft: theme.spacing.md,
    paddingVertical: theme.spacing.xs,
  },
  /** 设计稿列表末尾留 26 */
  footerSpacer: {
    height: 26,
  },
}));

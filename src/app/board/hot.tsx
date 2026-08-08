import { FlashList } from '@shopify/flash-list';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';

import type { Topic } from '@/core/api';
import { HOT_WINDOW_HOURS } from '@/core/local';
import { useHotTopics } from '@/store/hot-topics';
import { Icon } from '@/ui/icon';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { relativeTimeText } from '@/ui/time-text';
import { TopBar, TopBarButton, TopBarTitle, topBarSpacer } from '@/ui/top-bar';
import { TopicRow } from '@/ui/topic-row';

/**
 * 24 小时热帖(CONTEXT.md「热帖」):并发拉版块前几页、本地聚合的榜单,
 * **不是服务端 API**。UI 按设计稿 simple-list 屏:顶栏带刷新钮,
 * 列表行复用 TopicRow 的 simple 档(标题 16、右侧是相对时间)。
 *
 * 路由参数与主题列表页同一套:`id`(stid 优先)、`kind`、`name`(版块名,进副标题条)。
 */
export default function HotTopicsScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();

  const { id, name, kind } = useLocalSearchParams<{ id: string; name?: string; kind?: string }>();
  const boardId = Number(id);
  const boardKind = kind === 'collection' ? 'collection' : 'board';

  const { data, error, isPending, isRefetching, refetch } = useHotTopics({
    boardId,
    kind: boardKind,
  });

  const openTopic = (topic: Topic) => {
    // 榜单在聚合时已剔掉合集/镜像/外链行,进来的都是普通讨论串
    router.push({
      pathname: '/topic/[tid]',
      params: {
        tid: String(topic.tid),
        title: topic.subject,
        ...(topic.favCode === undefined ? {} : { fav: topic.favCode }),
      },
    });
  };

  // 副标题条(设计稿 listSub):来源版块 + 排序口径;部分页失败时把话说在这儿
  const subParts = [
    ...(name === undefined ? [] : [name]),
    `近 ${HOT_WINDOW_HOURS} 小时 · 按回复数排序`,
    ...(data !== undefined && data.failedPages.length > 0
      ? [`${data.pagesTried} 页里 ${data.failedPages.length} 页拉取失败,榜单不完整`]
      : []),
  ];

  const body = () => {
    if (isPending) {
      return (
        <View style={styles.center}>
          <ActivityIndicator color={theme.colors.primary} />
        </View>
      );
    }
    if (data === undefined || data.topics.length === 0) {
      const failed = data === undefined && error !== null;
      return (
        <View style={styles.center}>
          <Icon
            name={failed ? 'cloud_off' : 'local_fire_department'}
            size={40}
            color={theme.colors.meta}
          />
          <Text style={styles.errorText}>
            {failed
              ? error instanceof Error
                ? error.message
                : '热帖拉不下来'
              : `近 ${HOT_WINDOW_HOURS} 小时没有新主题`}
          </Text>
          <Pressable style={styles.retry} onPress={() => void refetch()}>
            <Text style={styles.retryLabel}>{failed ? '重试' : '刷新'}</Text>
          </Pressable>
        </View>
      );
    }
    return (
      // FlashList 要一个高度确定的父容器才算得出可视区
      <View style={styles.body}>
        <FlashList
          data={data.topics}
          keyExtractor={(topic) => String(topic.tid)}
          renderItem={({ item }) => (
            <TopicRow
              topic={item}
              onPress={openTopic}
              time={relativeTimeText(item.postedAt, data.fetchedAt)}
            />
          )}
          ListFooterComponent={<View style={styles.footerSpacer} />}
          refreshing={isRefetching}
          onRefresh={() => void refetch()}
        />
      </View>
    );
  };

  return (
    <View style={styles.root}>
      <TopBar paddingHorizontal={4}>
        <TopBarButton
          icon="arrow_back"
          size={24}
          onPress={() => router.back()}
          accessibilityLabel="返回"
        />
        <TopBarTitle variant="sub">24 小时热帖</TopBarTitle>
        <TopBarButton
          icon="refresh"
          size={22}
          onPress={() => void refetch()}
          accessibilityLabel="刷新热帖榜"
          style={topBarSpacer}
        />
      </TopBar>

      {/* 设计稿 listSub:12px meta 色副标题条,surface-2 底 */}
      <Text style={styles.sub}>{subParts.join(' · ')}</Text>

      {body()}
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  root: {
    flex: 1,
    backgroundColor: theme.colors.bg,
  },
  body: {
    flex: 1,
  },
  sub: {
    paddingVertical: 11,
    paddingHorizontal: theme.spacing.lg,
    fontSize: 12,
    color: theme.colors.meta,
    backgroundColor: theme.colors.surface2,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: theme.spacing.md,
    padding: theme.spacing.xl,
  },
  errorText: {
    ...theme.typography.notice,
    color: theme.colors.fg2,
    textAlign: 'center',
  },
  retry: {
    height: 40,
    paddingHorizontal: theme.spacing.xl,
    borderRadius: theme.radius.full,
    backgroundColor: theme.colors.primary,
    alignItems: 'center',
    justifyContent: 'center',
  },
  retryLabel: {
    ...theme.typography.drawerItem,
    fontWeight: '600',
    color: theme.colors.onPrimary,
  },
  footerSpacer: {
    height: 26,
  },
}));

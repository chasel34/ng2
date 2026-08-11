import { FlashList } from '@shopify/flash-list';
import { useLocalSearchParams, useRouter } from 'expo-router';
import * as WebBrowser from 'expo-web-browser';
import { useCallback, useMemo } from 'react';
import { Text, View } from 'react-native';

import { mergeTopicPages, type Topic } from '@/core/api';
import { useTopicFilter } from '@/store/filters';
import { useRefreshTopicList, useTopicList } from '@/store/topic-list';
import { LoadFailedNotice, loadFailureCopy } from '@/ui/error-screen';
import { EmptyState, LoadingFooter, LoadingState } from '@/ui/state-view';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { dateText } from '@/ui/time-text';
import { showNotAvailable } from '@/ui/toast';
import { TopBar, TopBarButton, TopBarTitle, topBarSpacer } from '@/ui/top-bar';
import { TopicRow } from '@/ui/topic-row';

/**
 * 精华区(功能文档 §2.2):`recommend=1` 的主题列表,按发帖时间排。
 * UI 按设计稿 simple-list 屏,列表行复用 TopicRow 的 simple 档
 * (标题 16、右侧是发帖日期——设计稿样例就是「2026-07-20」这种)。
 *
 * 路由参数与主题列表页同一套:`id`(stid 优先)、`kind`、`name`。
 */
export default function RecommendScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();

  const { id, name, kind } = useLocalSearchParams<{ id: string; name?: string; kind?: string }>();
  const boardId = Number(id);
  const boardKind = kind === 'collection' ? 'collection' : 'board';

  // 精华区下 sort 不参与请求(recommend 固定 postdatedesc),给个定值就好
  const params = { boardId, kind: boardKind, sort: 'postDate', recommend: true } as const;
  const {
    data,
    error,
    isPending,
    isFetchingNextPage,
    isRefetching,
    hasNextPage,
    fetchNextPage,
    refetch,
  } = useTopicList(params);
  const refresh = useRefreshTopicList(params);

  // 与主题列表页一样过一道屏蔽规则(21 票):精华区也是主题列表,不该漏网
  const filterTopics = useTopicFilter();
  const topics = useMemo(
    () => filterTopics(mergeTopicPages(data?.pages ?? [])),
    [data?.pages, filterTopics],
  );
  const loadedPages = data?.pages.length ?? 0;
  const totalRows = data?.pages[0]?.totalRows;

  const openTopic = useCallback(
    (topic: Topic) => {
      // 与主题列表页同一套规则:快捷方式行开版块、活动行走浏览器
      if (topic.shortcut !== undefined) {
        router.push({
          pathname: '/board/[id]',
          params: { id: String(topic.shortcut.id), name: topic.subject, kind: topic.shortcut.kind },
        });
        return;
      }
      if (topic.jumpUrl !== undefined) {
        void WebBrowser.openBrowserAsync(topic.jumpUrl);
        return;
      }
      router.push({
        pathname: '/topic/[tid]',
        params: {
          tid: String(topic.tid),
          title: topic.subject,
          ...(topic.favCode === undefined ? {} : { fav: topic.favCode }),
        },
      });
    },
    [router],
  );

  // 副标题条(设计稿 listSub:「版面推荐 · 共 148 篇」,「版面」沿用设计稿原字)
  const subParts = [
    ...(name === undefined ? [] : [name]),
    '版面推荐',
    ...(totalRows === undefined ? [] : [`共 ${totalRows} 篇`]),
  ];

  const body = () => {
    if (isPending) return <LoadingState />;
    if (topics.length === 0 && error !== null) {
      return (
        <View style={styles.center}>
          <LoadFailedNotice error={error} onRetry={() => void refetch()} />
        </View>
      );
    }
    if (topics.length === 0) {
      const allFiltered = (data?.pages[0]?.topics.length ?? 0) > 0;
      return (
        <EmptyState
          icon={allFiltered ? 'filter_alt' : 'article'}
          text={
            allFiltered ? '这一页的主题都被屏蔽规则挡住了' : '这个版块还没有精华主题'
          }
          action={{ label: '刷新', onPress: () => void refetch() }}
        />
      );
    }
    return (
      // FlashList 要一个高度确定的父容器才算得出可视区
      <View style={styles.body}>
        <FlashList
          data={topics}
          keyExtractor={(topic) => String(topic.tid)}
          renderItem={({ item }) => (
            <TopicRow topic={item} onPress={openTopic} time={dateText(item.postedAt)} />
          )}
          ListFooterComponent={
            <View>
              {isFetchingNextPage && <LoadingFooter text={`正在载入第 ${loadedPages + 1} 页…`} />}
              {!isFetchingNextPage && error !== null && (
                <Text style={styles.footerText}>{loadFailureCopy(error).headline}</Text>
              )}
              <View style={styles.footerSpacer} />
            </View>
          }
          onEndReachedThreshold={0.6}
          onEndReached={() => {
            if (hasNextPage && !isFetchingNextPage) void fetchNextPage();
          }}
          refreshing={isRefetching && !isFetchingNextPage}
          onRefresh={refresh}
        />
      </View>
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
        <TopBarTitle variant="sub">精华区</TopBarTitle>
        {/* 设计稿的按版块筛选还没排票,入口保留 */}
        <TopBarButton
          icon="filter_alt"
          size={22}
          onPress={showNotAvailable}
          accessibilityLabel="按版块筛选"
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
    ...theme.typography.listSubtitle,
    paddingVertical: 11,
    paddingHorizontal: theme.spacing.lg,
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
  footerText: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
    padding: theme.spacing.xl,
    textAlign: 'center',
  },
  footerSpacer: {
    height: 26,
  },
}));

import { FlashList } from '@shopify/flash-list';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useMemo } from 'react';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';

import { mergeUserPostPages, type Topic, type UserPostKind } from '@/core/api';
import { useUserPosts } from '@/store/user-topics';
import { Icon } from '@/ui/icon';
import { ReplyRow } from '@/ui/reply-row';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { dateText } from '@/ui/time-text';
import { showToast } from '@/ui/toast';
import { TopBar, TopBarButton, TopBarTitle, topBarSpacer } from '@/ui/top-bar';
import { TopicRow } from '@/ui/topic-row';

const TITLES: Record<UserPostKind, string> = {
  topics: '我的主题',
  replies: '我的回复',
};

/** 一条都没有时的文案。 */
const EMPTY_TEXT: Record<UserPostKind, string> = {
  topics: '还没有发过主题',
  replies: '还没有回过帖',
};

/** 过期占位条目的 `__P.postdate` 是 0,照 `dateText` 走会显示成 1970-01-01。 */
const replyTimeText = (topic: Topic): string => {
  const postedAt = topic.reply?.postedAt ?? 0;
  return postedAt === 0 ? '—' : dateText(postedAt);
};

/**
 * 某人的主题 / 回复列表(抽屉的「我的主题」「我的回复」两个入口,同一个屏)。
 *
 * 两个入口只差一个 `kind`:响应形状一样,只有回复多带一条 `__P`。
 * 路由参数:`uid` 必填、`kind` 默认 topics、`name` 只是为了顶栏副标题。
 */
export default function UserPostsScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();

  const { uid, kind, name } = useLocalSearchParams<{
    uid: string;
    kind?: string;
    name?: string;
  }>();
  const userId = Number(uid);
  const postKind: UserPostKind = kind === 'replies' ? 'replies' : 'topics';

  const {
    data,
    error,
    isPending,
    isRefetching,
    isFetchingNextPage,
    hasNextPage,
    fetchNextPage,
    refetch,
  } = useUserPosts({ uid: userId, kind: postKind });

  const items = useMemo(() => mergeUserPostPages(data?.pages ?? []), [data?.pages]);
  const loadedPages = data?.pages.length ?? 0;

  const openTopic = (topic: Topic) => {
    if (topic.denied) {
      // 服务端已经明说了不给看,点进去只会是一个空帖子
      showToast(topic.subject);
      return;
    }
    router.push({
      pathname: '/topic/[tid]',
      params: {
        tid: String(topic.tid),
        title: topic.subject,
        ...(topic.favCode === undefined ? {} : { fav: topic.favCode }),
        // 回复条目直接落到那一楼:NGA 不提供 pid → 页码 的换算,
        // 只提供「只看某一楼」(API 文档 §3 的 pid 参数),详情页会带一条返回全帖的提示
        ...(topic.reply === undefined ? {} : { pid: String(topic.reply.pid) }),
      },
    });
  };

  const body = () => {
    if (isPending) {
      return (
        <View style={styles.center}>
          <ActivityIndicator color={theme.colors.primary} />
        </View>
      );
    }
    if (items.length === 0) {
      const failed = error !== null;
      return (
        <View style={styles.center}>
          <Icon name={failed ? 'cloud_off' : 'article'} size={40} color={theme.colors.meta} />
          <Text style={styles.errorText}>
            {failed
              ? error instanceof Error
                ? error.message
                : '列表拉不下来'
              : EMPTY_TEXT[postKind]}
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
          data={items}
          keyExtractor={(topic) =>
            // 回复列表里同一个 tid 会出现很多次,key 必须落在 pid 上
            topic.reply === undefined ? `t${topic.tid}` : `p${topic.reply.pid}`
          }
          renderItem={({ item }) =>
            postKind === 'replies' ? (
              <ReplyRow topic={item} onPress={openTopic} time={replyTimeText(item)} />
            ) : (
              <TopicRow topic={item} onPress={openTopic} time={dateText(item.postedAt)} />
            )
          }
          ListFooterComponent={
            <View>
              {isFetchingNextPage && (
                <Text style={styles.footerText}>正在载入第 {loadedPages + 1} 页…</Text>
              )}
              {!hasNextPage && <Text style={styles.footerText}>没有更多了</Text>}
              <View style={styles.footerSpacer} />
            </View>
          }
          onEndReachedThreshold={0.6}
          onEndReached={() => {
            if (hasNextPage && !isFetchingNextPage) void fetchNextPage();
          }}
          refreshing={isRefetching && !isFetchingNextPage}
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
        <TopBarTitle variant="sub">{TITLES[postKind]}</TopBarTitle>
        <TopBarButton
          icon="person"
          size={22}
          onPress={() =>
            router.push({
              pathname: '/user/[uid]',
              params: { uid: String(userId), ...(name === undefined ? {} : { name }) },
            })
          }
          accessibilityLabel="查看资料"
          style={topBarSpacer}
        />
      </TopBar>

      {/* 设计稿 listSub:12px meta 色副标题条 */}
      <Text style={styles.sub}>{name === undefined ? `UID ${uid}` : `${name}(${uid})`}</Text>

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
  footerText: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
    textAlign: 'center',
    paddingVertical: theme.spacing.md,
  },
  footerSpacer: {
    height: 26,
  },
}));

import { FlashList } from '@shopify/flash-list';
import { useRouter } from 'expo-router';
import { useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { mergeTopicPages, type Topic } from '@/core/api';
import {
  useFavoriteFolders,
  useFavoriteTopics,
  useRefreshFavoriteTopics,
} from '@/store/topic-favor';
import { Icon } from '@/ui/icon';
import { OverflowMenu, type MenuItem } from '@/ui/menu';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { TopBar, TopBarButton, TopBarTitle, topBarSpacer } from '@/ui/top-bar';
import { TopicRow } from '@/ui/topic-row';

/**
 * 已收藏的主题(设计稿 `screen:'favorites'`,CONTEXT.md「收藏夹」)。
 *
 * **一次只展示一个收藏夹**:每个夹的主题是各自的 `thread.php?favor=<夹id>`,
 * 把所有夹拼成一屏就是开屏打 N 个请求,正撞在 NGA 封第三方客户端的枪口上(ADR-0002)。
 * 所以进来先落在默认夹,点副标题条换夹——设计稿那句「默认收藏夹 · 126 个主题」
 * 说的就是当前这个夹。
 */
export default function FavoriteTopicsScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();
  const insets = useSafeAreaInsets();

  const { data: folders, error: foldersError, isPending: foldersPending } = useFavoriteFolders();
  const [pickedFolderId, setPickedFolderId] = useState<number | undefined>(undefined);
  const [switcherOpen, setSwitcherOpen] = useState(false);

  // 没手动选过就落在默认夹;服务端没标默认(老账号)时退到第一个夹
  const folder =
    folders?.find((item) => item.id === pickedFolderId) ??
    folders?.find((item) => item.isDefault) ??
    folders?.[0];

  const {
    data,
    error,
    isPending,
    isFetchingNextPage,
    isRefetching,
    hasNextPage,
    fetchNextPage,
    refetch,
  } = useFavoriteTopics(folder?.id);

  // 下拉刷新砍回第一页再取,不把翻过的每一页都重打一遍(ADR-0002)
  const refresh = useRefreshFavoriteTopics(folder?.id);

  const topics = useMemo(() => mergeTopicPages(data?.pages ?? []), [data?.pages]);
  const loadedPages = data?.pages.length ?? 0;

  const switcherItems: readonly MenuItem[] = useMemo(
    () =>
      (folders ?? []).map((item) => ({
        key: String(item.id),
        label: `${item.name}（${item.count}）`,
        selected: item.id === folder?.id,
        onPress: () => {
          setSwitcherOpen(false);
          setPickedFolderId(item.id);
        },
      })),
    [folders, folder?.id],
  );

  const openTopic = (topic: Topic) => {
    router.push({
      pathname: '/topic/[tid]',
      params: {
        tid: String(topic.tid),
        title: topic.subject,
        // 收藏夹列表的 tpcurl 带 fav 码,进详情页要带上才打得开隐藏/过期主题
        ...(topic.favCode === undefined ? {} : { fav: topic.favCode }),
      },
    });
  };

  const body = () => {
    if (foldersPending || (folder !== undefined && isPending)) {
      return (
        <View style={styles.center}>
          <ActivityIndicator color={theme.colors.primary} />
        </View>
      );
    }
    if (folders === undefined) {
      return (
        <View style={styles.center}>
          <Icon name="cloud_off" size={40} color={theme.colors.meta} />
          <Text style={styles.errorText}>
            {foldersError instanceof Error ? foldersError.message : '收藏夹列表拉不下来'}
          </Text>
          <Text style={styles.errorHint}>没登录的话，先从抽屉里登录账号</Text>
        </View>
      );
    }
    if (folder === undefined) {
      return (
        <View style={styles.center}>
          <Icon name="folder" size={40} color={theme.colors.meta} />
          <Text style={styles.errorText}>还没有收藏夹</Text>
          <Pressable style={styles.retry} onPress={() => router.push('/favorites/folders')}>
            <Text style={styles.retryLabel}>去新建</Text>
          </Pressable>
        </View>
      );
    }
    if (topics.length === 0) {
      const failed = error !== null;
      return (
        <View style={styles.center}>
          <Icon name={failed ? 'cloud_off' : 'star'} size={40} color={theme.colors.meta} />
          <Text style={styles.errorText}>
            {failed
              ? error instanceof Error
                ? error.message
                : '收藏列表拉不下来'
              : `「${folder.name}」里还没有主题`}
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
          data={topics}
          keyExtractor={(topic) => String(topic.tid)}
          renderItem={({ item }) => <TopicRow topic={item} onPress={openTopic} />}
          ListFooterComponent={
            <View>
              {isFetchingNextPage && (
                <Text style={styles.footerText}>正在载入第 {loadedPages + 1} 页…</Text>
              )}
              {!isFetchingNextPage && error !== null && (
                <Text style={styles.footerText}>
                  {error instanceof Error ? error.message : '下一页拉不下来'}
                </Text>
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
          size={24}
          onPress={() => router.back()}
          accessibilityLabel="返回"
        />
        <TopBarTitle variant="sub">已收藏的主题</TopBarTitle>
        <TopBarButton
          icon="folder_special"
          size={22}
          onPress={() => router.push('/favorites/folders')}
          accessibilityLabel="收藏夹管理"
          style={topBarSpacer}
        />
      </TopBar>

      {/* 设计稿 listSub:12px meta 色副标题条。点它换夹——一屏只装得下一个夹 */}
      {folder !== undefined && (
        <Pressable
          style={styles.sub}
          onPress={() => setSwitcherOpen(true)}
          disabled={switcherItems.length < 2}
          android_ripple={{ color: theme.colors.divider }}
        >
          <Text style={styles.subText} numberOfLines={1}>
            {folder.name} · {folder.count} 个主题
            {switcherItems.length > 1 ? ' · 点此换收藏夹' : ''}
          </Text>
          {switcherItems.length > 1 && (
            <Icon name="expand_more" size={16} color={theme.colors.meta} />
          )}
        </Pressable>
      )}

      {body()}

      <OverflowMenu
        open={switcherOpen}
        onClose={() => setSwitcherOpen(false)}
        items={switcherItems}
        top={insets.top + 6}
      />
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
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.xs,
    paddingVertical: 11,
    paddingHorizontal: theme.spacing.lg,
    backgroundColor: theme.colors.surface2,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  subText: {
    ...theme.typography.cardMeta,
    color: theme.colors.meta,
    flexShrink: 1,
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
  errorHint: {
    ...theme.typography.meta,
    color: theme.colors.meta,
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
    paddingVertical: theme.spacing.row,
  },
  footerSpacer: {
    height: 26,
  },
}));

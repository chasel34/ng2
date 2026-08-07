import { FlashList } from '@shopify/flash-list';
import { useLocalSearchParams, useRouter } from 'expo-router';
import * as WebBrowser from 'expo-web-browser';
import { useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { mergeTopicPages, type Board, type Topic } from '@/core/api';
import { useRefreshTopicList, useTopicList, useTopicSort } from '@/store/topic-list';
import { Icon } from '@/ui/icon';
import { OverflowMenu, type MenuItem } from '@/ui/menu';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { showNotAvailable } from '@/ui/toast';
import { TopicRow } from '@/ui/topic-row';
import { TopBar, TopBarButton, TopBarTitle, topBarSpacer } from '@/ui/top-bar';

/**
 * 主题列表页。
 *
 * 路由参数由 04 定好:`id`(合集是 stid、普通版块是 fid,stid 优先)、`kind`、`name`。
 * `name` 是为了进页面立刻能画出顶栏标题,不用等 thread.php 回来;
 * 服务端的版块名回来后不覆盖它——两者一致,覆盖只会让标题闪一下。
 */
export default function BoardScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();

  const { id, name, kind } = useLocalSearchParams<{
    id: string;
    name?: string;
    kind?: string;
  }>();
  const boardId = Number(id);
  const boardKind = kind === 'collection' ? 'collection' : 'board';

  const sort = useTopicSort((state) => state.sort);
  const setSort = useTopicSort((state) => state.setSort);
  const [menuOpen, setMenuOpen] = useState(false);

  const {
    data,
    error,
    isPending,
    isFetchingNextPage,
    isRefetching,
    hasNextPage,
    fetchNextPage,
    refetch,
  } = useTopicList({ boardId, kind: boardKind, sort });

  const refresh = useRefreshTopicList({ boardId, kind: boardKind, sort });

  // 置顶主题与镜像行每页都会再回来一次,拼页时按 tid 去重
  const topics = useMemo(() => mergeTopicPages(data?.pages ?? []), [data?.pages]);
  const loadedPages = data?.pages.length ?? 0;
  const subBoards = data?.pages[0]?.subBoards ?? [];

  const openBoard = (board: Board) => {
    router.push({
      pathname: '/board/[id]',
      params: { id: String(board.id), name: board.name, kind: board.kind },
    });
  };

  const openTopic = (topic: Topic) => {
    // 合集 / 版块镜像行不是讨论串,点开是另一个版块的主题列表(API 文档 §2 解析要点 3)
    if (topic.shortcut !== undefined) {
      openBoard({ id: topic.shortcut.id, kind: topic.shortcut.kind, name: topic.subject });
      return;
    }
    // 活动主题指向站内活动页,不是 read.php,只能交给浏览器
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
  };

  const menuItems: readonly MenuItem[] = useMemo(() => {
    // 都还没做:24 小时热帖与精华区 17、浏览历史 16、子版块 23、收藏夹 11
    // (设计稿这一条写的是「子板块」,CONTEXT.md 的词条是「子版块」,按术语表来)
    const pending = ['24 小时热帖', '浏览历史', '精华区', '子版块', '收藏夹'].map((label) => ({
      key: label,
      label,
      onPress: () => {
        setMenuOpen(false);
        showNotAvailable();
      },
    }));

    // 设计稿的列表菜单没画排序(它在 MNGA 里是设置项),按现有菜单样式延伸一组互斥选项
    const sorts: MenuItem[] = (
      [
        ['lastPost', '按最后回复排序'],
        ['postDate', '按发帖时间排序'],
      ] as const
    ).map(([value, label], index) => ({
      key: value,
      label,
      gapBefore: index === 0,
      selected: sort === value,
      onPress: () => {
        setMenuOpen(false);
        setSort(value);
      },
    }));

    return [...pending, ...sorts];
  }, [sort, setSort]);

  const body = () => {
    if (isPending) {
      return (
        <View style={styles.center}>
          <ActivityIndicator color={theme.colors.primary} />
        </View>
      );
    }
    if (topics.length === 0) {
      // 拉失败与「这个版块真的空着」得分开说,不然被封时用户以为版块没帖子
      const failed = error !== null;
      return (
        <View style={styles.center}>
          <Icon name={failed ? 'cloud_off' : 'article'} size={40} color={theme.colors.meta} />
          <Text style={styles.errorText}>
            {failed
              ? error instanceof Error
                ? error.message
                : '主题列表拉不下来'
              : '这个版块还没有主题'}
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
          ListHeaderComponent={
            subBoards.length === 0 ? null : <SubBoardBar boards={subBoards} onPress={openBoard} />
          }
          ListFooterComponent={
            <View>
              {isFetchingNextPage && (
                <Text style={styles.footerText}>正在载入第 {loadedPages + 1} 页…</Text>
              )}
              {/* 翻页失败别闷着:列表照旧,底下把原因说出来 */}
              {!isFetchingNextPage && error !== null && (
                <Text style={styles.footerText}>
                  {error instanceof Error ? error.message : '下一页拉不下来'}
                </Text>
              )}
              {/* 设计稿在列表末尾留了 70 给 FAB 让路 */}
              <View style={styles.footerSpacer} />
            </View>
          }
          onEndReachedThreshold={0.6}
          onEndReached={() => {
            if (hasNextPage && !isFetchingNextPage) void fetchNextPage();
          }}
          // 翻下一页时 isRefetching 不会亮,不然底部转圈会连带把顶部也拽出来
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
        <TopBarTitle variant="sub">
          {name ?? data?.pages[0]?.board?.name ?? `版块 ${id}`}
        </TopBarTitle>
        {/* 版块收藏是 10 票、搜索是 15 票 */}
        <TopBarButton
          icon="star"
          size={23}
          onPress={showNotAvailable}
          accessibilityLabel="收藏本版块"
          style={topBarSpacer}
        />
        <TopBarButton
          icon="search"
          size={22}
          onPress={showNotAvailable}
          accessibilityLabel="搜索"
        />
        <TopBarButton
          icon="more_vert"
          size={22}
          onPress={() => setMenuOpen(true)}
          accessibilityLabel="更多"
        />
      </TopBar>

      {body()}

      {/* 发新帖不在 v1 范围内(spec §1),入口保留 */}
      <Pressable style={styles.fab} onPress={showNotAvailable} accessibilityLabel="发新帖">
        <Icon name="add" size={27} color={theme.colors.onFab} />
      </Pressable>

      <OverflowMenu
        open={menuOpen}
        onClose={() => setMenuOpen(false)}
        items={menuItems}
        top={insets.top + 6}
      />
    </View>
  );
}

/** 子版块横条(设计稿:列表顶部一排可横滚的 tag)。 */
function SubBoardBar({
  boards,
  onPress,
}: {
  boards: readonly Board[];
  onPress: (board: Board) => void;
}) {
  const styles = useStyles();
  return (
    <ScrollView
      horizontal
      showsHorizontalScrollIndicator={false}
      style={styles.subBoardBar}
      contentContainerStyle={styles.subBoardBarContent}
    >
      {boards.map((board) => (
        <Pressable key={board.id} style={styles.subBoardTag} onPress={() => onPress(board)}>
          <Text style={styles.subBoardLabel} numberOfLines={1}>
            {board.name}
          </Text>
        </Pressable>
      ))}
    </ScrollView>
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
  subBoardBar: {
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  subBoardBarContent: {
    gap: theme.spacing.sm,
    paddingVertical: theme.spacing.md,
    paddingHorizontal: theme.spacing.row,
  },
  subBoardTag: {
    paddingVertical: 6,
    paddingHorizontal: 13,
    borderRadius: theme.radius.sm,
    backgroundColor: theme.colors.surface2,
    borderWidth: 1,
    borderColor: theme.colors.divider,
  },
  subBoardLabel: {
    ...theme.typography.listMeta,
    color: theme.colors.fg2,
  },
  footerText: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
    padding: theme.spacing.xl,
    textAlign: 'center',
  },
  footerSpacer: {
    height: 70,
  },
  fab: {
    position: 'absolute',
    right: theme.spacing.xl,
    bottom: 24,
    width: 50,
    height: 50,
    borderRadius: 25,
    backgroundColor: theme.colors.fab,
    alignItems: 'center',
    justifyContent: 'center',
    boxShadow: theme.shadows.elevation2,
  },
}));

import { FlashList } from '@shopify/flash-list';
import { useLocalSearchParams, useRouter } from 'expo-router';
import * as WebBrowser from 'expo-web-browser';
import { useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { mergeTopicPages, type Board, type Topic } from '@/core/api';
import { useAccounts } from '@/store/accounts';
import { useBoardFavoriteMutations, useIsBoardFavored } from '@/store/board-favor';
import { useRefreshTopicList, useTopicList, useTopicSort } from '@/store/topic-list';
import { Icon } from '@/ui/icon';
import { showLoginPrompt } from '@/ui/login-prompt';
import { OverflowMenu, type MenuItem } from '@/ui/menu';
import { showSnackbar } from '@/ui/snackbar';
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
  // 版头(CONTEXT.md):__F.topped_topic 带 tid 时在列表顶上给一条置顶入口,普通详情页打开
  const headTid = data?.pages[0]?.board?.head;

  const boardTitle = name ?? data?.pages[0]?.board?.name ?? `版块 ${id}`;
  const signedIn = useAccounts((state) => state.currentUid) !== null;
  const favored = useIsBoardFavored(boardId);
  const { add: addFavorite, remove: removeFavorite } = useBoardFavoriteMutations();

  /** 顶栏星标(设计稿 toggleStar):点了立刻变色,再把结果 toast 出来并留一手撤销。 */
  const toggleFavorite = () => {
    if (!signedIn) {
      showLoginPrompt(router, '登录后可把版块收藏到云端');
      return;
    }
    const board: Board = {
      id: boardId,
      kind: boardKind,
      ...(boardKind === 'collection' ? { stid: boardId } : { fid: boardId }),
      name: boardTitle,
    };
    // 撤销就是反着做一次,失败一律回到「服务端怎么说就怎么显示」的话术
    const run = (favor: boolean) =>
      (favor ? addFavorite(board) : removeFavorite(board)).then(
        () => {
          showSnackbar(favor ? '已收藏到「我的收藏」' : '已取消收藏该版面', {
            label: '撤销',
            onPress: () => run(!favor),
          });
        },
        (error: unknown) => {
          showSnackbar(error instanceof Error ? error.message : '收藏没能同步到云端');
        },
      );
    void run(!favored);
  };

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
    // 热帖/精华区(17 票)、浏览历史(16 票)、子版块(23 票)都复用本页的路由参数;
    // 还没做:收藏夹 11
    // (设计稿这一条写的是「子板块」,CONTEXT.md 的词条是「子版块」,按术语表来;
    // 顺序照设计稿 MENUS.list:浏览历史在热帖之后、精华区之前)
    const boardParams = {
      id: String(boardId),
      kind: boardKind,
      ...(name === undefined ? {} : { name }),
    };
    const entries: readonly (readonly [string, (() => void)?])[] = [
      ['24 小时热帖', () => router.push({ pathname: '/board/hot', params: boardParams })],
      ['浏览历史', () => router.push('/history')],
      ['精华区', () => router.push({ pathname: '/board/recommend', params: boardParams })],
      ['子版块', () => router.push({ pathname: '/board/sub-boards', params: boardParams })],
      ['收藏夹'],
    ];
    const pending = entries.map(([label, go]) => ({
      key: label,
      label,
      onPress: () => {
        setMenuOpen(false);
        (go ?? showNotAvailable)();
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
  }, [sort, setSort, boardId, boardKind, name, router]);

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
            <View>
              {headTid !== undefined && (
                <Pressable
                  style={styles.headRow}
                  android_ripple={{ color: theme.colors.divider }}
                  onPress={() =>
                    router.push({
                      pathname: '/topic/[tid]',
                      params: { tid: String(headTid), title: '版头' },
                    })
                  }
                  accessibilityLabel="打开版头"
                >
                  <Icon name="push_pin" size={16} color={theme.colors.accent} />
                  <Text style={styles.headLabel}>版头</Text>
                  <Icon name="chevron_right" size={18} color={theme.colors.meta} />
                </Pressable>
              )}
              {subBoards.length > 0 && <SubBoardBar boards={subBoards} onPress={openBoard} />}
            </View>
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
        <TopBarTitle variant="sub">{boardTitle}</TopBarTitle>
        {/* 已收藏用 accent 点亮:图标字体是静态 Outlined 版,没有设计稿那根 FILL 轴 */}
        <TopBarButton
          icon="star"
          size={23}
          onPress={toggleFavorite}
          color={favored ? theme.colors.accent : undefined}
          accessibilityLabel={favored ? '取消收藏本版块' : '收藏本版块'}
          style={topBarSpacer}
        />
        {/* 从列表页进搜索:带上当前版块,搜索选项里才有「当前板块」(15) */}
        <TopBarButton
          icon="search"
          size={22}
          onPress={() =>
            router.push({
              pathname: '/search',
              params: { boardId: String(boardId), kind: boardKind, boardName: boardTitle },
            })
          }
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
  // 版头置顶入口:设计稿没画这屏,按公告条的设计语言延伸(surface-2 底 + 分隔线)
  headRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingVertical: theme.spacing.md,
    paddingHorizontal: theme.spacing.row,
    backgroundColor: theme.colors.surface2,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  headLabel: {
    ...theme.typography.notice,
    color: theme.colors.fg2,
    flex: 1,
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

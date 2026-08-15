import { FlashList, type ListRenderItem } from '@shopify/flash-list';
import { useLocalSearchParams, useRouter } from 'expo-router';
import * as WebBrowser from 'expo-web-browser';
import { useCallback, useMemo, useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { mergeTopicPages, type Board, type Topic } from '@/core/api';
import { NgaError } from '@/core/net';
import { useAccounts } from '@/store/accounts';
import { useBoardFavoriteMutations, useIsBoardFavored } from '@/store/board-favor';
import { useTopicFilter } from '@/store/filters';
import { currentHost, useSettings } from '@/store/settings';
import {
  useRefreshTopicList,
  useRetryTopicList,
  useTopicList,
  useTopicSort,
} from '@/store/topic-list';
import { useLeftHanded } from '@/ui/appearance';
import { Icon } from '@/ui/icon';
import { showLoginPrompt } from '@/ui/login-prompt';
import { OverflowMenu, type MenuItem } from '@/ui/menu';
import { showSnackbar } from '@/ui/snackbar';
import { LoadFailed, LoadFailedNotice, loadFailureCopy } from '@/ui/error-screen';
import { EmptyState, LoadingFooter, LoadingState } from '@/ui/state-view';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { showNotAvailable } from '@/ui/toast';
import { useProgressiveReveal } from '@/ui/progressive';
import { TopicRow } from '@/ui/topic-row';
import { TopBar, TopBarButton, TopBarTitle, topBarSpacer } from '@/ui/top-bar';

/**
 * FlashList Android 默认只在视口外画 250px，约一条主题行；高速甩动会持续追着回收池跑。
 * 取约一个物理屏的余量:FlashList 会在静止/间歇期用 premountViews 把这段慢慢预绑,
 * 单次拖拽(~1500px)基本落在预绑区内——拖拽中不再出现行重绑帧。重绑帧(2.4~4.9ms)
 * 与轻帧交替会让 RenderThread 生产时序摆动 ±3ms,在慢性满队列(零余量)下周期性
 * 越过 SF latch 边界 → Dropped Frame → 肉眼可见的"停格+双倍跳"(第四轮排查)。
 */
const TOPIC_LIST_DRAW_DISTANCE = 2400;

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
  const solidBackground = useSettings((state) => state.settings.solidBackground);
  const leftHanded = useLeftHanded();

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
  } = useTopicList({ boardId, kind: boardKind, sort });

  const refresh = useRefreshTopicList({ boardId, kind: boardKind, sort });
  // 空态/错误态那两个按钮走「重试」而不是 refetch:它会先忘掉 thread.php 上次
  // 试通的格式 × 域名组合。用户按这个按钮时正是「拿回来的东西不对」的时候
  const retry = useRetryTopicList({ boardId, kind: boardKind, sort });

  /**
   * 「用网页版打开」:站内网页兜底页(19 票),不开系统浏览器。
   * 域名走设置里选的那个(22 票)——原生被封往往是整个域名被封。
   */
  const openWeb = useCallback(() => {
    const param = boardKind === 'collection' ? 'stid' : 'fid';
    router.push({
      pathname: '/web',
      params: {
        url: `${currentHost()}/thread.php?${param}=${boardId}`,
        ...(name === undefined ? {} : { title: name }),
      },
    });
  }, [router, boardId, boardKind, name]);

  // 置顶主题与镜像行每页都会再回来一次,拼页时按 tid 去重;
  // 之后再过一道屏蔽规则(21 票):命中标题关键词/作者/分类的主题直接不画这一行
  const filterTopics = useTopicFilter();
  const merged = useMemo(() => mergeTopicPages(data?.pages ?? []), [data?.pages]);
  const topics = useMemo(() => filterTopics(merged), [merged, filterTopics]);
  // 分帧揭示:整页 ~13 行一次性挂载要 31~35ms,横推动画起步直接掉帧;
  // 数据到达帧只挂 listHeader(版头行+chips)与列表壳(~18ms 已是该帧下限),行从下一帧起每帧 +3,动画走完前全部就位(useProgressiveReveal 的文档)
  const revealed = useProgressiveReveal(topics.length, { initial: 0, step: 3 });
  const revealDone = revealed >= topics.length;
  const shownTopics = revealDone ? topics : topics.slice(0, revealed);
  const loadedPages = data?.pages.length ?? 0;
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

  // useCallback:TopicRow 是 memo 的,onPress 不稳定的话每次父渲染都白比一遍
  const openBoard = useCallback(
    (board: Board) => {
      router.push({
        pathname: '/board/[id]',
        params: { id: String(board.id), name: board.name, kind: board.kind },
      });
    },
    [router],
  );

  const openTopic = useCallback(
    (topic: Topic) => {
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
    },
    [router, openBoard],
  );

  const renderTopic = useCallback<ListRenderItem<Topic>>(
    ({ item }) => <TopicRow topic={item} onPress={openTopic} />,
    [openTopic],
  );

  const listHeader = useMemo(() => {
    const subBoards = data?.pages[0]?.subBoards ?? [];
    return (
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
    );
  }, [data?.pages, headTid, openBoard, router, styles, theme]);

  const listFooter = useMemo(
    () => (
      <View>
        {isFetchingNextPage && <LoadingFooter text={`正在载入第 ${loadedPages + 1} 页…`} />}
        {!isFetchingNextPage && error !== null && (
          <Text style={styles.footerText}>{loadFailureCopy(error).headline}</Text>
        )}
        <View style={styles.footerSpacer} />
      </View>
    ),
    [error, isFetchingNextPage, loadedPages, styles],
  );

  const loadNextPage = useCallback(() => {
    // 揭示没追平时列表还是切片,contentSize 偏小会让 onEndReached 立刻误触发;
    // 放行的话每次进版块都会白拉一次第二页(NGA 对请求频率敏感)
    if (!revealDone) return;
    if (hasNextPage && !isFetchingNextPage) void fetchNextPage();
  }, [fetchNextPage, hasNextPage, isFetchingNextPage, revealDone]);

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
    if (isPending) return <LoadingState />;
    // 「拿不到列表」「版块真的空着」「拉到了但整页都被屏蔽规则藏掉」是三回事,
    // 说成同一句话时用户会以为版块是空的(2026-08-13:被限流时全站版块都显示
    // 「这个版块还没有主题」,连我们自己都查了半天)
    if (topics.length === 0 && error !== null) {
      // 解析不了 / 没有兜底可用 = 多半是被拦了,给足三个出路(重试 / 网页版 / 重登)。
      // 网络断了、服务端明说了理由这些不需要网页版,维持列表屏那个轻量形态
      const blocked =
        error instanceof NgaError && (error.kind === 'parse' || error.kind === 'unavailable');
      return blocked ? (
        <LoadFailed
          error={error}
          onRetry={retry}
          onOpenWeb={openWeb}
          onRelogin={() => router.push('/login')}
        />
      ) : (
        <View style={styles.center}>
          <LoadFailedNotice error={error} onRetry={retry} />
        </View>
      );
    }
    if (topics.length === 0) {
      const allFiltered = merged.length > 0;
      // 服务端连「主题列表」这个结构都没给(`__T`/`__F`/`__ROWS` 一个都没有):
      // 这不是空版块。正常情况下 core/api 已经把它变成错误了,这里是最后一道
      // 防线——别再让「没拿到」和「没帖子」共用一句话
      if (!allFiltered && data?.pages[0]?.listStructure === false) {
        return (
          <EmptyState
            icon="cloud_off"
            text={'没能拿到这个版块的主题列表\n多半是被论坛限流或拦下了'}
            action={{ label: '重试', onPress: retry }}
          />
        );
      }
      return (
        <EmptyState
          icon={allFiltered ? 'filter_alt' : 'article'}
          text={allFiltered ? '这一页的主题都被屏蔽规则挡住了' : '这个版块还没有主题'}
          action={{ label: '刷新', onPress: retry }}
        />
      );
    }
    return (
      // FlashList 要一个高度确定的父容器才算得出可视区
      <View style={styles.body}>
        <FlashList
          data={shownTopics}
          keyExtractor={(topic) => String(topic.tid)}
          renderItem={renderTopic}
          ListHeaderComponent={listHeader}
          ListFooterComponent={listFooter}
          drawDistance={TOPIC_LIST_DRAW_DISTANCE}
          maintainVisibleContentPosition={{ disabled: true }}
          onEndReachedThreshold={0.6}
          onEndReached={loadNextPage}
          // 翻下一页时 isRefetching 不会亮,不然底部转圈会连带把顶部也拽出来
          refreshing={isRefetching && !isFetchingNextPage}
          onRefresh={refresh}
        />
      </View>
    );
  };

  return (
    <View style={[styles.root, solidBackground && styles.rootSolid]}>
      <TopBar paddingHorizontal={4}>
        <TopBarButton
          icon="arrow_back"
          box={46}
          size={24}
          onPress={() => router.back()}
          accessibilityLabel="返回"
        />
        {/* 设计稿 isList 给标题标了 150 的截断宽度,右边三枚图标才排得开 */}
        <TopBarTitle variant="sub" maxWidth={150}>
          {boardTitle}
        </TopBarTitle>
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
      <Pressable
        style={[styles.fab, leftHanded ? styles.fabLeft : styles.fabRight]}
        onPress={showNotAvailable}
        accessibilityLabel="发新帖"
      >
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
  /** 「使用纯色背景」(22 票):把奶油底换成卡片那一档纯色 */
  rootSolid: {
    backgroundColor: theme.colors.surface,
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
    bottom: 24,
    width: 50,
    height: 50,
    borderRadius: 25,
    backgroundColor: theme.colors.fab,
    alignItems: 'center',
    justifyContent: 'center',
    boxShadow: theme.shadows.elevation2,
  },
  // 左手模式(22 票):FAB 镜像到左下角
  fabRight: {
    right: theme.spacing.xl,
  },
  fabLeft: {
    left: theme.spacing.xl,
  },
}));

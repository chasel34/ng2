import { LegendList } from '@legendapp/list/react-native';
import { useRouter, type Href } from 'expo-router';
import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import Reanimated, { useAnimatedStyle, useSharedValue } from 'react-native-reanimated';

import {
  parseBoardIdInput,
  pickActiveAnnouncement,
  type Board,
  type BoardCategory,
  type HomeAnnouncement,
} from '@/core/api';
import { NGA_LINK_FAILURE_MESSAGES, ngaLinkPath, parseNgaLink } from '@/core/local';
import { useAccounts } from '@/store/accounts';
import {
  useAddBoardFavoriteById,
  useBoardFavoriteMutations,
  useBoardFavorites,
  useFavoriteBoards,
} from '@/store/board-favor';
import { useBoardTree, useDismissedAnnouncements } from '@/store/board-tree';
import { AppDrawerContent } from '@/ui/app-drawer';
import { BoardIcon } from '@/ui/board-icon';
import { ConfirmDialog } from '@/ui/confirm-dialog';
import { Drawer, DrawerEdgeHandle, DRAWER_EDGE_WIDTH } from '@/ui/drawer';
import { LoadFailedNotice } from '@/ui/error-screen';
import { LoadingState } from '@/ui/state-view';
import { Icon, type IconName } from '@/ui/icon';
import { initialOf } from '@/ui/initial';
import { InputDialog } from '@/ui/input-dialog';
import { showLoginPrompt } from '@/ui/login-prompt';
import { showSnackbar } from '@/ui/snackbar';
import { SwipePager } from '@/ui/swipe-pager';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { TopBar, TopBarButton, TopBarTitle, topBarSpacer } from '@/ui/top-bar';

/** 设计稿:tab 44 高、版块宫格三列。 */
const TAB_BAR_HEIGHT = 44;

/** 横滑换分类时把选中的那格滚进视野,左边留出这么多,免得它永远贴在最左边。 */
const TAB_SCROLL_LEAD = 56;
const GRID_COLUMNS = 3;

/**
 * 「我的收藏」(CONTEXT.md「版块收藏」)在设计稿里就是首页的第一个 tab,
 * 所以这里把云端收藏包成一个合成分类插在服务端分类前面。
 * id 用 `favorites/` 前缀:服务端分类 id 是 wow / other 这种裸词,撞不上。
 */
const FAVORITES_CATEGORY_ID = 'favorites/mine';
/** 设计稿这一组的圆章写的是「收」,不是组名首字「我」。 */
const FAVORITES_INITIAL = '收';

/**
 * 服务端没有生效中的公告时显示的常驻提示——文案取自设计稿首页。
 * 关掉后同样记进「已关闭」列表,不会再冒出来。
 */
const BUILTIN_ANNOUNCEMENT: HomeAnnouncement = {
  id: 'builtin/multi-account',
  title: '建议登录多个账号，可有效改善跳转系统浏览器的问题',
};

/**
 * 首页正文按行虚拟化:最大的分类(手机游戏)有 300 多个版块,
 * 一次铺完会连带发出三百多个图标请求,所以摊平成
 * 「公告条 / 分组标题 / 一行三个版块」交给 LegendList。
 */
type HomeRow =
  | { readonly kind: 'announcement'; readonly key: string; readonly announcement: HomeAnnouncement }
  | {
      readonly kind: 'group';
      readonly key: string;
      readonly name: string;
      /** 组名圆章里的那个字 */
      readonly initial: string;
    }
  | {
      readonly kind: 'boards';
      readonly key: string;
      readonly boards: readonly Board[];
      /** 分组里的第一行:上方留的是宫格容器的 10,不是行距 14 */
      readonly first: boolean;
    }
  /** 空「我的收藏」的占位说明(游客引导登录、还没收藏都走它) */
  | {
      readonly kind: 'notice';
      readonly key: string;
      readonly icon: IconName;
      readonly text: string;
      readonly action?: { readonly label: string; readonly onPress: () => void };
    }
  /** 收藏拉不下来。文案交给统一的错误组件,不在这儿把异常摊出来 */
  | {
      readonly kind: 'error';
      readonly key: string;
      readonly error: unknown;
      readonly onRetry: () => void;
    };

/** 把一组版块摊成「一行三个」的宫格行。 */
function pushBoardRows(rows: HomeRow[], groupId: string, boards: readonly Board[]): void {
  for (let index = 0; index < boards.length; index += GRID_COLUMNS) {
    const slice = boards.slice(index, index + GRID_COLUMNS);
    rows.push({
      kind: 'boards',
      key: `boards/${groupId}/${slice[0]?.id ?? index}`,
      boards: slice,
      first: index === 0,
    });
  }
}

function buildRows(category: BoardCategory, announcement: HomeAnnouncement | undefined): HomeRow[] {
  const rows: HomeRow[] = [];
  if (announcement) {
    rows.push({ kind: 'announcement', key: `announcement/${announcement.id}`, announcement });
  }
  for (const group of category.groups) {
    rows.push({
      kind: 'group',
      key: `group/${group.id}`,
      name: group.name,
      initial: initialOf(group.name),
    });
    pushBoardRows(rows, group.id, group.boards);
  }
  return rows;
}

/**
 * 「我的收藏」tab 的行。收藏为空时不画组标题,只留一条说明——
 * 组标题下面空着一片会让人以为是没加载出来。
 */
function buildFavoriteRows(
  announcement: HomeAnnouncement | undefined,
  boards: readonly Board[],
  placeholder: HomeRow,
): HomeRow[] {
  const rows: HomeRow[] = [];
  if (announcement) {
    rows.push({ kind: 'announcement', key: `announcement/${announcement.id}`, announcement });
  }
  if (boards.length === 0) {
    rows.push(placeholder);
    return rows;
  }
  rows.push({
    kind: 'group',
    key: 'group/favorites',
    name: '我的收藏',
    initial: FAVORITES_INITIAL,
  });
  pushBoardRows(rows, 'favorites', boards);
  return rows;
}

export default function HomeScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [addBoardOpen, setAddBoardOpen] = useState(false);
  const [clearOpen, setClearOpen] = useState(false);
  const [urlOpen, setUrlOpen] = useState(false);
  // 「由 URL 读取」解不开时框里那行红字;undefined = 还没错过
  const [urlError, setUrlError] = useState<string | undefined>(undefined);
  // tab 认分类 id 而不是下标:服务端加减分类时,选中的还是原来那个分类
  const [activeCategoryId, setActiveCategoryId] = useState<string | null>(null);
  // tab 条现在也能被横滑改选中项,选中的那一格得自己滚进视野
  const tabScrollRef = useRef<ScrollView>(null);
  // 格子不等宽(分类名长短不一),按格数估位置会越估越偏,所以量实际布局
  const tabOffsets = useRef(new Map<string, number>()).current;
  // 下划线跟手的两样原料:pager 的连续页位(UI 线程直喂),和每格 tab 的实测几何。
  // 几何先攒在普通 ref 里、每次整体拷贝赋给共享值——9 个 onLayout 各自对共享值
  // 读改写会互相覆盖(JS 侧读不保证反映同一拍里的前一次写),最后只剩一格,
  // 下划线查到 undefined 就永远隐身。真机上栽过
  const pagerProgress = useSharedValue(0);
  const tabLayoutList = useRef<{ x: number; w: number }[]>([]).current;
  const tabLayouts = useSharedValue<readonly { x: number; w: number }[]>([]);
  // 松手那一刻就把高亮切过去(ViewPager2 onPageSelected 的时机);真正的窗口
  // 挪动等动画停稳。清账在下面的 effect:category 追上来就归还给它
  const [inFlightCategoryId, setInFlightCategoryId] = useState<string | null>(null);

  /**
   * 下划线钉在两格 tab 几何量的插值上,进度走到哪儿画到哪儿——与内容同帧,
   * 不等 React 的 commit。头尾越界(边缘阻尼拖出去的那点)夹回来。
   */
  const tabIndicatorStyle = useAnimatedStyle(() => {
    const layouts = tabLayouts.value;
    if (layouts.length === 0) return { opacity: 0 };
    const position = Math.min(Math.max(pagerProgress.value, 0), layouts.length - 1);
    const index = Math.floor(position);
    const fraction = position - index;
    const from = layouts[index];
    const to = layouts[index + 1] ?? from;
    if (from === undefined || to === undefined) return { opacity: 0 };
    return {
      opacity: 1,
      width: from.w + (to.w - from.w) * fraction,
      transform: [{ translateX: from.x + (to.x - from.x) * fraction }],
    };
  });

  const { data, isPending, error, refetch } = useBoardTree();

  const signedIn = useAccounts((state) => state.currentUid) !== null;
  const favorites = useBoardFavorites();
  // 收藏接口不下发图标,列表在 store 里按 id 从分类树认领过一遍才交给宫格
  const favoriteBoards = useFavoriteBoards();
  const { clear: clearFavorites, restore: restoreFavorites } = useBoardFavoriteMutations();
  const addFavoriteById = useAddBoardFavoriteById();

  const categories = useMemo(
    (): readonly BoardCategory[] =>
      // 分类树没回来就不插合成 tab:否则 categories 永远非空,下面的错误分支再也走不到
      data === undefined
        ? []
        : [{ id: FAVORITES_CATEGORY_ID, name: '我的收藏', groups: [] }, ...data.tree.categories],
    [data],
  );
  // 默认停在「我的收藏」(设计稿的 tab 0);游客那一栏只有登录引导,
  // 拿它当首屏等于把整个首页开成空的,所以游客直接落到第一个服务端分类
  const defaultCategory = signedIn ? categories[0] : (categories[1] ?? categories[0]);
  const category =
    categories.find((item) => item.id === activeCategoryId) ?? defaultCategory;
  /** tab 高亮认它:横滑松手先行一步,commit 落地后归还给 category */
  const shownCategoryId = inFlightCategoryId ?? category?.id;
  useEffect(() => {
    if (inFlightCategoryId !== null && inFlightCategoryId === category?.id) {
      setInFlightCategoryId(null);
    }
  }, [inFlightCategoryId, category?.id]);

  const dismissedIds = useDismissedAnnouncements((state) => state.ids);
  const dismiss = useDismissedAnnouncements((state) => state.dismiss);
  const announcement = useMemo(() => {
    // 先滤掉关过的再挑生效中的那条:否则关掉第一条之后，后面几条永远轮不到
    const available = (data?.tree.announcements ?? []).filter(
      (item) => !dismissedIds.includes(item.id),
    );
    const active = pickActiveAnnouncement(available, Date.now());
    if (active) return active;
    return dismissedIds.includes(BUILTIN_ANNOUNCEMENT.id) ? undefined : BUILTIN_ANNOUNCEMENT;
  }, [data, dismissedIds]);

  /** 空收藏时那条说明:游客给登录出口,拉失败给统一错误块,其余就是「还没收藏」。 */
  const favoritesPlaceholder = useMemo((): HomeRow => {
    if (!signedIn) {
      return {
        kind: 'notice',
        key: 'notice/guest',
        icon: 'person_add',
        text: '登录后可查看云端收藏的版块',
        action: { label: '去登录', onPress: () => router.push('/login') },
      };
    }
    if (favorites.isPending) {
      return { kind: 'notice', key: 'notice/loading', icon: 'star', text: '正在载入我的收藏…' };
    }
    if (favorites.error !== null) {
      return {
        kind: 'error',
        key: 'notice/error',
        error: favorites.error,
        onRetry: () => void favorites.refetch(),
      };
    }
    return {
      kind: 'notice',
      key: 'notice/empty',
      icon: 'star',
      text: '还没有收藏版块。进版块后点顶栏的星标,或用抽屉里的「添加版面 ID」。',
    };
  }, [signedIn, favorites.isPending, favorites.error, favorites.refetch, router]);

  // 分类在列表里的位置。横滑翻的就是它,tab 条也照它高亮
  const activeIndex = Math.max(
    0,
    categories.findIndex((item) => item.id === category?.id),
  );

  /**
   * 行数组按分类 id 缓存,**不挂在 activeIndex 上**:横滑 commit 后可见分类拿到的
   * 还是同一个数组引用,整屏列表一行都不用重画(翻页停稳那一拍不卡的关键一环)。
   * 只有内容真变了(分类树、公告、收藏)才整个换掉。仍然按需建:最大的分类
   * (手机游戏)摊开一百多行,面板没轮到它就不白烧。
   */
  const rowsCache = useMemo(
    () => new Map<string, readonly HomeRow[]>(),
    [categories, announcement, favoriteBoards, favoritesPlaceholder],
  );
  const rowsFor = useCallback(
    (item: (typeof categories)[number]): readonly HomeRow[] => {
      let rows = rowsCache.get(item.id);
      if (rows === undefined) {
        rows =
          item.id === FAVORITES_CATEGORY_ID
            ? buildFavoriteRows(announcement, favoriteBoards, favoritesPlaceholder)
            : buildRows(item, announcement);
        rowsCache.set(item.id, rows);
      }
      return rows;
    },
    [rowsCache, announcement, favoriteBoards, favoritesPlaceholder],
  );

  // 横滑换了分类之后,选中的那一格可能在 tab 条视野外
  useEffect(() => {
    const x = tabOffsets.get(shownCategoryId ?? '');
    if (x === undefined) return;
    tabScrollRef.current?.scrollTo({ x: Math.max(0, x - TAB_SCROLL_LEAD), animated: true });
  }, [shownCategoryId, tabOffsets]);

  const openBoard = useCallback(
    (board: Board) => {
      router.push({
        pathname: '/board/[id]',
        params: { id: String(board.id), name: board.name, kind: board.kind },
      });
    },
    [router],
  );

  const failed = (cause: unknown, fallback: string) =>
    showSnackbar(cause instanceof Error ? cause.message : fallback);

  /** 抽屉「添加版面 ID」。设计稿是先关抽屉再弹对话框。 */
  const openAddBoard = () => {
    setDrawerOpen(false);
    if (!signedIn) {
      showLoginPrompt(router, '登录后可把版块收藏到云端');
      return;
    }
    setAddBoardOpen(true);
  };

  const confirmAddBoard = (text: string) => {
    setAddBoardOpen(false);
    const boardId = parseBoardIdInput(text);
    if (boardId === undefined) {
      showSnackbar('版面 ID 只能是整数,例如 459 或 -7');
      return;
    }
    // 先按「普通版块」乐观显示;是不是合集、真名叫什么,以重拉回来的列表为准
    const provisional: Board = { id: boardId, kind: 'board', fid: boardId, name: `版块 ${boardId}` };
    void addFavoriteById(boardId, provisional).then(
      (board) =>
        // 设计稿的文案是「已添加版面到我的收藏」;这里带上服务端给的名字,
        // 手输 id 时才看得出到底收到了哪个版块(尤其 stid 输进去解析成合集的时候)
        showSnackbar(`已添加「${board.name}」到我的收藏`, {
          label: '打开',
          onPress: () => openBoard(board),
        }),
      (error: unknown) => failed(error, '添加版面失败'),
    );
  };

  /** 抽屉「清空我的收藏」。服务端没有批量接口,确认后逐个删,所以先问一句。 */
  const openClearFavorites = () => {
    setDrawerOpen(false);
    if (!signedIn) {
      showLoginPrompt(router, '登录后可管理云端收藏的版块');
      return;
    }
    if (favoriteBoards.length === 0) {
      showSnackbar('还没有收藏任何版块');
      return;
    }
    setClearOpen(true);
  };

  /** 抽屉「由 URL 读取」(24)。同样是关抽屉再弹框。 */
  const openFromUrl = () => {
    setDrawerOpen(false);
    setUrlError(undefined);
    setUrlOpen(true);
  };

  /**
   * 粘进来的链接就地解析:解得开才跳,解不开留在框里说明哪儿不对——
   * 关掉框再弹 toast 的话,想改那一行还得重新粘一次。
   */
  const confirmFromUrl = (text: string) => {
    const result = parseNgaLink(text);
    if (!result.ok) {
      setUrlError(NGA_LINK_FAILURE_MESSAGES[result.reason]);
      return;
    }
    setUrlOpen(false);
    setUrlError(undefined);
    // 深链的落地路径由 core 那一份统一拼(与 `+native-intent` 同源),
    // 拿到的是字符串,typedRoutes 认不出来,只能在这儿转一次
    router.push(ngaLinkPath(result.link) as Href);
  };

  const confirmClearFavorites = () => {
    setClearOpen(false);
    void clearFavorites().then(
      (removed) =>
        showSnackbar('已清空我的收藏', {
          label: '撤销',
          // 撤销 = 逐个收回来,再失败就只能说一声了
          onPress: () =>
            void restoreFavorites(removed).catch((error: unknown) =>
              failed(error, '收藏没能收回来'),
            ),
        }),
      (error: unknown) => failed(error, '清空收藏失败'),
    );
  };

  const renderRow = useCallback(
    ({ item }: { item: HomeRow }) => (
      <HomeRowView row={item} onOpenBoard={openBoard} onDismiss={dismiss} />
    ),
    [openBoard, dismiss],
  );

  /** 横滑落到第几个分类(`SwipePager` 的「页」从 1 起,这里的下标从 0 起)。 */
  const pickCategoryAt = useCallback(
    (position: number) => {
      const next = categories[position - 1];
      if (next !== undefined) setActiveCategoryId(next.id);
    },
    [categories],
  );

  /** 松手即定向:tab 高亮与 tab 条滚动先行,窗口挪动等 `onChange`(动画停稳)。 */
  const markCategoryAt = useCallback(
    (position: number) => {
      const next = categories[position - 1];
      if (next !== undefined) setInFlightCategoryId(next.id);
    },
    [categories],
  );

  /**
   * 一个分类的一屏。相邻两块也会被画出来(这就是横滑跟手的来源),
   * 但只有屏幕正中那一块能滚——两边接了纵向滚动会跟横滑抢手势。
   */
  const renderCategory = useCallback(
    (position: number) => {
      const item = categories[position - 1];
      if (item === undefined) return null;
      return (
        // 列表要一个高度确定的父容器才算得出可视区
        <View style={styles.body}>
          <LegendList
            data={rowsFor(item)}
            keyExtractor={(row) => row.key}
            recycleItems
            // 行是异构的:公告条、分组标题、一行三个版块的宫格、空态说明、错误块,
            // 高度差好几倍。不给 getItemType 的话它们混在同一个回收池里,
            // 复用到形状完全不同的行就得重新量一次
            getItemType={(row) => row.kind}
            contentContainerStyle={styles.bodyContent}
            renderItem={renderRow}
            scrollEnabled={position - 1 === activeIndex}
          />
        </View>
      );
    },
    [categories, rowsFor, renderRow, activeIndex, styles],
  );

  return (
    <View style={styles.root}>
      <TopBar
        below={
          <ScrollView
            ref={tabScrollRef}
            horizontal
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.tabBar}
          >
            {categories.map((item, index) => (
              <Pressable
                key={item.id}
                onPress={() => setActiveCategoryId(item.id)}
                onLayout={(event) => {
                  const { x, width } = event.nativeEvent.layout;
                  tabOffsets.set(item.id, x);
                  tabLayoutList[index] = { x, w: width };
                  tabLayouts.value = [...tabLayoutList];
                }}
                style={styles.tab}
              >
                <Text
                  style={[styles.tabLabel, item.id !== shownCategoryId && styles.tabLabelInactive]}
                >
                  {item.name}
                </Text>
              </Pressable>
            ))}
            <Reanimated.View pointerEvents="none" style={[styles.tabIndicator, tabIndicatorStyle]} />
          </ScrollView>
        }
      >
        <TopBarButton
          icon="menu"
          box={46}
          size={24}
          onPress={() => setDrawerOpen(true)}
          accessibilityLabel="打开抽屉"
        />
        <TopBarTitle>NG2</TopBarTitle>
        {/* 原先右边还有个「更多」kebab,条目全部并进了左侧抽屉(它在每一屏都拉得出来,
            不必先退回首页),顶栏只留搜索 */}
        <TopBarButton
          icon="search"
          size={23}
          onPress={() => router.push('/search')}
          accessibilityLabel="搜索"
          style={topBarSpacer}
        />
      </TopBar>

      {isPending ? (
        <LoadingState />
      ) : category === undefined ? (
        // 分类树拉不下来 = 除「我的收藏」外每个 tab 都是空的,所以这一屏也走统一错误块
        <View style={styles.center}>
          <LoadFailedNotice error={error} onRetry={() => void refetch()} />
        </View>
      ) : (
        <SwipePager
          page={activeIndex + 1}
          count={categories.length}
          onChange={pickCategoryAt}
          onTarget={markCategoryAt}
          renderPage={renderCategory}
          progress={pagerProgress}
          // 左边缘那一条是抽屉的地盘:从那儿右滑是「拉抽屉」,不是「翻上一个分类」
          edgeGuard={DRAWER_EDGE_WIDTH}
          style={styles.body}
        />
      )}

      <DrawerEdgeHandle onOpen={() => setDrawerOpen(true)} />
      <Drawer open={drawerOpen} onClose={() => setDrawerOpen(false)}>
        <AppDrawerContent
          onNavigate={() => setDrawerOpen(false)}
          onAddBoard={openAddBoard}
          onClearFavorites={openClearFavorites}
          onOpenUrl={openFromUrl}
        />
      </Drawer>
      {/* 抽屉的两个收藏入口,对话框归宿主页面(设计稿:关抽屉 → 弹框) */}
      <InputDialog
        open={addBoardOpen}
        title="添加版面 ID"
        hint="填 fid 或合集 stid,例如 459、-7"
        confirmLabel="添加"
        keyboardType="numeric"
        onCancel={() => setAddBoardOpen(false)}
        onConfirm={confirmAddBoard}
      />
      <InputDialog
        open={urlOpen}
        title="由 URL 读取"
        hint="支持 read.php / thread.php 链接"
        error={urlError}
        confirmLabel="打开"
        keyboardType="url"
        // 改了链接就把上一次的红字撤了,别让它挂到下一次点「打开」
        onChangeText={() => setUrlError(undefined)}
        onCancel={() => setUrlOpen(false)}
        onConfirm={confirmFromUrl}
      />
      <ConfirmDialog
        open={clearOpen}
        title="清空我的收藏"
        message={`将取消收藏全部 ${favoriteBoards.length} 个版块。清空后可以撤销。`}
        confirmLabel="清空"
        destructive
        onCancel={() => setClearOpen(false)}
        onConfirm={confirmClearFavorites}
      />
    </View>
  );
}

/**
 * 首页列表的一行。
 *
 * 单独抽出来包 `memo`,是因为 `HomeScreen` 会因为抽屉、菜单、三个对话框的开合
 * 频繁重渲染,而这一屏最大的分类有 300 多个版块(摊成一百多行)。
 * props 的引用稳定性:`row` 来自 `rows` 那个 `useMemo`,`onOpenBoard` 是
 * `useCallback`,`onDismiss` 是 zustand 的 action(建仓时就定死),三个都稳。
 */
const HomeRowView = memo(function HomeRowView({
  row,
  onOpenBoard,
  onDismiss,
}: {
  row: HomeRow;
  onOpenBoard: (board: Board) => void;
  onDismiss: (id: string) => void;
}) {
  const styles = useStyles();
  const theme = useTheme();

  switch (row.kind) {
    case 'announcement':
      return (
        <View style={styles.announcement}>
          <Icon
            name="campaign"
            size={19}
            color={theme.colors.accent}
            style={styles.announcementIcon}
          />
          <Text style={styles.announcementText}>{row.announcement.title}</Text>
          <Pressable
            onPress={() => onDismiss(row.announcement.id)}
            hitSlop={10}
            accessibilityLabel="关闭公告"
          >
            <Icon name="close" size={17} color={theme.colors.meta} />
          </Pressable>
        </View>
      );
    case 'group':
      return (
        <View style={styles.groupHeader}>
          <View style={styles.groupBadge}>
            <Text style={styles.groupBadgeText} allowFontScaling={false}>
              {row.initial}
            </Text>
          </View>
          <Text style={styles.groupName}>{row.name}</Text>
        </View>
      );
    case 'notice':
      return (
        <View style={styles.notice}>
          <Icon name={row.icon} size={34} color={theme.colors.meta} />
          <Text style={styles.noticeText}>{row.text}</Text>
          {row.action !== undefined && (
            <Pressable style={styles.retry} onPress={row.action.onPress}>
              <Text style={styles.retryLabel}>{row.action.label}</Text>
            </Pressable>
          )}
        </View>
      );
    case 'error':
      return <LoadFailedNotice error={row.error} onRetry={row.onRetry} />;
    case 'boards':
      return (
        <View style={[styles.grid, row.first && styles.gridFirst]}>
          {row.boards.map((board) => (
            <Pressable key={board.id} style={styles.cell} onPress={() => onOpenBoard(board)}>
              <View style={styles.cellIcon}>
                <BoardIcon board={board} />
              </View>
              {/* 版块名长了要么折行要么打省略号,不能在半路被裁掉(「网事杂谈」→「网事杂」) */}
              <Text style={styles.cellLabel} numberOfLines={2} ellipsizeMode="tail">
                {board.name}
              </Text>
            </Pressable>
          ))}
        </View>
      );
  }
});

const useStyles = createThemedStyles((theme) => ({
  root: {
    flex: 1,
    backgroundColor: theme.colors.bg,
  },
  tabBar: {
    paddingHorizontal: 6,
  },
  tab: {
    height: TAB_BAR_HEIGHT,
    paddingHorizontal: theme.spacing.lg,
    justifyContent: 'center',
  },
  // 设计稿用的是 inset box-shadow,不占布局;所以下划线绝对定位,不能用 border。
  // 单条浮动线,位置与宽度由 pager 进度驱动(tabIndicatorStyle),跟手不等 commit
  tabIndicator: {
    position: 'absolute',
    left: 0,
    bottom: 0,
    height: 3,
    backgroundColor: theme.colors.onTopbar,
  },
  tabLabel: {
    ...theme.typography.tab,
    color: theme.colors.onTopbar,
  },
  tabLabelInactive: {
    opacity: 0.62,
  },
  body: {
    flex: 1,
  },
  bodyContent: {
    paddingBottom: 90,
  },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: theme.spacing.md,
    padding: theme.spacing.xl,
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
  // 空「我的收藏」的占位。设计稿没画这一屏,按错误屏那套(图标 + 说明 + 圆角按钮)延伸
  notice: {
    alignItems: 'center',
    gap: theme.spacing.md,
    paddingVertical: 56,
    paddingHorizontal: theme.spacing.xl,
  },
  noticeText: {
    ...theme.typography.notice,
    color: theme.colors.fg2,
    textAlign: 'center',
  },
  announcement: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: 10,
    paddingVertical: theme.spacing.row,
    paddingHorizontal: theme.spacing.page,
    backgroundColor: theme.colors.surface2,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  announcementIcon: {
    marginTop: 1,
  },
  announcementText: {
    ...theme.typography.notice,
    color: theme.colors.fg2,
    flex: 1,
  },
  groupHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.md,
    paddingTop: theme.spacing.lg,
    paddingHorizontal: theme.spacing.page,
    paddingBottom: theme.spacing.xs,
  },
  groupBadge: {
    width: 17,
    height: 17,
    borderRadius: 9,
    borderWidth: 1,
    borderColor: theme.colors.accent,
    alignItems: 'center',
    justifyContent: 'center',
  },
  groupBadgeText: {
    ...theme.typography.badge,
    color: theme.colors.accent,
  },
  groupName: {
    ...theme.typography.section,
    color: theme.colors.fg,
  },
  grid: {
    flexDirection: 'row',
    paddingHorizontal: theme.spacing.sm,
    // 设计稿宫格的行距 14——摊成一行一个列表项之后,行距落到每行的上边距
    paddingTop: theme.spacing.row,
  },
  gridFirst: {
    // 分组的第一行上方是宫格容器自己的 10
    paddingTop: 10,
  },
  cell: {
    width: `${100 / GRID_COLUMNS}%`,
    paddingTop: theme.spacing.sm,
    paddingHorizontal: 6,
    paddingBottom: 10,
    alignItems: 'center',
  },
  cellIcon: {
    marginBottom: theme.spacing.sm,
  },
  cellLabel: {
    ...theme.typography.gridLabel,
    color: theme.colors.fg,
    textAlign: 'center',
    // 撑满格子而不是让 Text 自己量:父容器 alignItems 是 center,
    // 交给它量宽度时长名字容易被算窄一截,折行位置跟着往前跑
    alignSelf: 'stretch',
  },
}));

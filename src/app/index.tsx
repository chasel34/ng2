import { FlashList } from '@shopify/flash-list';
import { useRouter, type Href } from 'expo-router';
import { useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

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
} from '@/store/board-favor';
import { useBoardTree, useDismissedAnnouncements } from '@/store/board-tree';
import { AppDrawerContent } from '@/ui/app-drawer';
import { BoardIcon } from '@/ui/board-icon';
import { ConfirmDialog } from '@/ui/confirm-dialog';
import { Drawer, DrawerEdgeHandle } from '@/ui/drawer';
import { Icon, type IconName } from '@/ui/icon';
import { initialOf } from '@/ui/initial';
import { InputDialog } from '@/ui/input-dialog';
import { showLoginPrompt } from '@/ui/login-prompt';
import { OverflowMenu, type MenuItem } from '@/ui/menu';
import { showSnackbar } from '@/ui/snackbar';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { showNotAvailable } from '@/ui/toast';
import { TopBar, TopBarButton, TopBarTitle, topBarSpacer } from '@/ui/top-bar';

/** 设计稿:tab 44 高、版块宫格三列。 */
const TAB_BAR_HEIGHT = 44;
const GRID_COLUMNS = 3;

/**
 * 「我的收藏」(CONTEXT.md「版块收藏」)在设计稿里就是首页的第一个 tab,
 * 所以这里把云端收藏包成一个合成分类插在服务端分类前面。
 * id 用 `favorites/` 前缀:服务端分类 id 是 wow / other 这种裸词,撞不上。
 */
const FAVORITES_CATEGORY_ID = 'favorites/mine';
/** 设计稿这一组的圆章写的是「收」,不是组名首字「我」。 */
const FAVORITES_INITIAL = '收';
/** 收藏还没拉回来时的空列表。用常量而不是 `?? []`,免得每次渲染都换一个引用把 memo 打穿。 */
const NO_BOARDS: readonly Board[] = [];

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
 * 「公告条 / 分组标题 / 一行三个版块」交给 FlashList。
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
  /** 空「我的收藏」的占位说明(游客引导登录、还没收藏、拉取失败都走它) */
  | {
      readonly kind: 'notice';
      readonly key: string;
      readonly icon: IconName;
      readonly text: string;
      readonly action?: { readonly label: string; readonly onPress: () => void };
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
  notice: Extract<HomeRow, { kind: 'notice' }>,
): HomeRow[] {
  const rows: HomeRow[] = [];
  if (announcement) {
    rows.push({ kind: 'announcement', key: `announcement/${announcement.id}`, announcement });
  }
  if (boards.length === 0) {
    rows.push(notice);
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
  const insets = useSafeAreaInsets();
  const router = useRouter();

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [addBoardOpen, setAddBoardOpen] = useState(false);
  const [clearOpen, setClearOpen] = useState(false);
  const [urlOpen, setUrlOpen] = useState(false);
  // 「由 URL 读取」解不开时框里那行红字;undefined = 还没错过
  const [urlError, setUrlError] = useState<string | undefined>(undefined);
  // tab 认分类 id 而不是下标:服务端加减分类时,选中的还是原来那个分类
  const [activeCategoryId, setActiveCategoryId] = useState<string | null>(null);

  const { data, isPending, error, refetch } = useBoardTree();

  const signedIn = useAccounts((state) => state.currentUid) !== null;
  const favorites = useBoardFavorites();
  const favoriteBoards = favorites.data ?? NO_BOARDS;
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

  /** 空收藏时那条说明:游客给登录出口,拉失败给重试,其余就是「还没收藏」。 */
  const favoritesNotice = useMemo((): Extract<HomeRow, { kind: 'notice' }> => {
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
        kind: 'notice',
        key: 'notice/error',
        icon: 'cloud_off',
        text: favorites.error instanceof Error ? favorites.error.message : '收藏列表拉不下来',
        action: { label: '重试', onPress: () => void favorites.refetch() },
      };
    }
    return {
      kind: 'notice',
      key: 'notice/empty',
      icon: 'star',
      text: '还没有收藏版块。进版块后点顶栏的星标,或用抽屉里的「添加版面 ID」。',
    };
  }, [signedIn, favorites.isPending, favorites.error, favorites.refetch, router]);

  const rows = useMemo(() => {
    if (category === undefined) return [];
    if (category.id === FAVORITES_CATEGORY_ID) {
      return buildFavoriteRows(announcement, favoriteBoards, favoritesNotice);
    }
    return buildRows(category, announcement);
  }, [category, announcement, favoriteBoards, favoritesNotice]);

  const menuItems: readonly MenuItem[] = useMemo(
    () =>
      // 还没做:我的主题/我的回复 14、设置 22;短消息整块不做(spec §1)
      ['我的主题', '我的回复', '我的缓存', '短消息', '收藏夹', '设置'].map((label, index) => ({
        key: label,
        label,
        gapBefore: index === 3,
        onPress: () => {
          setMenuOpen(false);
          if (label === '收藏夹') router.push('/favorites');
          else if (label === '我的缓存') router.push('/caches');
          else showNotAvailable();
        },
      })),
    [router],
  );

  const openBoard = (board: Board) => {
    router.push({
      pathname: '/board/[id]',
      params: { id: String(board.id), name: board.name, kind: board.kind },
    });
  };

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

  const renderRow = (row: HomeRow) => {
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
              onPress={() => dismiss(row.announcement.id)}
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
      case 'boards':
        return (
          <View style={[styles.grid, row.first && styles.gridFirst]}>
            {row.boards.map((board) => (
              <Pressable key={board.id} style={styles.cell} onPress={() => openBoard(board)}>
                <View style={styles.cellIcon}>
                  <BoardIcon board={board} />
                </View>
                <Text style={styles.cellLabel}>{board.name}</Text>
              </Pressable>
            ))}
          </View>
        );
    }
  };

  return (
    <View style={styles.root}>
      <TopBar
        below={
          <ScrollView
            horizontal
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.tabBar}
          >
            {categories.map((item) => (
              <Pressable
                key={item.id}
                onPress={() => setActiveCategoryId(item.id)}
                style={styles.tab}
              >
                <Text
                  style={[styles.tabLabel, item.id !== category?.id && styles.tabLabelInactive]}
                >
                  {item.name}
                </Text>
                {item.id === category?.id && <View style={styles.tabIndicator} />}
              </Pressable>
            ))}
          </ScrollView>
        }
      >
        <TopBarButton
          icon="menu"
          size={24}
          onPress={() => setDrawerOpen(true)}
          accessibilityLabel="打开抽屉"
        />
        <TopBarTitle>NGA 阅读器</TopBarTitle>
        <TopBarButton
          icon="search"
          size={23}
          onPress={() => router.push('/search')}
          accessibilityLabel="搜索"
          style={topBarSpacer}
        />
        <TopBarButton
          icon="more_vert"
          size={23}
          onPress={() => setMenuOpen(true)}
          accessibilityLabel="更多"
        />
      </TopBar>

      {isPending ? (
        <View style={styles.center}>
          <ActivityIndicator color={theme.colors.primary} />
        </View>
      ) : category === undefined ? (
        <View style={styles.center}>
          <Icon name="cloud_off" size={40} color={theme.colors.meta} />
          <Text style={styles.errorText}>
            {error instanceof Error ? error.message : '版块列表拉不下来'}
          </Text>
          <Pressable style={styles.retry} onPress={() => void refetch()}>
            <Text style={styles.retryLabel}>重试</Text>
          </Pressable>
        </View>
      ) : (
        // FlashList 要一个高度确定的父容器才算得出可视区
        <View style={styles.body}>
          <FlashList
            data={rows}
            keyExtractor={(row) => row.key}
            contentContainerStyle={styles.bodyContent}
            renderItem={({ item }) => renderRow(item)}
          />
        </View>
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
      <OverflowMenu
        open={menuOpen}
        onClose={() => setMenuOpen(false)}
        items={menuItems}
        top={insets.top + 6}
      />
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
  // 设计稿用的是 inset box-shadow,不占布局;所以下划线绝对定位,不能用 border
  tabIndicator: {
    position: 'absolute',
    left: 0,
    right: 0,
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
  },
}));

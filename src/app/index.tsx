import { FlashList } from '@shopify/flash-list';
import { useRouter } from 'expo-router';
import { useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import {
  pickActiveAnnouncement,
  type Board,
  type BoardCategory,
  type HomeAnnouncement,
} from '@/core/api';
import { useBoardTree, useDismissedAnnouncements } from '@/store/board-tree';
import { AppDrawerContent } from '@/ui/app-drawer';
import { BoardIcon } from '@/ui/board-icon';
import { Drawer, DrawerEdgeHandle } from '@/ui/drawer';
import { Icon } from '@/ui/icon';
import { initialOf } from '@/ui/initial';
import { OverflowMenu, type MenuItem } from '@/ui/menu';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { showNotAvailable } from '@/ui/toast';
import { TopBar, TopBarButton, TopBarTitle, topBarSpacer } from '@/ui/top-bar';

/** 设计稿:tab 44 高、版块宫格三列。 */
const TAB_BAR_HEIGHT = 44;
const GRID_COLUMNS = 3;

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
  | { readonly kind: 'group'; readonly key: string; readonly name: string }
  | {
      readonly kind: 'boards';
      readonly key: string;
      readonly boards: readonly Board[];
      /** 分组里的第一行:上方留的是宫格容器的 10,不是行距 14 */
      readonly first: boolean;
    };

function buildRows(category: BoardCategory, announcement: HomeAnnouncement | undefined): HomeRow[] {
  const rows: HomeRow[] = [];
  if (announcement) {
    rows.push({ kind: 'announcement', key: `announcement/${announcement.id}`, announcement });
  }
  for (const group of category.groups) {
    rows.push({ kind: 'group', key: `group/${group.id}`, name: group.name });
    for (let index = 0; index < group.boards.length; index += GRID_COLUMNS) {
      const boards = group.boards.slice(index, index + GRID_COLUMNS);
      rows.push({
        kind: 'boards',
        key: `boards/${group.id}/${boards[0]?.id ?? index}`,
        boards,
        first: index === 0,
      });
    }
  }
  return rows;
}

export default function HomeScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  // tab 认分类 id 而不是下标:服务端加减分类时,选中的还是原来那个分类
  const [activeCategoryId, setActiveCategoryId] = useState<string | null>(null);

  const { data, isPending, error, refetch } = useBoardTree();
  const categories = data?.tree.categories ?? [];
  const category =
    categories.find((item) => item.id === activeCategoryId) ?? categories[0];

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

  const rows = useMemo(
    () => (category === undefined ? [] : buildRows(category, announcement)),
    [category, announcement],
  );

  const menuItems: readonly MenuItem[] = useMemo(
    () =>
      // 还没做:我的主题/我的回复 14、我的缓存 20、设置 22;短消息整块不做(spec §1)
      ['我的主题', '我的回复', '我的缓存', '短消息', '收藏夹', '设置'].map((label, index) => ({
        key: label,
        label,
        gapBefore: index === 3,
        onPress: () => {
          setMenuOpen(false);
          if (label === '收藏夹') router.push('/favorites');
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
                {initialOf(row.name)}
              </Text>
            </View>
            <Text style={styles.groupName}>{row.name}</Text>
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
          onPress={showNotAvailable}
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
        <AppDrawerContent onNavigate={() => setDrawerOpen(false)} />
      </Drawer>
      <OverflowMenu
        open={menuOpen}
        onClose={() => setMenuOpen(false)}
        items={menuItems}
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

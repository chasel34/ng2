import { FlashList, type FlashListRef } from '@shopify/flash-list';
import { useLocalSearchParams, useRouter } from 'expo-router';
import * as WebBrowser from 'expo-web-browser';
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Animated,
  PanResponder,
  Pressable,
  Text,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import type { Floor } from '@/core/api';
import { useTopicDetail } from '@/store/topic-detail';
import { FavoriteFolderDialog } from '@/ui/favorite-folder-dialog';
import { FloorCard, type FloorContext } from '@/ui/floor-card';
import { isHorizontalDragActive } from '@/ui/horizontal-drag';
import { Icon } from '@/ui/icon';
import { InputDialog } from '@/ui/input-dialog';
import { OverflowMenu, type MenuItem } from '@/ui/menu';
import { PageBar } from '@/ui/page-bar';
import {
  clampPage,
  parseJumpTarget,
  swipeHintText,
  swipeOffset,
  swipeTargetPage,
} from '@/ui/paging';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { showNotAvailable, showToast } from '@/ui/toast';
import { TopBar, TopBarButton, TopBarTitle, topBarSpacer } from '@/ui/top-bar';

/**
 * 走够这么多才认成横滑(且横向位移要明显压过纵向,不然抢了列表的上下滚动)。
 * 数值照设计稿 isArticle 那段 `swipeMove`;翻页与提示的阈值在 `ui/paging`,
 * 三个翻页入口共用同一套算术。
 */
const SWIPE_ACTIVATE = 12;

/**
 * 帖子详情(CONTEXT.md:主题里的楼层流)。
 *
 * 路由参数由 05 定好:`tid` 是真实 tid、`fav` 是 fav 码、`title` 免得等 read.php 才有标题。
 *
 * 翻页有三个入口——顶部页码条、跳页对话框、左右滑动——它们都只改同一个 `page` state,
 * 所以三者天然一致;每页的数据按页码进 Query 缓存,翻回去不会再打一次 read.php。
 */
export default function TopicScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();
  const insets = useSafeAreaInsets();

  const { tid, title, fav } = useLocalSearchParams<{
    tid: string;
    title?: string;
    fav?: string;
  }>();
  const topicId = Number(tid);

  const [page, setPage] = useState(1);
  const [jumpOpen, setJumpOpen] = useState(false);
  const [fabOpen, setFabOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [favorOpen, setFavorOpen] = useState(false);
  const listRef = useRef<FlashListRef<Floor>>(null);

  const { data, error, isPending, isFetching, isPlaceholderData, refetch } = useTopicDetail({
    tid: topicId,
    page,
    ...(fav === undefined ? {} : { favCode: fav }),
  });

  const totalPages = data?.totalPages ?? 1;

  // 页码条、跳页、滑动三个入口都收敛到这里,页码规则只有一套(ui/paging)
  const goToPage = (next: number) => {
    const clamped = clampPage(next, totalPages);
    if (clamped === page) return;
    setPage(clamped);
    // 换页等于换内容,停在上一页的滚动位置会让人以为没翻动
    listRef.current?.scrollToOffset({ offset: 0, animated: false });
  };

  const swipe = useSwipePaging({ page, totalPages, onChange: goToPage });

  /**
   * 顶栏「更多」菜单,条目与顺序照设计稿 `MENUS.article`。
   * 还没做的几项(复制链接、缓存本页 20 票、分享、夜间模式 22 票)先 toast「本版本未开放」,
   * 各票到时候换掉自己那一行即可。
   */
  const menuItems: readonly MenuItem[] = useMemo(() => {
    // 点哪一条都先收起菜单,免得动作做完了菜单还盖在上面
    const pick = (run: () => void) => () => {
      setMenuOpen(false);
      run();
    };
    return [
      { key: 'jump', label: '跳页', onPress: pick(() => setJumpOpen(true)) },
      { key: 'copy', label: '复制链接', onPress: pick(showNotAvailable) },
      { key: 'favor', label: '收藏本帖', onPress: pick(() => setFavorOpen(true)) },
      { key: 'cache', label: '缓存本页', onPress: pick(showNotAvailable) },
      { key: 'share', label: '分享', onPress: pick(showNotAvailable) },
      { key: 'theme', label: '夜间模式', gapBefore: true, onPress: pick(showNotAvailable) },
    ];
  }, []);

  const body = () => {
    if (isPending) {
      return (
        <View style={styles.center}>
          <ActivityIndicator color={theme.colors.primary} />
        </View>
      );
    }
    if (data === undefined || data.floors.length === 0) {
      const failed = error !== null;
      return (
        <View style={styles.center}>
          <Icon name={failed ? 'cloud_off' : 'article'} size={40} color={theme.colors.meta} />
          <Text style={styles.errorText}>
            {failed
              ? error instanceof Error
                ? error.message
                : '这一页拉不下来'
              : '这一页没有楼层'}
          </Text>
          <Pressable style={styles.retry} onPress={() => void refetch()}>
            <Text style={styles.retryLabel}>{failed ? '重试' : '刷新'}</Text>
          </Pressable>
        </View>
      );
    }

    const floorContext: FloorContext = {
      tid: topicId,
      users: data.users,
      attachBase: data.attachBase,
      // 大图查看器是 25 票
      onOpenImage: showNotAvailable,
    };

    return (
      <Animated.View
        style={[styles.body, { transform: [{ translateX: swipe.translateX }] }]}
        {...swipe.panHandlers}
      >
        <FlashList
          ref={listRef}
          data={data.floors}
          keyExtractor={(floor) => String(floor.pid)}
          renderItem={({ item }) => <FloorCard floor={item} context={floorContext} />}
          ListHeaderComponent={
            // 热门回复是服务端在主楼里标的,只有第 1 页拿得到
            data.hotReplies.length === 0 ? null : (
              <HotReplies floors={data.hotReplies} context={floorContext} />
            )
          }
          ListFooterComponent={<View style={styles.footerSpacer} />}
          // 翻页时 isPlaceholderData 为真(屏上还是上一页的内容),那种情况下
          // 不该亮下拉转圈——只有真正在刷新当前这一页时才亮
          refreshing={isFetching && !isPlaceholderData}
          onRefresh={() => void refetch()}
        />
      </Animated.View>
    );
  };

  return (
    <View style={styles.root}>
      <TopBar
        paddingHorizontal={4}
        below={
          <PageBar
            page={page}
            totalPages={totalPages}
            onPick={goToPage}
            onJump={() => setJumpOpen(true)}
          />
        }
      >
        <TopBarButton
          icon="arrow_back"
          size={24}
          onPress={() => router.back()}
          accessibilityLabel="返回"
        />
        <TopBarTitle variant="sub">{title ?? data?.subject ?? `主题 ${tid}`}</TopBarTitle>
        <TopBarButton
          icon="public"
          size={22}
          onPress={() => void WebBrowser.openBrowserAsync(webUrlOf(topicId, page, fav))}
          accessibilityLabel="在浏览器里打开"
          style={topBarSpacer}
        />
        <TopBarButton
          icon="more_vert"
          size={22}
          onPress={() => setMenuOpen(true)}
          accessibilityLabel="更多"
        />
      </TopBar>

      {body()}

      {swipe.hint !== undefined && (
        <View style={styles.swipeHint} pointerEvents="none">
          <Text style={styles.swipeHintText}>{swipe.hint}</Text>
        </View>
      )}

      {fabOpen && (
        <View style={styles.fabMenu}>
          {/* 回帖是 v1 排除项(spec §1),入口保留 */}
          <Pressable
            style={styles.fabItem}
            onPress={() => {
              setFabOpen(false);
              showNotAvailable();
            }}
          >
            <Icon name="reply" size={19} color={theme.colors.primary} />
            <Text style={styles.fabItemLabel}>回复</Text>
          </Pressable>
          <Pressable
            style={styles.fabItem}
            onPress={() => {
              setFabOpen(false);
              void refetch();
            }}
          >
            <Icon name="refresh" size={19} color={theme.colors.primary} />
            <Text style={styles.fabItemLabel}>刷新</Text>
          </Pressable>
        </View>
      )}

      <Pressable
        style={styles.fab}
        onPress={() => setFabOpen((open) => !open)}
        accessibilityLabel={fabOpen ? '收起操作' : '展开操作'}
      >
        <Icon name={fabOpen ? 'close' : 'add'} size={27} color={theme.colors.onFab} />
      </Pressable>

      <InputDialog
        open={jumpOpen}
        title="跳转到页码"
        hint={`共 ${totalPages} 页 · 输入 1 – ${totalPages}`}
        confirmLabel="跳转"
        initialValue={String(page)}
        keyboardType="number-pad"
        onCancel={() => setJumpOpen(false)}
        onConfirm={(value) => {
          setJumpOpen(false);
          // 跳页不夹逼:输了个 999 就该说超范围,而不是默默跳到最后一页
          const target = parseJumpTarget(value, totalPages);
          if (target === undefined) {
            showToast(`请输入 1 – ${totalPages} 之间的页码`);
            return;
          }
          goToPage(target);
        }}
      />

      <OverflowMenu
        open={menuOpen}
        onClose={() => setMenuOpen(false)}
        items={menuItems}
        top={insets.top + 6}
      />

      {/* 多选收藏夹对话框(11 票):传 tid 就能调起,12 票的楼层菜单直接复用同一个组件 */}
      <FavoriteFolderDialog
        open={favorOpen}
        tid={topicId}
        onClose={() => setFavorOpen(false)}
      />
    </View>
  );
}

/** 热门回复区(CONTEXT.md「热门回复」):服务端只在主楼里标,独立成一块。 */
function HotReplies({ floors, context }: { floors: readonly Floor[]; context: FloorContext }) {
  const styles = useStyles();
  const theme = useTheme();
  const [open, setOpen] = useState(false);

  return (
    <View style={styles.hotReplies}>
      <Pressable style={styles.hotHeader} onPress={() => setOpen((value) => !value)}>
        <Icon name="local_fire_department" size={18} color={theme.colors.accent} />
        <Text style={styles.hotTitle}>热门回复({floors.length})</Text>
        <Icon name={open ? 'expand_more' : 'chevron_right'} size={20} color={theme.colors.meta} />
      </Pressable>
      {open && floors.map((floor) => <FloorCard key={floor.pid} floor={floor} context={context} />)}
    </View>
  );
}

interface SwipePagingOptions {
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
}

/**
 * 左右滑动翻页。
 *
 * 用 RN 自带的 PanResponder 而不是 gesture-handler:后者要在根布局套一层
 * `GestureHandlerRootView`,而这里只需要「横向拖一下」这一个手势,不值得为它改根布局。
 * 关键在 `onMoveShouldSetPanResponder`——只有横向位移明显压过纵向时才认领手势,
 * 认领不了的时候列表照常上下滚。
 */
function useSwipePaging({ page, totalPages, onChange }: SwipePagingOptions) {
  const translateX = useRef(new Animated.Value(0)).current;
  const [hint, setHint] = useState<string | undefined>(undefined);
  // PanResponder 的回调建一次就固定住了,拿不到后来的 page/totalPages,用 ref 兜住
  const state = useRef({ page, totalPages, onChange });
  state.current = { page, totalPages, onChange };

  useEffect(() => {
    translateX.setValue(0);
    setHint(undefined);
  }, [page, translateX]);

  const responder = useMemo(
    () =>
      PanResponder.create({
        // 用 capture:responder 的捕获阶段从根往下走,不这么做的话
        // FlashList 里的 ScrollView 会先把手势抢走,横滑就再也认领不到了。
        // 条件卡得很死(横向位移明显压过纵向),所以不会误伤上下滚动。
        // 捕获阶段祖先先手,楼层里横向滚的表格抢不回来,所以它按下时会先打招呼(ui/horizontal-drag)。
        onMoveShouldSetPanResponderCapture: (_event, gesture) =>
          !isHorizontalDragActive() &&
          Math.abs(gesture.dx) >= SWIPE_ACTIVATE &&
          Math.abs(gesture.dx) > Math.abs(gesture.dy) * 1.3,
        onPanResponderMove: (_event, gesture) => {
          const { page: current, totalPages: total } = state.current;
          translateX.setValue(swipeOffset(current, gesture.dx, total));
          setHint(swipeHintText(current, gesture.dx, total));
        },
        onPanResponderRelease: (_event, gesture) => {
          const { page: current, totalPages: total, onChange: change } = state.current;
          setHint(undefined);
          const target = swipeTargetPage(current, gesture.dx, total);

          // 翻页时不弹回:换页会重置 translateX,弹回动画反而多闪一下
          if (target !== current) {
            translateX.setValue(0);
            change(target);
            return;
          }
          Animated.timing(translateX, {
            toValue: 0,
            duration: 220,
            useNativeDriver: true,
          }).start();
        },
        onPanResponderTerminate: () => {
          setHint(undefined);
          Animated.timing(translateX, {
            toValue: 0,
            duration: 220,
            useNativeDriver: true,
          }).start();
        },
      }),
    [translateX],
  );

  return { translateX, hint, panHandlers: responder.panHandlers };
}

/** 「在浏览器里打开」用的网页地址(19 票的网页兜底也会落到同一个 URL)。 */
function webUrlOf(tid: number, page: number, favCode: string | undefined): string {
  const fav = favCode === undefined ? '' : `&fav=${favCode}`;
  return `https://bbs.nga.cn/read.php?tid=${tid}&page=${page}${fav}`;
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
  hotReplies: {
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
    backgroundColor: theme.colors.surface2,
  },
  hotHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.sm,
    paddingVertical: theme.spacing.md,
    paddingHorizontal: theme.spacing.lg,
  },
  hotTitle: {
    ...theme.typography.notice,
    fontWeight: '600',
    color: theme.colors.fg2,
    flex: 1,
  },
  /** 设计稿在列表末尾留 90 给 FAB 让路 */
  footerSpacer: {
    height: 90,
  },
  swipeHint: {
    position: 'absolute',
    alignSelf: 'center',
    top: '50%',
    paddingVertical: 9,
    paddingHorizontal: theme.spacing.lg,
    borderRadius: theme.radius.lg,
    backgroundColor: theme.colors.scrim,
  },
  swipeHintText: {
    ...theme.typography.tab,
    fontWeight: '600',
    color: theme.colors.onPrimary,
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
  fabMenu: {
    position: 'absolute',
    right: 22,
    bottom: 96,
    gap: 10,
    alignItems: 'flex-end',
  },
  fabItem: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 9,
    height: 44,
    paddingHorizontal: theme.spacing.lg,
    borderRadius: theme.radius.lg,
    backgroundColor: theme.colors.menu,
    boxShadow: theme.shadows.elevation2,
  },
  fabItemLabel: {
    ...theme.typography.dialogAction,
    color: theme.colors.fg,
  },
}));

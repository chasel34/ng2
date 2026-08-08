import { FlashList, type FlashListRef, type ViewToken } from '@shopify/flash-list';
import { useLocalSearchParams, useRouter } from 'expo-router';
import * as WebBrowser from 'expo-web-browser';
import { useEffect, useMemo, useRef, useState, type RefObject } from 'react';
import {
  ActivityIndicator,
  Animated,
  Easing,
  PanResponder,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { ATTACH_BASE_FALLBACK, type Floor, type FloorUser, type RecommendAction } from '@/core/api';
import { parseBBCode } from '@/core/bbcode';
import { pageOfFloor } from '@/core/local';
import { currentAccount } from '@/store/accounts';
import { peekHistoryEntry, recordReadFloor, recordTopicVisit } from '@/store/history';
import { useTopicDetail } from '@/store/topic-detail';
import { recommendPidOf, useFloorRecommend } from '@/store/topic-recommend';
import { BBCodeBody } from '@/ui/bbcode';
import { LoadFailed } from '@/ui/error-screen';
import { FavoriteFolderDialog } from '@/ui/favorite-folder-dialog';
import { FloorCard, type FloorContext } from '@/ui/floor-card';
import { isHorizontalDragActive } from '@/ui/horizontal-drag';
import { Icon } from '@/ui/icon';
import { InputDialog } from '@/ui/input-dialog';
import { showLoginPrompt } from '@/ui/login-prompt';
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

  const { tid, title, fav, page: fromPage, pid: fromPid } = useLocalSearchParams<{
    tid: string;
    title?: string;
    fav?: string;
    page?: string;
    pid?: string;
  }>();
  const topicId = Number(tid);

  // 通知(13)点进来时带着对方楼层所在页,直接开在那一页;没带就从第 1 页起
  const [page, setPage] = useState(() => {
    const parsed = Number(fromPage);
    return Number.isInteger(parsed) && parsed > 0 ? parsed : 1;
  });
  /**
   * 只看某一楼(14 的「我的回复」点进来):**服务端不提供 pid → 页码的换算**
   * (实测 `read.php` 带 pid 只会把那一楼单独捞出来,`__PAGE` 恒为 1、`lou` 被重编为 0),
   * 所以落地方式就是 NGA 自己那套「只看该楼」。点提示条上的「看全部」清掉它回到整帖。
   */
  const [onlyPid, setOnlyPid] = useState(() => {
    const parsed = Number(fromPid);
    return Number.isInteger(parsed) && parsed > 0 ? parsed : undefined;
  });
  const [jumpOpen, setJumpOpen] = useState(false);
  const [fabOpen, setFabOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [favorOpen, setFavorOpen] = useState(false);
  // 楼层菜单开在哪一楼(长按或菜单钮,ticket 12);undefined = 关着
  const [menuFloor, setMenuFloor] = useState<Floor | undefined>(undefined);
  // 「查看签名」弹窗:点菜单那一刻就把用户对象定格下来,翻页不影响已开的弹窗
  const [signUser, setSignUser] = useState<FloorUser | undefined>(undefined);
  // 只看此人(CONTEXT.md 术语「只看某人」):服务端 authorid 过滤,翻页天然保持
  const [onlyUser, setOnlyUser] = useState<{ uid: number; name: string } | undefined>(undefined);
  // 退出过滤时回到进入前的那一页
  const pageBeforeFilter = useRef(1);
  const listRef = useRef<FlashListRef<Floor>>(null);

  const { data, error, isPending, isFetching, isPlaceholderData, refetch } = useTopicDetail({
    tid: topicId,
    page,
    ...(fav === undefined ? {} : { favCode: fav }),
    ...(onlyPid === undefined ? {} : { pid: onlyPid }),
    ...(onlyUser === undefined ? {} : { authorId: onlyUser.uid }),
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
  // 只看某一楼时不记进度也不提示续读:屏上只有一楼,`totalRows` 是 1,
  // 照记会把这个主题的历史楼数覆盖成 0(ticket 16);
  // 只看此人期间楼号与总数同样是过滤后的口径,写进阅读进度会串档,一并暂停
  const resume = useReadingProgress({
    topicId,
    fav,
    data: onlyPid === undefined ? data : undefined,
    listRef,
    goToPage,
    paused: onlyUser !== undefined,
  });

  const recommend = useFloorRecommend(topicId);

  /**
   * 赞/踩一层(卡片钮与楼层菜单共用,状态天然同一份)。
   * 未登录先引导登录;乐观更新与失败回滚在 store 层,这里只管吐提示。
   */
  const runRecommend = (floor: Floor, action: RecommendAction, notify: boolean) => {
    if (currentAccount() === null) {
      showLoginPrompt(router, '登录后才能点赞点踩');
      return;
    }
    recommend
      .toggle(recommendPidOf(floor), action)
      .then((outcome) => {
        if (outcome === undefined || !notify) return;
        // 菜单入口照设计稿吐一句;取消也说一声,不然按了没反馈
        showToast(
          outcome.state === 'liked'
            ? '已支持 +1'
            : outcome.state === 'disliked'
              ? '已反对 -1'
              : '已取消',
        );
      })
      .catch((cause: unknown) => {
        showToast(cause instanceof Error ? cause.message : '操作失败,稍后再试');
      });
  };

  /** 进入只看此人。匿名用户没有数字 uid,服务端过滤不了。 */
  const enterOnlyUser = (floor: Floor) => {
    const user = data?.users[floor.authorKey];
    if (user?.uid === undefined) {
      showToast('匿名用户无法只看');
      return;
    }
    pageBeforeFilter.current = page;
    setOnlyUser({ uid: user.uid, name: user.name });
    setPage(1);
    listRef.current?.scrollToOffset({ offset: 0, animated: false });
  };

  /** 退出过滤恢复全楼,回到进入前那一页。 */
  const exitOnlyUser = () => {
    setOnlyUser(undefined);
    setPage(pageBeforeFilter.current);
    listRef.current?.scrollToOffset({ offset: 0, animated: false });
  };

  /**
   * 楼层菜单,条目与顺序照设计稿 `MENUS.floor`(分组空隙在「只看此人」前)。
   * 贴条/举报/屏蔽此人是本版本未开放的占位(spec §1);收藏直接复用 11 票的对话框。
   */
  const floorMenuItems = (): readonly MenuItem[] => {
    if (menuFloor === undefined) return [];
    const pick = (run: () => void) => () => {
      setMenuFloor(undefined);
      run();
    };
    return [
      { key: 'note', label: '贴条', onPress: pick(showNotAvailable) },
      { key: 'like', label: '支持', onPress: pick(() => runRecommend(menuFloor, 'like', true)) },
      {
        key: 'dislike',
        label: '反对',
        onPress: pick(() => runRecommend(menuFloor, 'dislike', true)),
      },
      { key: 'report', label: '举报', onPress: pick(showNotAvailable) },
      {
        key: 'sign',
        label: '查看签名',
        onPress: pick(() => setSignUser(data?.users[menuFloor.authorKey])),
      },
      { key: 'favor', label: '收藏', onPress: pick(() => setFavorOpen(true)) },
      {
        key: 'only-user',
        label: '只看此人',
        gapBefore: true,
        onPress: pick(() => enterOnlyUser(menuFloor)),
      },
      { key: 'block', label: '屏蔽此人', onPress: pick(showNotAvailable) },
    ];
  };

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
    // 反封锁链(ADR-0002)全档跑完还是没拿到数据 → 设计稿的「加载失败」页
    if (error !== null && data === undefined) {
      return (
        <LoadFailed
          error={error}
          onRetry={() => void refetch()}
          onOpenWeb={() => void WebBrowser.openBrowserAsync(webUrlOf(topicId, page, fav))}
          onRelogin={() => router.push('/login')}
        />
      );
    }
    if (data === undefined || data.floors.length === 0) {
      return (
        <View style={styles.center}>
          <Icon name="article" size={40} color={theme.colors.meta} />
          <Text style={styles.errorText}>这一页没有楼层</Text>
          <Pressable style={styles.retry} onPress={() => void refetch()}>
            <Text style={styles.retryLabel}>刷新</Text>
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
      // 赞踩与楼层菜单(ticket 12)。卡片钮不吐 toast,变色计数本身就是反馈
      recommendOf: (floor) => recommend.markOf(recommendPidOf(floor)),
      onRecommend: (floor, action) => runRecommend(floor, action, false),
      onOpenMenu: setMenuFloor,
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
            <>
              {/* 「上次读到第 N 楼」提示条(设计稿 progressTip):跟内容一起滚走。
                  只看此人期间楼号是过滤后的口径,跳过去会落错地方,先藏起来 */}
              {onlyUser === undefined && resume.floor !== undefined && (
                <ResumeBanner floor={resume.floor} onJump={resume.jump} onClose={resume.dismiss} />
              )}
              {/* 只看此人过滤条(设计稿 onlyUser):退出即恢复全楼 */}
              {onlyUser !== undefined && (
                <View style={styles.onlyUserBar}>
                  <Icon name="filter_alt" size={17} color={theme.colors.primary} />
                  <Text style={styles.onlyUserText}>
                    只看 <Text style={styles.onlyUserName}>{onlyUser.name}</Text> 的发言
                  </Text>
                  <Pressable onPress={exitOnlyUser} accessibilityLabel="退出只看此人" hitSlop={8}>
                    <Text style={styles.onlyUserExit}>退出</Text>
                  </Pressable>
                </View>
              )}
              {/* 热门回复是服务端在主楼里标的,只有第 1 页拿得到 */}
              {data.hotReplies.length > 0 && (
                <HotReplies floors={data.hotReplies} context={floorContext} />
              )}
            </>
          }
          ListFooterComponent={<View style={styles.footerSpacer} />}
          // 阅读进度:哪些楼层在屏上由 FlashList 报,记「看到过的最高楼层」(ticket 16)
          viewabilityConfig={resume.viewabilityConfig}
          onViewableItemsChanged={resume.onViewableItemsChanged}
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

      {onlyPid !== undefined && (
        <Pressable style={styles.onlyFloorBar} onPress={() => setOnlyPid(undefined)}>
          <Icon name="filter_alt" size={15} color={theme.colors.primary} />
          <Text style={styles.onlyFloorText}>只看该楼</Text>
          <Text style={styles.onlyFloorAction}>看全部</Text>
        </Pressable>
      )}

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

      {/* 楼层菜单(ticket 12):弹出位置照设计稿 menuTop 的 300 */}
      <OverflowMenu
        open={menuFloor !== undefined}
        onClose={() => setMenuFloor(undefined)}
        items={floorMenuItems()}
        top={insets.top + 300}
      />

      {/* 查看签名弹窗:签名是 BBCode,复用正文渲染器 */}
      <SignatureDialog
        user={signUser}
        attachBase={data?.attachBase ?? ATTACH_BASE_FALLBACK}
        onClose={() => setSignUser(undefined)}
      />

      {/* 多选收藏夹对话框(11 票):顶栏菜单与楼层菜单的「收藏」共用这一个 */}
      <FavoriteFolderDialog
        open={favorOpen}
        tid={topicId}
        onClose={() => setFavorOpen(false)}
      />
    </View>
  );
}

interface ReadingProgressOptions {
  topicId: number;
  fav: string | undefined;
  data: ReturnType<typeof useTopicDetail>['data'];
  listRef: RefObject<FlashListRef<Floor> | null>;
  goToPage: (page: number) => void;
  /** 只看此人期间为 true:那时的楼号/总数是过滤后的口径,不能写进历史 */
  paused: boolean;
}

/**
 * 浏览历史 + 阅读进度(ticket 16,CONTEXT.md「阅读进度」)。
 *
 * 三件事:拿到一页数据就把主题登记进历史(去重、刷新元数据);滚动时把
 * 屏上最高的楼层号报给历史(只前进,不写盘的判断在 core/local/history);
 * 进场时如果有「上次读到第 N 楼」,管提示条的出现、关闭与「回到那里」跳转。
 */
function useReadingProgress({
  topicId,
  fav,
  data,
  listRef,
  goToPage,
  paused,
}: ReadingProgressOptions) {
  // 进场那一刻的存档楼层。之后的滚动会推着进度涨,但提示条要说的是「上次」,
  // 所以只在挂载时读一次;主楼都没读过(lastFloor 0)就不打扰
  const [resumeFloor, setResumeFloor] = useState<number | undefined>(() => {
    const entry = peekHistoryEntry(topicId);
    return entry !== undefined && entry.lastFloor >= 1 ? entry.lastFloor : undefined;
  });
  // 点了「回到那里」之后待兑现的目标楼层:目标页的数据到了才能滚过去
  const [pendingFloor, setPendingFloor] = useState<number | undefined>(undefined);

  // viewability 回调终生不变(FlashList 要求),暂停信号只能从 ref 里透进去
  const pausedRef = useRef(paused);
  pausedRef.current = paused;

  // 浏览即入历史:每拿到新一页就登记一次(同主题只更新时间与楼层,不新增条目)
  useEffect(() => {
    if (paused) return;
    if (data === undefined || !Number.isFinite(topicId) || topicId <= 0) return;
    // 楼主名只有主楼在场的那页(第 1 页)拿得到;缺席时 core 层会保留旧值
    const starter = data.floors.find((floor) => floor.isStarter);
    const author = starter === undefined ? undefined : data.users[starter.authorKey]?.name;
    recordTopicVisit({
      tid: topicId,
      subject: data.subject,
      maxFloor: Math.max(0, data.totalRows - 1),
      ...(author === undefined ? {} : { author }),
      ...(data.boardName === undefined ? {} : { boardName: data.boardName }),
      ...(fav === undefined ? {} : { favCode: fav }),
    });
  }, [data, topicId, fav, paused]);

  // FlashList 要求 viewability 回调终生不变,所以塞进 ref;
  // topicId 是路由参数,这个屏活着期间不会变,闭包捕获是安全的
  const viewability = useRef({
    viewabilityConfig: { itemVisiblePercentThreshold: 20 },
    onViewableItemsChanged: ({ viewableItems }: { viewableItems: ViewToken<Floor>[] }) => {
      if (pausedRef.current) return;
      let maxLou = -1;
      for (const token of viewableItems) {
        if (token.isViewable && token.item.lou > maxLou) maxLou = token.item.lou;
      }
      // 条目还没登记(recordTopicVisit 未跑)或楼层没前进时,store 层是纯 no-op
      if (maxLou >= 0) recordReadFloor(topicId, maxLou);
    },
  }).current;

  const jump = () => {
    if (resumeFloor === undefined || data === undefined) return;
    // 设计稿 jumpToLast:提示条随即消失 + toast「已跳转到第 N 楼」
    setResumeFloor(undefined);
    setPendingFloor(resumeFloor);
    showToast(`已跳转到第 ${resumeFloor} 楼`);
    goToPage(pageOfFloor(resumeFloor, data.rowsPerPage));
  };

  // 目标页的数据到位后滚到那一楼。翻页期间 keepPreviousData 还在展示旧页,
  // 必须核对 data.page,不然会拿旧页的楼层号错滚一通
  useEffect(() => {
    if (pendingFloor === undefined || data === undefined) return;
    if (data.page !== pageOfFloor(pendingFloor, data.rowsPerPage)) return;
    setPendingFloor(undefined);
    // 有楼层被删时 lou 有空洞,目标楼可能不在了:落到它后面最近的一楼
    const index = data.floors.findIndex((floor) => floor.lou >= pendingFloor);
    if (index >= 0) {
      listRef.current?.scrollToIndex({ index, animated: true });
    } else {
      listRef.current?.scrollToEnd({ animated: true });
    }
  }, [pendingFloor, data, listRef]);

  return {
    floor: resumeFloor,
    dismiss: () => setResumeFloor(undefined),
    jump,
    viewabilityConfig: viewability.viewabilityConfig,
    onViewableItemsChanged: viewability.onViewableItemsChanged,
  };
}

/**
 * 「上次读到第 N 楼」提示条。样式与出现动画照设计稿 progressTip:
 * primary-c 底、圆角 12,进场 .28s 上浮淡入(omup);关闭/跳转即消失,无退场动画。
 */
function ResumeBanner({
  floor,
  onJump,
  onClose,
}: {
  floor: number;
  onJump: () => void;
  onClose: () => void;
}) {
  const styles = useStyles();
  const theme = useTheme();
  const progress = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    const animation = Animated.timing(progress, {
      toValue: 1,
      duration: 280,
      // CSS 的 ease
      easing: Easing.bezier(0.25, 0.1, 0.25, 1),
      useNativeDriver: true,
    });
    animation.start();
    return () => animation.stop();
  }, [progress]);

  return (
    <Animated.View
      style={[
        styles.resumeBanner,
        {
          opacity: progress,
          transform: [
            { translateY: progress.interpolate({ inputRange: [0, 1], outputRange: [14, 0] }) },
          ],
        },
      ]}
    >
      <Icon name="bookmark" size={19} color={theme.colors.primary} />
      <Text style={styles.resumeText}>
        上次读到 <Text style={styles.resumeStrong}>第 {floor} 楼</Text>
      </Text>
      <Pressable onPress={onJump} accessibilityLabel={`回到第 ${floor} 楼`}>
        <Text style={styles.resumeAction}>回到那里</Text>
      </Pressable>
      <Pressable onPress={onClose} accessibilityLabel="关闭提示" hitSlop={8}>
        <Icon name="close" size={17} color={theme.colors.meta} />
      </Pressable>
    </Animated.View>
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

/**
 * 「查看签名」弹窗(设计稿 `dialog:'sign'`:标题 + 正文 + 取消/知道了)。
 * 签名是 BBCode(可能带图带折叠),复用正文渲染器;没设置签名给一句占位。
 */
function SignatureDialog({
  user,
  attachBase,
  onClose,
}: {
  user: FloorUser | undefined;
  attachBase: string;
  onClose: () => void;
}) {
  const styles = useStyles();
  const nodes = useMemo(
    () => (user?.signature === undefined ? [] : parseBBCode(user.signature)),
    [user?.signature],
  );
  if (user === undefined) return null;

  return (
    <View style={styles.signRoot}>
      <Pressable style={StyleSheet.absoluteFill} onPress={onClose} accessibilityLabel="关闭弹窗" />
      <View style={styles.signPanel}>
        <Text style={styles.signTitle}>查看签名</Text>
        {/* 签名可以很长(装机单/许愿墙…),超出就在弹窗里滚 */}
        <ScrollView style={styles.signBody} contentContainerStyle={styles.signBodyContent}>
          {nodes.length > 0 ? (
            <BBCodeBody nodes={nodes} options={{ attachBase }} />
          ) : (
            <Text style={styles.signEmpty}>{user.name} 没有设置签名</Text>
          )}
        </ScrollView>
        <View style={styles.signActions}>
          <Pressable style={styles.signCancel} onPress={onClose}>
            <Text style={styles.signCancelLabel}>取消</Text>
          </Pressable>
          <Pressable style={styles.signConfirm} onPress={onClose}>
            <Text style={styles.signConfirmLabel}>知道了</Text>
          </Pressable>
        </View>
      </View>
    </View>
  );
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
  /** 设计稿 progressTip:外距 10 12 2、内距 11 12 11 14、圆角 12、primary-c 底 */
  resumeBanner: {
    marginTop: 10,
    marginHorizontal: theme.spacing.md,
    marginBottom: 2,
    paddingVertical: 11,
    paddingLeft: theme.spacing.row,
    paddingRight: theme.spacing.md,
    borderRadius: theme.radius.md,
    backgroundColor: theme.colors.primaryContainer,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  resumeText: {
    ...theme.typography.resumeTip,
    color: theme.colors.fg,
    flex: 1,
  },
  resumeStrong: {
    fontWeight: '700',
  },
  resumeAction: {
    ...theme.typography.resumeTip,
    fontWeight: '700',
    color: theme.colors.primary,
    paddingVertical: theme.spacing.xs,
    paddingHorizontal: 6,
  },
  /** 设计稿 onlyUser 过滤条:外距 10 12 2、内距 10 14、圆角 12、surface-2 底加 divider 描边 */
  onlyUserBar: {
    marginTop: 10,
    marginHorizontal: theme.spacing.md,
    marginBottom: 2,
    paddingVertical: 10,
    paddingHorizontal: theme.spacing.row,
    borderRadius: theme.radius.md,
    backgroundColor: theme.colors.surface2,
    borderWidth: 1,
    borderColor: theme.colors.divider,
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.sm,
  },
  onlyUserText: {
    ...theme.typography.listMeta,
    color: theme.colors.fg2,
    flex: 1,
  },
  onlyUserName: {
    fontWeight: '700',
    color: theme.colors.fg,
  },
  onlyUserExit: {
    ...theme.typography.listMeta,
    fontWeight: '700',
    color: theme.colors.primary,
  },
  /** 签名弹窗:面板形状与确认/输入对话框同一套(设计稿通用 dialog) */
  signRoot: {
    position: 'absolute',
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
    backgroundColor: theme.colors.scrim,
  },
  signPanel: {
    width: '100%',
    borderRadius: theme.radius.dialog,
    backgroundColor: theme.colors.menu,
    paddingTop: 22,
    paddingHorizontal: 22,
    paddingBottom: theme.spacing.row,
    boxShadow: theme.shadows.elevation2,
  },
  signTitle: {
    ...theme.typography.dialogTitle,
    color: theme.colors.fg,
  },
  signBody: {
    marginTop: 9,
    maxHeight: 340,
  },
  signBodyContent: {
    paddingBottom: theme.spacing.xs,
  },
  signEmpty: {
    ...theme.typography.notice,
    color: theme.colors.meta,
  },
  signActions: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 6,
    marginTop: theme.spacing.row,
  },
  signCancel: {
    height: 40,
    paddingHorizontal: theme.spacing.lg,
    borderRadius: theme.radius.full,
    alignItems: 'center',
    justifyContent: 'center',
  },
  signCancelLabel: {
    ...theme.typography.dialogAction,
    color: theme.colors.fg2,
  },
  signConfirm: {
    height: 40,
    paddingHorizontal: theme.spacing.xl,
    borderRadius: theme.radius.full,
    backgroundColor: theme.colors.primary,
    alignItems: 'center',
    justifyContent: 'center',
  },
  signConfirmLabel: {
    ...theme.typography.dialogAction,
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
  onlyFloorBar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingVertical: 9,
    paddingHorizontal: theme.spacing.lg,
    backgroundColor: theme.colors.primaryContainer,
  },
  onlyFloorText: {
    ...theme.typography.listMeta,
    color: theme.colors.primary,
    flex: 1,
  },
  onlyFloorAction: {
    ...theme.typography.listMeta,
    fontWeight: '600',
    color: theme.colors.primary,
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

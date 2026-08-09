import { FlashList } from '@shopify/flash-list';
import { useQueryClient } from '@tanstack/react-query';
import { Image } from 'expo-image';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { memo, useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';

import { fetchTopicDetail, type Floor, type FloorUser, type TopicDetail } from '@/core/api';
import { parseBBCode } from '@/core/bbcode';
import {
  buildQuoteIndex,
  buildReplyChain,
  pageOfFloor,
  resolveDice,
  stripQuoteMarkup,
  type ChainNode,
} from '@/core/local';
import { fetchNga } from '@/store/nga-client';
import { saveCachedPage } from '@/store/topic-cache';
import { loadedTopicPages, topicDetailQueryKey } from '@/store/topic-detail';
import { avatarColorFor } from '@/ui/avatar';
import { BBCodeBody } from '@/ui/bbcode';
import { Icon } from '@/ui/icon';
import { initialOf } from '@/ui/initial';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { showNotAvailable } from '@/ui/toast';
import { TopBar, TopBarButton, TopBarTitle, topBarSpacer } from '@/ui/top-bar';

/** 设计稿 isChain:每往链的下游走一张卡,左缩进加 14。 */
const INDENT_STEP = 14;

/** 缩进封顶。设计稿只画了 4 层;长链一路缩下去卡片会被挤没,到这一档就并排。 */
const MAX_INDENT_STEPS = 8;

/** 一楼连同「它是从哪一页来的」——附件基址与每页楼数都是页级属性。 */
interface LoadedFloor {
  readonly floor: Floor;
  readonly attachBase: string;
  readonly rowsPerPage: number;
}

/**
 * 回复链页(CONTEXT.md「回复链」,ticket 26;设计稿 isChain 屏)。
 *
 * 从详情页某楼的引用块进来:`tid` + `pid`(展开起点)+ 可选 `fav`。
 * 已加载楼层直接从 Query 缓存搬(详情页翻过的页都在);链上引用了
 * 还没加载的楼时,按引用标记里的页码把那一页懒加载回来——加载失败
 * 或定位不到的节点降级成占位卡,不阻塞整条链。
 */
export default function ChainScreen() {
  const styles = useStyles();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { tid, pid, fav } = useLocalSearchParams<{ tid: string; pid: string; fav?: string }>();
  const topicId = Number(tid);
  const startPid = Number(pid);

  // 已加载页。进场时从 Query 缓存搬一份,懒加载的页往里补
  const [pages, setPages] = useState<ReadonlyMap<number, TopicDetail>>(
    () => new Map(loadedTopicPages(queryClient, topicId, fav).map((detail) => [detail.page, detail])),
  );
  const [failedPages, setFailedPages] = useState<ReadonlySet<number>>(() => new Set());
  const [loadingPage, setLoadingPage] = useState<number | undefined>(undefined);

  // 全部已加载楼层(含热门回复)合成一张表,quote 索引按它建。
  // 匿名用户的 key 带请求级前缀(API 文档 §3),跨页合并用户表不会串号
  const merged = useMemo(() => {
    const users: Record<string, FloorUser> = {};
    const byPid = new Map<number, LoadedFloor>();
    for (const detail of [...pages.values()].sort((a, b) => a.page - b.page)) {
      Object.assign(users, detail.users);
      for (const floor of [...detail.floors, ...detail.hotReplies]) {
        if (byPid.has(floor.pid)) continue;
        byPid.set(floor.pid, {
          floor,
          attachBase: detail.attachBase,
          rowsPerPage: detail.rowsPerPage,
        });
      }
    }
    const index = buildQuoteIndex(
      [...byPid.values()].map((entry) => entry.floor),
      { tid: topicId },
    );
    return { users, byPid, index };
  }, [pages, topicId]);

  const chain = useMemo(() => buildReplyChain(merged.index, startPid), [merged.index, startPid]);

  // 懒加载:链上第一个「未加载但带页码」的节点,把那一页拉回来;
  // 页到位 → 索引重建 → 链自己长长,直到没有可补的节点
  const wantedPage = chain.find(
    (node) =>
      !node.loaded &&
      node.ref?.page !== undefined &&
      !pages.has(node.ref.page) &&
      !failedPages.has(node.ref.page),
  )?.ref?.page;

  useEffect(() => {
    if (wantedPage === undefined || loadingPage !== undefined) return;
    if (!Number.isFinite(topicId) || topicId <= 0) return;
    setLoadingPage(wantedPage);
    const params = {
      tid: topicId,
      page: wantedPage,
      ...(fav === undefined ? {} : { favCode: fav }),
    };
    queryClient
      .fetchQuery({
        queryKey: topicDetailQueryKey(params),
        queryFn: ({ signal }) =>
          fetchTopicDetail(fetchNga, { ...params, signal, onSnapshot: saveCachedPage }),
        // 详情页刚看过的页直接用缓存,不再打一次 read.php(ADR-0002 的封号风险)
        staleTime: Infinity,
      })
      // 按请求的页码登记而不是响应的 __PAGE:超范围的页码服务端会钳到末页,
      // 按响应登记的话这个页码永远补不上,懒加载会原地打转
      .then((detail) => setPages((prev) => new Map(prev).set(wantedPage, detail)))
      .catch(() => setFailedPages((prev) => new Set(prev).add(wantedPage)))
      .finally(() => setLoadingPage(undefined));
  }, [wantedPage, loadingPage, topicId, fav, queryClient]);

  /** 「在原帖中查看」:回详情页那一页并定位那一楼(16 票的跳楼机制,详情页收 floor 参数)。 */
  const openInTopic = (node: ChainNode) => {
    const entry = merged.byPid.get(node.pid);
    const target =
      entry !== undefined
        ? { page: pageOfFloor(entry.floor.lou, entry.rowsPerPage), floor: entry.floor.lou }
        : node.ref?.page !== undefined
          ? { page: node.ref.page }
          : undefined;
    if (target === undefined) return;
    router.push({
      pathname: '/topic/[tid]',
      params: {
        tid: String(topicId),
        page: String(target.page),
        ...(target.floor === undefined ? {} : { floor: String(target.floor) }),
        ...(fav === undefined ? {} : { fav }),
      },
    });
  };

  const currentLou = merged.byPid.get(startPid)?.floor.lou;

  return (
    <View style={styles.root}>
      <TopBar paddingHorizontal={4}>
        <TopBarButton
          icon="arrow_back"
          box={46}
          size={24}
          onPress={() => router.back()}
          accessibilityLabel="返回"
        />
        <TopBarTitle variant="sub">回复链 · {chain.length} 层</TopBarTitle>
        {/* 回帖是 v1 排除项(spec §1),设计稿这个入口保留 */}
        <TopBarButton
          icon="reply"
          size={22}
          onPress={showNotAvailable}
          accessibilityLabel="回复"
          style={topBarSpacer}
        />
      </TopBar>
      <FlashList
        data={chain}
        keyExtractor={(node) => String(node.pid)}
        extraData={{ loadingPage, failedPages }}
        contentContainerStyle={styles.listContent}
        ListHeaderComponent={
          currentLou === undefined ? null : (
            <Text style={styles.intro}>
              从第 {currentLou} 楼展开:上游是它引用的楼层,下游是引用它的楼层。
            </Text>
          )
        }
        renderItem={({ item, index }) => {
          const entry = merged.byPid.get(item.pid);
          const indent = Math.min(index, MAX_INDENT_STEPS) * INDENT_STEP;
          if (entry === undefined) {
            return (
              <MissingCard
                node={item}
                indent={indent}
                loading={
                  item.ref?.page !== undefined &&
                  !failedPages.has(item.ref.page) &&
                  !pages.has(item.ref.page)
                }
                pageLoaded={item.ref?.page !== undefined && pages.has(item.ref.page)}
                onRetry={() => {
                  const page = item.ref?.page;
                  if (page === undefined) return;
                  setFailedPages((prev) => {
                    const next = new Set(prev);
                    next.delete(page);
                    return next;
                  });
                }}
                onOpenInTopic={item.ref?.page === undefined ? undefined : () => openInTopic(item)}
              />
            );
          }
          return (
            <ChainCard
              entry={entry}
              current={item.role === 'current'}
              indent={indent}
              tid={topicId}
              user={merged.users[entry.floor.authorKey]}
              onOpenInTopic={() => openInTopic(item)}
            />
          );
        }}
      />
    </View>
  );
}

/**
 * 链上一张已加载的卡(设计稿 chainCards):缩进、当前楼主题色描边 + 阴影 +
 * 「当前楼层」徽标;正文剥掉引用容器——上一层就画在这张卡上面,不必重复。
 */
const ChainCard = memo(function ChainCard({
  entry,
  current,
  indent,
  tid,
  user,
  onOpenInTopic,
}: {
  entry: LoadedFloor;
  current: boolean;
  indent: number;
  tid: number;
  user: FloorUser | undefined;
  onOpenInTopic: () => void;
}) {
  const styles = useStyles();
  const theme = useTheme();
  const { floor } = entry;
  const nodes = useMemo(() => stripQuoteMarkup(parseBBCode(floor.content)), [floor.content]);
  const dice = useMemo(
    () => resolveDice(nodes, { authorId: floor.authorId, tid, pid: floor.pid }),
    [nodes, floor.authorId, tid, floor.pid],
  );

  const name = user?.name ?? '未知用户';
  return (
    <View style={[styles.card, current && styles.cardCurrent, { marginLeft: indent }]}>
      <View style={styles.cardHeader}>
        <ChainAvatar name={name} avatarKey={user?.key ?? name} avatarUrl={user?.avatarUrl} />
        <Text style={styles.cardName} numberOfLines={1}>
          {name}
        </Text>
        <Text style={styles.cardMeta}>
          [{floor.lou} 楼] {floor.postedAtText}
        </Text>
      </View>
      <View style={styles.cardBody}>
        <BBCodeBody
          nodes={nodes}
          options={{
            attachBase: entry.attachBase,
            postedAt: floor.postedAt,
            dice,
            bodyFontSize: theme.typography.chainBody.fontSize,
            bodyLineHeight: theme.typography.chainBody.lineHeight,
          }}
          style={styles.cardBodyText}
        />
      </View>
      <View style={styles.cardFooter}>
        {current && <Text style={styles.badge}>当前楼层</Text>}
        <View style={styles.likes}>
          <Icon name="thumb_up" size={14} color={theme.colors.meta} />
          <Text style={styles.likesCount}>{floor.score}</Text>
        </View>
        <Pressable onPress={onOpenInTopic} hitSlop={8} accessibilityLabel="在原帖中查看">
          <Text style={styles.goFloor}>在原帖中查看</Text>
        </Pressable>
      </View>
    </View>
  );
});

/** 设计稿链卡头像:30 见方、圆角 10、无图时纯色底 + 名字首字(12/700)。 */
function ChainAvatar({
  name,
  avatarKey,
  avatarUrl,
}: {
  name: string;
  avatarKey: string;
  avatarUrl: string | undefined;
}) {
  const styles = useStyles();
  const [failed, setFailed] = useState(false);

  if (avatarUrl === undefined || failed) {
    return (
      <View style={[styles.avatar, { backgroundColor: avatarColorFor(avatarKey) }]}>
        <Text style={styles.avatarInitial} allowFontScaling={false}>
          {initialOf(name)}
        </Text>
      </View>
    );
  }
  return (
    <Image
      source={{ uri: avatarUrl }}
      style={styles.avatar}
      contentFit="cover"
      cachePolicy="disk"
      transition={120}
      recyclingKey={avatarKey}
      onError={() => setFailed(true)}
      accessibilityIgnoresInvertColors
    />
  );
}

/**
 * 链上一个没加载出来的节点的降级占位(票面要求:不阻塞整链)。
 * 三种情况:那一页还在拉(转圈)、拉失败(给「重试」)、
 * 拉回来了但里面没有这一楼 / 引用里根本没有页码(只能说明情况)。
 */
function MissingCard({
  node,
  indent,
  loading,
  pageLoaded,
  onRetry,
  onOpenInTopic,
}: {
  node: ChainNode;
  indent: number;
  loading: boolean;
  pageLoaded: boolean;
  onRetry: () => void;
  onOpenInTopic: (() => void) | undefined;
}) {
  const styles = useStyles();
  const theme = useTheme();

  return (
    <View style={[styles.card, { marginLeft: indent }]}>
      <View style={styles.missingRow}>
        {loading ? (
          <ActivityIndicator size="small" color={theme.colors.primary} />
        ) : (
          <Icon name="cloud_off" size={16} color={theme.colors.meta} />
        )}
        <Text style={styles.missingText}>
          {loading
            ? '正在加载这一楼…'
            : pageLoaded
              ? '这一楼没能加载:目标页里找不到它,可能已被删除'
              : node.ref?.page === undefined
                ? '这一楼没能加载:引用里没有页码,定位不到'
                : '这一楼没能加载'}
        </Text>
        {!loading && !pageLoaded && node.ref?.page !== undefined && (
          <Pressable onPress={onRetry} hitSlop={8} accessibilityLabel="重试加载这一楼">
            <Text style={styles.goFloor}>重试</Text>
          </Pressable>
        )}
      </View>
      {onOpenInTopic !== undefined && (
        <View style={styles.cardFooter}>
          <Pressable
            onPress={onOpenInTopic}
            hitSlop={8}
            accessibilityLabel="在原帖中查看"
            style={styles.missingGoFloor}
          >
            <Text style={styles.goFloor}>在原帖中查看</Text>
          </Pressable>
        </View>
      )}
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  root: {
    flex: 1,
    backgroundColor: theme.colors.bg,
  },
  /** 设计稿 isChain 滚动区:内距 14 12 24 */
  listContent: {
    paddingTop: theme.spacing.row,
    paddingHorizontal: theme.spacing.md,
    paddingBottom: 24,
  },
  /** 设计稿:说明行 12 号 meta 色,内距 0 4 12 */
  intro: {
    ...theme.typography.cardMeta,
    color: theme.colors.meta,
    paddingHorizontal: theme.spacing.xs,
    paddingBottom: theme.spacing.md,
  },
  /** 设计稿链卡:下距 10、内距 13 14、圆角 14、divider 描边 1.5、底色同页面 */
  card: {
    marginBottom: 10,
    paddingVertical: 13,
    paddingHorizontal: theme.spacing.row,
    borderRadius: theme.radius.lg,
    borderWidth: 1.5,
    borderColor: theme.colors.divider,
    backgroundColor: theme.colors.bg,
  },
  /** 当前楼:surface 底、主题色描边、卡片阴影 */
  cardCurrent: {
    borderColor: theme.colors.primary,
    backgroundColor: theme.colors.surface,
    boxShadow: theme.shadows.elevation1,
  },
  cardHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 9,
  },
  avatar: {
    width: 30,
    height: 30,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarInitial: {
    ...theme.typography.initial,
    color: theme.colors.onPrimary,
  },
  cardName: {
    ...theme.typography.chainName,
    color: theme.colors.primary,
    flexShrink: 1,
  },
  /** 设计稿:右侧 [N 楼] 时间,11 号 meta 色 */
  cardMeta: {
    ...theme.typography.notifyMeta,
    color: theme.colors.meta,
    marginLeft: 'auto',
  },
  cardBody: {
    marginTop: 9,
  },
  cardBodyText: {
    ...theme.typography.chainBody,
    color: theme.colors.fg,
  },
  /** 设计稿:底行 gap 8、上距 9、11.5 号 meta 色 */
  cardFooter: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.sm,
    marginTop: 9,
  },
  /** 「当前楼层」徽标:2 8 内距、圆角 6、primary-c 底、主题色 700 */
  badge: {
    ...theme.typography.meta,
    fontWeight: '700',
    color: theme.colors.primary,
    backgroundColor: theme.colors.primaryContainer,
    paddingVertical: 2,
    paddingHorizontal: theme.spacing.sm,
    borderRadius: 6,
    overflow: 'hidden',
  },
  likes: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.xs,
    marginLeft: 'auto',
  },
  likesCount: {
    ...theme.typography.meta,
    color: theme.colors.meta,
  },
  goFloor: {
    ...theme.typography.meta,
    fontWeight: '600',
    color: theme.colors.primary,
  },
  missingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.sm,
  },
  missingText: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
    flex: 1,
  },
  missingGoFloor: {
    marginLeft: 'auto',
  },
}));

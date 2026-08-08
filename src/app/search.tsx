import { FlashList } from '@shopify/flash-list';
import { Image } from 'expo-image';
import { useLocalSearchParams, useRouter } from 'expo-router';
import * as WebBrowser from 'expo-web-browser';
import { useMemo, useState } from 'react';
import {
  ActivityIndicator,
  Keyboard,
  Pressable,
  ScrollView,
  Text,
  TextInput,
  View,
} from 'react-native';

import { mergeTopicPages, type Board, type BoardSearchItem, type Topic } from '@/core/api';
import { useAccounts } from '@/store/accounts';
import { useBoardFavoriteMutations, useIsBoardFavored } from '@/store/board-favor';
import {
  useBoardSearch,
  useSearchHistory,
  useTopicSearch,
  useUserSearch,
  type SearchBoardScope,
  type SearchHistoryEntry,
  type SearchTab,
} from '@/store/search';
import { avatarColorFor } from '@/ui/avatar';
import { BoardIcon } from '@/ui/board-icon';
import { Icon, type IconName } from '@/ui/icon';
import { initialOf } from '@/ui/initial';
import { showLoginPrompt } from '@/ui/login-prompt';
import { showSnackbar } from '@/ui/snackbar';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { TopBar } from '@/ui/top-bar';
import { TopicRow } from '@/ui/topic-row';

/** 设计稿 isSearch 屏:tab 48 高;输入框 40 高、圆角 6。 */
const TAB_HEIGHT = 48;
const INPUT_HEIGHT = 40;
const INPUT_RADIUS = 6;

const TABS: readonly { key: SearchTab; label: string }[] = [
  // 「搜板块」沿用设计稿原字(CONTEXT.md「版块」:UI 文案可沿用设计稿)
  { key: 'topics', label: '搜主题' },
  { key: 'boards', label: '搜板块' },
  { key: 'users', label: '搜用户' },
];

const PLACEHOLDER: Record<SearchTab, string> = {
  topics: '搜索主题',
  boards: '搜索版块',
  users: '输入 UID 或用户名',
};

/**
 * 搜索页(设计稿 isSearch 屏 1:1;三种结果列表是设计稿缺失页面,按现有设计语言延伸)。
 *
 * 从列表页进来带 `boardId`/`kind`/`boardName`,搜索选项里才有「当前板块」;
 * 首页进来只有「全部板块」。三个 tab 各自独立的搜索历史(带范围,持久化)。
 */
export default function SearchScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();

  const params = useLocalSearchParams<{ boardId?: string; kind?: string; boardName?: string }>();
  const currentBoard: SearchBoardScope | undefined =
    params.boardId === undefined
      ? undefined
      : {
          boardId: Number(params.boardId),
          kind: params.kind === 'collection' ? 'collection' : 'board',
          name: params.boardName ?? `版块 ${params.boardId}`,
        };

  const [tab, setTab] = useState<SearchTab>('topics');
  const [text, setText] = useState('');
  /** 已提交的关键词;空串 = 还没搜,显示选项 + 历史 */
  const [submitted, setSubmitted] = useState('');
  // 从列表页进来默认搜当前板块(设计稿的「当前板块」默认选中);undefined = 全部板块
  const [scope, setScope] = useState<SearchBoardScope | undefined>(currentBoard);
  const [content, setContent] = useState(false);

  const history = useSearchHistory((state) => state.byTab[tab]);
  const addHistory = useSearchHistory((state) => state.add);
  const removeHistory = useSearchHistory((state) => state.remove);
  const clearHistory = useSearchHistory((state) => state.clear);

  const submit = (raw: string, scopeArg = scope, contentArg = content) => {
    const query = raw.trim();
    if (query === '') return;
    setText(query);
    setScope(scopeArg);
    setContent(contentArg);
    setSubmitted(query);
    addHistory(tab, {
      query,
      ...(tab === 'topics' && scopeArg !== undefined ? { scope: scopeArg } : {}),
      ...(tab === 'topics' && contentArg ? { content: true } : {}),
    });
    Keyboard.dismiss();
  };

  const clearInput = () => {
    setText('');
    setSubmitted('');
  };

  /** 历史条目右侧的范围标注(设计稿 h.scope 那一格)。 */
  const historyScopeLabel = (entry: SearchHistoryEntry): string => {
    if (tab === 'boards') return '版块';
    if (tab === 'users') return '用户';
    const parts = [entry.scope?.name ?? '全部板块'];
    if (entry.content === true) parts.push('包括正文');
    return parts.join(' · ');
  };

  /** 搜索选项(设计稿 searchOpts):单选二枚 + 勾选一枚。只对搜主题生效。 */
  const options: readonly {
    key: string;
    label: string;
    icon: IconName;
    on: boolean;
    pick: () => void;
  }[] = [
    ...(currentBoard === undefined
      ? []
      : [
          {
            key: 'current',
            label: '当前板块',
            icon: (scope?.boardId === currentBoard.boardId
              ? 'radio_button_checked'
              : 'radio_button_unchecked') as IconName,
            on: scope?.boardId === currentBoard.boardId,
            pick: () => setScope(currentBoard),
          },
        ]),
    {
      key: 'all',
      label: '全部板块',
      icon: (scope === undefined
        ? 'radio_button_checked'
        : 'radio_button_unchecked') as IconName,
      on: scope === undefined,
      pick: () => setScope(undefined),
    },
    {
      key: 'content',
      label: '包括正文',
      icon: (content ? 'check_box' : 'check_box_outline_blank') as IconName,
      on: content,
      pick: () => setContent(!content),
    },
  ];

  /** 选项 + 各 tab 独立的搜索历史(还没提交关键词时的正文)。 */
  const renderHome = () => (
    <ScrollView keyboardShouldPersistTaps="handled">
      {tab === 'topics' && (
        <View>
          <Text style={styles.sectionTitle}>搜索选项</Text>
          <View style={styles.optionRow}>
            {options.map((option) => (
              <Pressable key={option.key} style={styles.option} onPress={option.pick} hitSlop={4}>
                <Icon
                  name={option.icon}
                  size={23}
                  color={option.on ? theme.colors.primary : theme.colors.fg2}
                />
                <Text style={styles.optionLabel}>{option.label}</Text>
              </Pressable>
            ))}
          </View>
          <View style={styles.divider} />
        </View>
      )}

      <View style={styles.historyHeader}>
        <Text style={[styles.sectionTitle, styles.historyTitle]}>搜索历史</Text>
        {history.length > 0 && (
          <Pressable
            onPress={() => {
              clearHistory(tab);
              showSnackbar('已清空搜索历史');
            }}
            hitSlop={10}
          >
            <Text style={styles.clearLabel}>清空</Text>
          </Pressable>
        )}
      </View>
      {history.length === 0 && <Text style={styles.historyEmpty}>还没有搜索记录</Text>}
      {history.map((entry, index) => (
        <Pressable
          key={`${entry.query}/${entry.scope?.boardId ?? 'all'}/${entry.content === true}`}
          style={styles.historyRow}
          android_ripple={{ color: theme.colors.divider }}
          onPress={() => submit(entry.query, entry.scope, entry.content === true)}
        >
          <Icon name="history" size={19} color={theme.colors.meta} />
          <Text style={styles.historyQuery} numberOfLines={1}>
            {entry.query}
          </Text>
          <Text style={styles.historyScope}>{historyScopeLabel(entry)}</Text>
          <Pressable
            onPress={() => removeHistory(tab, index)}
            hitSlop={10}
            accessibilityLabel={`删除搜索历史「${entry.query}」`}
          >
            <Icon name="close" size={18} color={theme.colors.meta} />
          </Pressable>
        </Pressable>
      ))}
      <View style={styles.bottomSpacer} />
    </ScrollView>
  );

  return (
    <View style={styles.root}>
      <TopBar paddingHorizontal={4}>
        <Pressable
          onPress={() => router.back()}
          accessibilityLabel="返回"
          style={styles.backButton}
          android_ripple={{ color: theme.colors.onTopbar, borderless: true, radius: 23 }}
        >
          <Icon name="arrow_back" size={24} color={theme.colors.onTopbar} />
        </Pressable>
        <View style={styles.inputBox}>
          <TextInput
            style={styles.input}
            value={text}
            onChangeText={setText}
            placeholder={PLACEHOLDER[tab]}
            placeholderTextColor={theme.colors.meta}
            returnKeyType="search"
            onSubmitEditing={() => submit(text)}
            autoFocus
            autoCorrect={false}
          />
          {text !== '' && (
            <Pressable onPress={clearInput} hitSlop={10} accessibilityLabel="清空关键词">
              <Icon name="close" size={18} color={theme.colors.meta} />
            </Pressable>
          )}
        </View>
      </TopBar>

      <View style={styles.tabRow}>
        {TABS.map((item) => (
          <Pressable
            key={item.key}
            style={styles.tab}
            onPress={() => setTab(item.key)}
            accessibilityLabel={item.label}
          >
            <Text style={[styles.tabLabel, tab !== item.key && styles.tabLabelInactive]}>
              {item.label}
            </Text>
            {tab === item.key && <View style={styles.tabIndicator} />}
          </Pressable>
        ))}
      </View>

      {submitted === '' ? (
        renderHome()
      ) : tab === 'topics' ? (
        <TopicResults query={submitted} scope={scope} content={content} />
      ) : tab === 'boards' ? (
        <BoardResults query={submitted} />
      ) : (
        <UserResult query={submitted} />
      )}
    </View>
  );
}

/** 主题结果:thread.php 无限滚动,复用主题列表行(设计稿 isList 的两行布局)。 */
function TopicResults({
  query,
  scope,
  content,
}: {
  query: string;
  scope: SearchBoardScope | undefined;
  content: boolean;
}) {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();

  const { data, error, isPending, isFetchingNextPage, hasNextPage, fetchNextPage, refetch } =
    useTopicSearch({ key: query, ...(scope === undefined ? {} : { scope }), content });

  // 搜索结果按说不重复,但翻页期间置顶/镜像行的坑与版块列表同源,照样按 tid 去重
  const topics = useMemo(() => mergeTopicPages(data?.pages ?? []), [data?.pages]);
  const totalRows = data?.pages[0]?.totalRows ?? 0;
  const loadedPages = data?.pages.length ?? 0;

  const openBoard = (board: Board) => {
    router.push({
      pathname: '/board/[id]',
      params: { id: String(board.id), name: board.name, kind: board.kind },
    });
  };

  const openTopic = (topic: Topic) => {
    // 合集 / 版块镜像行点开的是另一个版块的列表(API 文档 §2 解析要点 3)
    if (topic.shortcut !== undefined) {
      openBoard({ id: topic.shortcut.id, kind: topic.shortcut.kind, name: topic.subject });
      return;
    }
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

  if (isPending) return <Loading />;
  if (topics.length === 0) {
    return (
      <EmptyState
        error={error}
        emptyIcon="search"
        emptyText={`没有找到与「${query}」相关的主题`}
        onRetry={() => void refetch()}
      />
    );
  }

  return (
    <View style={styles.body}>
      {/* 结果统计条:设计稿缺失页面,按二级列表的副标题条(listSub)延伸 */}
      <Text style={styles.resultSub}>
        {scope === undefined ? '全部板块' : scope.name}
        {content ? ' · 包括正文' : ''} · 约 {totalRows} 条结果
      </Text>
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
            {!hasNextPage && <Text style={styles.footerText}>没有更多了</Text>}
            <View style={styles.bottomSpacer} />
          </View>
        }
        onEndReachedThreshold={0.6}
        onEndReached={() => {
          if (hasNextPage && !isFetchingNextPage) void fetchNextPage();
        }}
      />
    </View>
  );
}

/** 版块结果:可进入、可收藏(设计稿缺失页面,行样式按首页宫格图标 + 列表行延伸)。 */
function BoardResults({ query }: { query: string }) {
  const styles = useStyles();
  const { data, error, isPending, refetch } = useBoardSearch(query);

  if (isPending) return <Loading />;
  if (data === undefined || data.length === 0) {
    return (
      <EmptyState
        error={error}
        emptyIcon="search"
        emptyText={`没有找到与「${query}」相关的版块`}
        onRetry={() => void refetch()}
      />
    );
  }

  return (
    <View style={styles.body}>
      <Text style={styles.resultSub}>找到 {data.length} 个版块</Text>
      <FlashList
        data={data}
        keyExtractor={(item) => `${item.board.kind}/${item.board.id}`}
        renderItem={({ item }) => <BoardResultRow item={item} />}
        ListFooterComponent={<View style={styles.bottomSpacer} />}
      />
    </View>
  );
}

function BoardResultRow({ item }: { item: BoardSearchItem }) {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();

  const { board } = item;
  const signedIn = useAccounts((state) => state.currentUid) !== null;
  const favored = useIsBoardFavored(board.id);
  const { add, remove } = useBoardFavoriteMutations();

  /** 与列表页顶栏星标同一套话术:点了立刻变,失败按服务端的话说。 */
  const toggleFavorite = () => {
    if (!signedIn) {
      showLoginPrompt(router, '登录后可把版块收藏到云端');
      return;
    }
    const run = (favor: boolean) =>
      (favor ? add(board) : remove(board)).then(
        () =>
          showSnackbar(favor ? '已收藏到「我的收藏」' : '已取消收藏该版面', {
            label: '撤销',
            onPress: () => run(!favor),
          }),
        (error: unknown) =>
          showSnackbar(error instanceof Error ? error.message : '收藏没能同步到云端'),
      );
    void run(!favored);
  };

  const meta = [item.parentName, board.info].filter((part) => part !== undefined).join(' · ');

  return (
    <Pressable
      style={styles.boardRow}
      android_ripple={{ color: theme.colors.divider }}
      onPress={() =>
        router.push({
          pathname: '/board/[id]',
          params: { id: String(board.id), name: board.name, kind: board.kind },
        })
      }
    >
      <BoardIcon board={board} />
      <View style={styles.boardText}>
        <Text style={styles.boardName} numberOfLines={1}>
          {board.name}
        </Text>
        {meta !== '' && (
          <Text style={styles.boardMeta} numberOfLines={1}>
            {meta}
          </Text>
        )}
      </View>
      <Pressable
        onPress={toggleFavorite}
        style={styles.boardStar}
        hitSlop={6}
        accessibilityLabel={favored ? `取消收藏${board.name}` : `收藏${board.name}`}
      >
        <Icon name="star" size={22} color={favored ? theme.colors.accent : theme.colors.meta} />
      </Pressable>
    </Pressable>
  );
}

/** 用户结果:一条资料卡,点击进资料页(设计稿缺失页面,按通知条目的头像行延伸)。 */
function UserResult({ query }: { query: string }) {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();

  const { data, error, isPending, refetch } = useUserSearch(query);
  const [avatarFailed, setAvatarFailed] = useState(false);

  if (isPending) return <Loading />;
  if (data === undefined) {
    return (
      <EmptyState
        error={error}
        emptyIcon="person"
        emptyText={`没有找到用户「${query}」`}
        onRetry={() => void refetch()}
      />
    );
  }

  const meta = [
    `UID ${data.uid}`,
    ...(data.group === undefined ? [] : [data.group]),
    `发帖 ${data.postCount}`,
  ].join(' · ');

  return (
    <View style={styles.body}>
      <Pressable
        style={styles.userRow}
        android_ripple={{ color: theme.colors.divider }}
        onPress={() =>
          router.push({
            pathname: '/user/[uid]',
            params: { uid: String(data.uid), name: data.name },
          })
        }
      >
        {data.avatarUrl === undefined || avatarFailed ? (
          // 头像占位与楼层头像同款:纯色圆底 + 首字(avatar.tsx)
          <View
            style={[styles.userAvatar, { backgroundColor: avatarColorFor(String(data.uid)) }]}
          >
            <Text style={styles.userInitial} allowFontScaling={false}>
              {initialOf(data.name)}
            </Text>
          </View>
        ) : (
          <Image
            source={{ uri: data.avatarUrl }}
            style={styles.userAvatar}
            onError={() => setAvatarFailed(true)}
          />
        )}
        <View style={styles.boardText}>
          <Text style={styles.boardName} numberOfLines={1}>
            {data.name}
          </Text>
          <Text style={styles.boardMeta} numberOfLines={1}>
            {meta}
          </Text>
        </View>
        <Icon name="chevron_right" size={20} color={theme.colors.meta} />
      </Pressable>
    </View>
  );
}

function Loading() {
  const styles = useStyles();
  const theme = useTheme();
  return (
    <View style={styles.center}>
      <ActivityIndicator color={theme.colors.primary} />
    </View>
  );
}

/** 空结果与拉取失败分开说(与主题列表页同一套话术)。 */
function EmptyState({
  error,
  emptyIcon,
  emptyText,
  onRetry,
}: {
  error: unknown;
  emptyIcon: IconName;
  emptyText: string;
  onRetry: () => void;
}) {
  const styles = useStyles();
  const theme = useTheme();
  const failed = error !== null && error !== undefined;
  return (
    <View style={styles.center}>
      <Icon name={failed ? 'cloud_off' : emptyIcon} size={40} color={theme.colors.meta} />
      <Text style={styles.errorText}>
        {failed ? (error instanceof Error ? error.message : '搜索结果拉不下来') : emptyText}
      </Text>
      {failed && (
        <Pressable style={styles.retry} onPress={onRetry}>
          <Text style={styles.retryLabel}>重试</Text>
        </Pressable>
      )}
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
  backButton: {
    width: 46,
    height: 46,
    borderRadius: theme.radius.full,
    alignItems: 'center',
    justifyContent: 'center',
  },
  // 设计稿:输入框 40 高、圆角 6、surface 底,左右 margin 4/12
  inputBox: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 9,
    height: INPUT_HEIGHT,
    paddingHorizontal: theme.spacing.row,
    marginLeft: theme.spacing.xs,
    marginRight: theme.spacing.md,
    borderRadius: INPUT_RADIUS,
    backgroundColor: theme.colors.surface,
  },
  input: {
    flex: 1,
    ...theme.typography.drawerItem,
    color: theme.colors.fg,
    paddingVertical: 0,
  },
  // 设计稿:tab 行在正文区(bg 底),选中项 fg 色 + 底部 3px 指示条
  tabRow: {
    flexDirection: 'row',
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  tab: {
    flex: 1,
    height: TAB_HEIGHT,
    alignItems: 'center',
    justifyContent: 'center',
  },
  tabIndicator: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    height: 3,
    backgroundColor: theme.colors.fg,
  },
  tabLabel: {
    ...theme.typography.menuItem,
    color: theme.colors.fg,
  },
  tabLabelInactive: {
    color: theme.colors.meta,
  },
  sectionTitle: {
    ...theme.typography.searchSection,
    color: theme.colors.fg,
    paddingTop: theme.spacing.lg,
    paddingHorizontal: theme.spacing.lg,
    paddingBottom: 10,
  },
  optionRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.sm,
    paddingHorizontal: theme.spacing.lg,
    paddingBottom: theme.spacing.lg,
  },
  option: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    paddingRight: theme.spacing.sm,
  },
  optionLabel: {
    ...theme.typography.dialogListItem,
    color: theme.colors.fg2,
  },
  divider: {
    height: 1,
    backgroundColor: theme.colors.divider,
  },
  historyHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingRight: theme.spacing.lg,
  },
  historyTitle: {
    flex: 1,
    paddingBottom: theme.spacing.sm,
  },
  clearLabel: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
  },
  historyEmpty: {
    ...theme.typography.notice,
    color: theme.colors.meta,
    paddingHorizontal: theme.spacing.lg,
    paddingVertical: theme.spacing.md,
  },
  historyRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 13,
    paddingVertical: 13,
    paddingHorizontal: theme.spacing.lg,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  historyQuery: {
    flex: 1,
    ...theme.typography.dialogListItem,
    color: theme.colors.fg,
  },
  historyScope: {
    ...theme.typography.meta,
    color: theme.colors.meta,
  },
  bottomSpacer: {
    height: 26,
  },
  // 结果统计条(listSub 延伸,与我的主题页的副标题条同款)
  resultSub: {
    ...theme.typography.listSubtitle,
    paddingVertical: 11,
    paddingHorizontal: theme.spacing.lg,
    color: theme.colors.meta,
    backgroundColor: theme.colors.surface2,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  footerText: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
    textAlign: 'center',
    paddingVertical: theme.spacing.md,
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
  boardRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.md,
    paddingVertical: theme.spacing.md,
    paddingLeft: theme.spacing.lg,
    paddingRight: theme.spacing.sm,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  boardText: {
    flex: 1,
    gap: 3,
  },
  boardName: {
    ...theme.typography.listTitle,
    color: theme.colors.fg,
  },
  boardMeta: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
  },
  boardStar: {
    width: 40,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
  },
  userRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.md,
    paddingVertical: theme.spacing.row,
    paddingHorizontal: theme.spacing.lg,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  // 楼层头像同款 42 圆(avatar.tsx),这里只要占位形态
  userAvatar: {
    width: 42,
    height: 42,
    borderRadius: 21,
    alignItems: 'center',
    justifyContent: 'center',
  },
  userInitial: {
    ...theme.typography.avatarInitial,
    color: theme.colors.onPrimary,
  },
}));

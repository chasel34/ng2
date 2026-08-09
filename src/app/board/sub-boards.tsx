import { useLocalSearchParams, useRouter } from 'expo-router';
import { Pressable, ScrollView, Text, View } from 'react-native';

import type { SubBoard } from '@/core/api';
import { useAccounts } from '@/store/accounts';
import {
  useSubBoardPending,
  useSubBoardState,
  useToggleSubBoard,
} from '@/store/sub-boards';
import { useTopicList, useTopicSort } from '@/store/topic-list';
import { showLoginPrompt } from '@/ui/login-prompt';
import { showSnackbar } from '@/ui/snackbar';
import { LoadFailedNotice } from '@/ui/error-screen';
import { EmptyState, LoadingState } from '@/ui/state-view';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { TopBar, TopBarButton, TopBarTitle } from '@/ui/top-bar';

/**
 * 子版块列表(CONTEXT.md「子版块」;版块页菜单的「子版块」进来)。
 *
 * 数据不另打接口:子版块随主题列表的 `__F.sub_forums` 一起下来,这里用**同一个
 * queryKey** 读版块页已经拉过的第一页(ADR-0002:能少打就少打)。所以排序也要
 * 取当前那一档,否则 key 对不上会再拉一次。
 *
 * 设计稿没画这一屏,按列表页的行样式延伸:一行一个子版块,点行进它的主题列表,
 * 右边那颗按钮切订阅/屏蔽。
 */
export default function SubBoardsScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();

  const { id, name, kind } = useLocalSearchParams<{ id: string; name?: string; kind?: string }>();
  const boardId = Number(id);
  const boardKind = kind === 'collection' ? 'collection' : 'board';
  const sort = useTopicSort((state) => state.sort);

  const { data, error, isPending, refetch } = useTopicList({ boardId, kind: boardKind, sort });
  const firstPage = data?.pages[0];
  const subBoards = firstPage?.subBoards ?? [];
  // 操作要带父版块的 fid;合集没有 fid 时退回路由上的 id
  const parentFid = firstPage?.board?.fid ?? boardId;

  const openSubBoard = (subBoard: SubBoard) => {
    router.push({
      pathname: '/board/[id]',
      params: { id: String(subBoard.id), name: subBoard.name, kind: subBoard.kind },
    });
  };

  const body = () => {
    if (isPending) return <LoadingState />;
    if (data === undefined && error !== null) {
      return (
        <View style={styles.center}>
          <LoadFailedNotice error={error} onRetry={() => void refetch()} />
        </View>
      );
    }
    if (subBoards.length === 0) {
      return <EmptyState icon="account_tree" text="这个版块没有子版块" />;
    }
    return (
      <ScrollView contentContainerStyle={styles.list}>
        {subBoards.map((subBoard) => (
          <SubBoardRow
            key={`${subBoard.kind}/${subBoard.id}`}
            subBoard={subBoard}
            parentFid={parentFid}
            onOpen={openSubBoard}
          />
        ))}
        <Text style={styles.footnote}>
          订阅后这个子版块的主题会出现在版块列表里,屏蔽则不再出现。
        </Text>
      </ScrollView>
    );
  };

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
        {/* 设计稿把版块名并进顶栏标题(「子板块 · 网事杂谈」),没有副标题条 */}
        <TopBarTitle variant="sub">子版块 · {name ?? `版块 ${id}`}</TopBarTitle>
      </TopBar>

      {body()}
    </View>
  );
}

/** 一行子版块:左边名字与副标题,右边订阅开关。 */
function SubBoardRow({
  subBoard,
  parentFid,
  onOpen,
}: {
  subBoard: SubBoard;
  parentFid: number;
  onOpen: (subBoard: SubBoard) => void;
}) {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();

  const uid = useAccounts((state) => state.currentUid);
  const state = useSubBoardState(uid, subBoard);
  const pending = useSubBoardPending(uid, subBoard);
  const toggle = useToggleSubBoard();

  const flip = () => {
    if (uid === null) {
      showLoginPrompt(router, '登录后才能订阅或屏蔽子版块');
      return;
    }
    const action = state.subscribed ? 'block' : 'subscribe';
    const verb = action === 'subscribe' ? '订阅' : '屏蔽';
    void toggle({ uid, subBoard, parentFid, action }).then(
      () => showSnackbar(`已${verb}「${subBoard.name}」`),
      // 失败时本地状态已经回滚,只把服务端的话说出来
      (error: unknown) =>
        showSnackbar(error instanceof Error ? error.message : `${verb}没能同步到服务端`),
    );
  };

  return (
    <Pressable
      style={styles.row}
      onPress={() => onOpen(subBoard)}
      android_ripple={{ color: theme.colors.divider }}
    >
      <View style={styles.rowText}>
        <Text style={styles.rowName} numberOfLines={1}>
          {subBoard.name}
        </Text>
        {subBoard.info !== undefined && (
          <Text style={styles.rowInfo} numberOfLines={1}>
            {subBoard.info}
          </Text>
        )}
      </View>

      {/* 服务端不让改的(attributes 太小)只显示状态,不给按钮 */}
      {state.filterable ? (
        <Pressable
          style={[styles.toggle, state.subscribed && styles.toggleOn]}
          onPress={flip}
          disabled={pending}
          hitSlop={8}
          accessibilityLabel={state.subscribed ? `屏蔽 ${subBoard.name}` : `订阅 ${subBoard.name}`}
        >
          <Text
            style={[styles.toggleLabel, state.subscribed && styles.toggleLabelOn]}
            allowFontScaling={false}
          >
            {pending ? '处理中' : state.subscribed ? '已订阅' : '已屏蔽'}
          </Text>
        </Pressable>
      ) : (
        <Text style={styles.locked} allowFontScaling={false}>
          不可更改
        </Text>
      )}
    </Pressable>
  );
}

const useStyles = createThemedStyles((theme) => ({
  root: {
    flex: 1,
    backgroundColor: theme.colors.bg,
  },
  list: {
    paddingBottom: 26,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.row,
    paddingVertical: theme.spacing.row,
    paddingHorizontal: theme.spacing.lg,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  rowText: {
    flex: 1,
    minWidth: 0,
  },
  rowName: {
    ...theme.typography.drawerItem,
    color: theme.colors.fg,
  },
  rowInfo: {
    ...theme.typography.cardMeta,
    color: theme.colors.meta,
    marginTop: 3,
  },
  /** 设计稿:32 高的纯文字胶囊,左右 13,1px primary 描边;已订阅时填 primary,屏蔽时留空底 */
  toggle: {
    alignItems: 'center',
    justifyContent: 'center',
    height: 32,
    paddingHorizontal: 13,
    borderRadius: theme.radius.pill,
    borderWidth: 1,
    borderColor: theme.colors.primary,
  },
  toggleOn: {
    backgroundColor: theme.colors.primary,
  },
  toggleLabel: {
    ...theme.typography.listMeta,
    fontWeight: '600',
    color: theme.colors.primary,
  },
  toggleLabelOn: {
    color: theme.colors.onPrimary,
  },
  locked: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
  },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: theme.spacing.md,
    padding: theme.spacing.xl,
  },
  footnote: {
    ...theme.typography.note,
    color: theme.colors.meta,
    paddingVertical: theme.spacing.lg,
    paddingHorizontal: theme.spacing.lg,
  },
}));

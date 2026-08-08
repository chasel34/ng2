import { useLocalSearchParams, useRouter } from 'expo-router';
import { ActivityIndicator, Pressable, ScrollView, Text, View } from 'react-native';

import type { SubBoard } from '@/core/api';
import { useAccounts } from '@/store/accounts';
import {
  useSubBoardPending,
  useSubBoardState,
  useToggleSubBoard,
} from '@/store/sub-boards';
import { useTopicList, useTopicSort } from '@/store/topic-list';
import { Icon } from '@/ui/icon';
import { showLoginPrompt } from '@/ui/login-prompt';
import { showSnackbar } from '@/ui/snackbar';
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

  const { data, error, isPending } = useTopicList({ boardId, kind: boardKind, sort });
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
    if (isPending) {
      return (
        <View style={styles.center}>
          <ActivityIndicator color={theme.colors.primary} />
        </View>
      );
    }
    if (subBoards.length === 0) {
      const failed = data === undefined && error !== null;
      return (
        <View style={styles.center}>
          <Icon name={failed ? 'cloud_off' : 'account_tree'} size={40} color={theme.colors.meta} />
          <Text style={styles.errorText}>
            {failed
              ? error instanceof Error
                ? error.message
                : '子版块拉不下来'
              : '这个版块没有子版块'}
          </Text>
        </View>
      );
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
          size={24}
          onPress={() => router.back()}
          accessibilityLabel="返回"
        />
        <TopBarTitle variant="sub">子版块</TopBarTitle>
      </TopBar>

      <Text style={styles.sub}>{name ?? `版块 ${id}`}</Text>

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
          style={styles.toggle}
          onPress={flip}
          disabled={pending}
          hitSlop={8}
          accessibilityLabel={state.subscribed ? `屏蔽 ${subBoard.name}` : `订阅 ${subBoard.name}`}
        >
          <Icon
            name={state.subscribed ? 'check_box' : 'check_box_outline_blank'}
            size={21}
            color={state.subscribed ? theme.colors.primary : theme.colors.meta}
          />
          <Text
            style={[styles.toggleLabel, state.subscribed && { color: theme.colors.primary }]}
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
  // 设计稿 listSub:12px meta 色副标题条,surface-2 底
  sub: {
    paddingVertical: 11,
    paddingHorizontal: theme.spacing.lg,
    fontSize: 12,
    color: theme.colors.meta,
    backgroundColor: theme.colors.surface2,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  list: {
    paddingBottom: 26,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.md,
    paddingVertical: theme.spacing.md,
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
    ...theme.typography.listMeta,
    color: theme.colors.meta,
    marginTop: 3,
  },
  toggle: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    paddingVertical: 6,
    paddingHorizontal: 9,
    borderRadius: theme.radius.sm,
    backgroundColor: theme.colors.surface2,
  },
  toggleLabel: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
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
  errorText: {
    ...theme.typography.notice,
    color: theme.colors.fg2,
    textAlign: 'center',
  },
  footnote: {
    ...theme.typography.note,
    color: theme.colors.meta,
    paddingVertical: theme.spacing.lg,
    paddingHorizontal: theme.spacing.lg,
  },
}));

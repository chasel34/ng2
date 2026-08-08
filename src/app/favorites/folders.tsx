import { useRouter } from 'expo-router';
import { useState } from 'react';
import { ActivityIndicator, Pressable, RefreshControl, ScrollView, Text, View } from 'react-native';

import type { FavoriteFolder } from '@/core/api';
import {
  useCreateFolder,
  useDeleteFolder,
  useFavoriteFolders,
  useModifyFolder,
} from '@/store/topic-favor';
import { ConfirmDialog } from '@/ui/confirm-dialog';
import { Icon } from '@/ui/icon';
import { InputDialog } from '@/ui/input-dialog';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { showToast } from '@/ui/toast';
import { TopBar, TopBarButton, TopBarTitle, topBarSpacer } from '@/ui/top-bar';

/** 服务端的收藏夹上限(设计稿「新建收藏夹」对话框的提示语)。 */
const FOLDER_LIMIT = 20;

/** 开着哪个对话框。三个都作用在某个夹上,所以把夹一起带着。 */
type Dialog =
  | { kind: 'create' }
  | { kind: 'rename'; folder: FavoriteFolder }
  | { kind: 'delete'; folder: FavoriteFolder }
  | undefined;

/**
 * 收藏夹管理(设计稿 `screen:'folders'`,CONTEXT.md「收藏夹」)。
 *
 * 新建 / 重命名 / 设默认 / 删除四件事都走 `topic_favor_v2`,
 * 每次写完都重拉夹列表——屏上的计数与默认徽标一律以服务端为准(11 票验收项)。
 */
export default function FavoriteFoldersScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();

  const { data: folders, error, isPending, isRefetching, refetch } = useFavoriteFolders();
  const create = useCreateFolder();
  const modify = useModifyFolder();
  const remove = useDeleteFolder();

  const [dialog, setDialog] = useState<Dialog>(undefined);
  const close = () => setDialog(undefined);

  /** 写操作的统一善后:成功报一句,失败把服务端的话原样带出来。 */
  const report = (done: string) => ({
    onSuccess: () => {
      close();
      showToast(done);
    },
    onError: (failure: unknown) =>
      showToast(failure instanceof Error ? failure.message : '操作没成功'),
  });

  const submitCreate = (name: string) => {
    const trimmed = name.trim();
    if (trimmed === '') {
      showToast('收藏夹名不能是空的');
      return;
    }
    create.mutate({ name: trimmed }, report(`已新建收藏夹「${trimmed}」`));
  };

  const submitRename = (folder: FavoriteFolder, name: string) => {
    const trimmed = name.trim();
    if (trimmed === '' || trimmed === folder.name) {
      close();
      return;
    }
    modify.mutate({ folderId: folder.id, name: trimmed }, report(`已改名为「${trimmed}」`));
  };

  const setDefault = (folder: FavoriteFolder) => {
    if (folder.isDefault) return;
    // 设默认与重命名是同一个 modify_folder,name 必传,所以把现名原样带回去
    modify.mutate(
      { folderId: folder.id, name: folder.name, asDefault: true },
      report(`已把「${folder.name}」设为默认收藏夹`),
    );
  };

  const body = () => {
    if (isPending) {
      return (
        <View style={styles.center}>
          <ActivityIndicator color={theme.colors.primary} />
        </View>
      );
    }
    if (folders === undefined) {
      return (
        <View style={styles.center}>
          <Icon name="cloud_off" size={40} color={theme.colors.meta} />
          <Text style={styles.errorText}>
            {error instanceof Error ? error.message : '收藏夹列表拉不下来'}
          </Text>
          <Pressable style={styles.retry} onPress={() => void refetch()}>
            <Text style={styles.retryLabel}>重试</Text>
          </Pressable>
        </View>
      );
    }

    return (
      <ScrollView
        style={styles.body}
        contentContainerStyle={styles.bodyContent}
        refreshControl={
          <RefreshControl
            refreshing={isRefetching}
            onRefresh={() => void refetch()}
            colors={[theme.colors.primary]}
            tintColor={theme.colors.primary}
          />
        }
      >
        {folders.length === 0 ? (
          <View style={styles.center}>
            <Icon name="folder" size={40} color={theme.colors.meta} />
            <Text style={styles.errorText}>还没有收藏夹，点右上角新建一个</Text>
          </View>
        ) : (
          folders.map((folder) => (
            <View key={folder.id} style={styles.card}>
              <Icon name="folder" size={24} color={theme.colors.accent} />
              <View style={styles.cardText}>
                <View style={styles.cardTitleRow}>
                  <Text style={styles.cardName} numberOfLines={1}>
                    {folder.name}
                  </Text>
                  {folder.isDefault && (
                    <View style={styles.badge}>
                      <Text style={styles.badgeLabel}>默认</Text>
                    </View>
                  )}
                </View>
                <Text style={styles.cardSub}>{folder.count} 个主题</Text>
              </View>
              <Pressable
                onPress={() => setDialog({ kind: 'rename', folder })}
                hitSlop={8}
                accessibilityLabel={`重命名 ${folder.name}`}
              >
                <Icon name="edit" size={20} color={theme.colors.meta} />
              </Pressable>
              <Pressable
                onPress={() => setDefault(folder)}
                hitSlop={8}
                disabled={folder.isDefault}
                accessibilityLabel={`把 ${folder.name} 设为默认收藏夹`}
              >
                <Icon
                  name="push_pin"
                  size={20}
                  color={folder.isDefault ? theme.colors.primary : theme.colors.meta}
                />
              </Pressable>
              <Pressable
                onPress={() => setDialog({ kind: 'delete', folder })}
                hitSlop={8}
                accessibilityLabel={`删除 ${folder.name}`}
              >
                <Icon name="delete" size={20} color={theme.colors.danger} />
              </Pressable>
            </View>
          ))
        )}

        <Text style={styles.hint}>
          收藏帖子时会弹出这份列表，一个主题可以同时归入多个收藏夹；删掉收藏夹会连同夹里的收藏一起删掉，删了找不回来。
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
        <TopBarTitle variant="sub">收藏夹管理</TopBarTitle>
        <TopBarButton
          icon="create_new_folder"
          size={23}
          style={topBarSpacer}
          onPress={() => {
            if (folders !== undefined && folders.length >= FOLDER_LIMIT) {
              showToast(`最多 ${FOLDER_LIMIT} 个收藏夹`);
              return;
            }
            setDialog({ kind: 'create' });
          }}
          accessibilityLabel="新建收藏夹"
        />
      </TopBar>

      {body()}

      <InputDialog
        open={dialog?.kind === 'create'}
        title="新建收藏夹"
        hint={`最多 ${FOLDER_LIMIT} 个收藏夹`}
        confirmLabel={create.isPending ? '创建中…' : '创建'}
        onCancel={close}
        onConfirm={submitCreate}
      />
      <InputDialog
        open={dialog?.kind === 'rename'}
        title="重命名收藏夹"
        // InputDialog 每次打开都会回到 initialValue,换个夹改名不会留着上一个夹的名字
        initialValue={dialog?.kind === 'rename' ? dialog.folder.name : ''}
        confirmLabel={modify.isPending ? '保存中…' : '保存'}
        onCancel={close}
        onConfirm={(name) => {
          if (dialog?.kind === 'rename') submitRename(dialog.folder, name);
        }}
      />
      <ConfirmDialog
        open={dialog?.kind === 'delete'}
        title="删除收藏夹"
        message={
          dialog?.kind === 'delete'
            ? `「${dialog.folder.name}」里的 ${dialog.folder.count} 个收藏会一起删掉，删了找不回来。`
            : undefined
        }
        confirmLabel={remove.isPending ? '删除中…' : '删除'}
        destructive
        onCancel={close}
        onConfirm={() => {
          if (dialog?.kind !== 'delete') return;
          const { folder } = dialog;
          remove.mutate(folder.id, report(`已删除「${folder.name}」`));
        }}
      />
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
  bodyContent: {
    paddingTop: theme.spacing.md,
    paddingHorizontal: theme.spacing.md,
    paddingBottom: 24,
  },
  center: {
    alignItems: 'center',
    justifyContent: 'center',
    gap: theme.spacing.md,
    padding: theme.spacing.xl,
    paddingTop: 60,
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
  // 设计稿:14 内边距、13 间距、圆角 14、surface 底 + 1px divider 描边,行距 10
  card: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 13,
    padding: theme.spacing.row,
    marginBottom: 10,
    borderRadius: theme.radius.lg,
    borderWidth: 1,
    borderColor: theme.colors.divider,
    backgroundColor: theme.colors.surface,
  },
  cardText: {
    flex: 1,
    minWidth: 0,
  },
  cardTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
  },
  cardName: {
    ...theme.typography.tab,
    color: theme.colors.fg,
    flexShrink: 1,
  },
  badge: {
    paddingVertical: 2,
    paddingHorizontal: 7,
    borderRadius: 6,
    backgroundColor: theme.colors.primaryContainer,
  },
  badgeLabel: {
    ...theme.typography.folderBadge,
    color: theme.colors.primary,
  },
  cardSub: {
    ...theme.typography.cardMeta,
    color: theme.colors.meta,
    marginTop: theme.spacing.xs,
  },
  hint: {
    ...theme.typography.cardMeta,
    color: theme.colors.meta,
    lineHeight: 19.2,
    paddingVertical: 6,
    paddingHorizontal: theme.spacing.xs,
  },
}));

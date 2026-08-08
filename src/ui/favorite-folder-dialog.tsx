import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Animated, Easing, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';

import { diffFolderSelection } from '@/core/local';
import {
  useApplyTopicFavorites,
  useCreateFolder,
  useFavoriteFolders,
  useTopicFolderIds,
} from '@/store/topic-favor';

import { Icon } from './icon';
import { InputDialog } from './input-dialog';
import { createThemedStyles, useTheme } from './theme';
import { showToast } from './toast';

/** 服务端的收藏夹上限(设计稿「新建收藏夹」对话框的提示语)。 */
const FOLDER_LIMIT = 20;

export interface FavoriteFolderDialogProps {
  open: boolean;
  /** 要收藏的主题 */
  tid: number;
  onClose: () => void;
}

/**
 * 「收藏到…」多选收藏夹对话框(设计稿 `dialog:'folder'`)。
 *
 * **传 tid 即可从任意入口调起**——详情页顶栏菜单在用,12 票的楼层菜单直接照抄:
 *
 *     const [favorOpen, setFavorOpen] = useState(false);
 *     <FavoriteFolderDialog open={favorOpen} tid={topicId} onClose={() => setFavorOpen(false)} />
 *
 * 组件自己拉夹列表、自己写回服务端,调用方只管开关。关着的时候整棵子树不挂载,
 * 所以不会在每次进详情页时白打一发 `list_folder`。
 */
export function FavoriteFolderDialog({ open, tid, onClose }: FavoriteFolderDialogProps) {
  if (!open) return null;
  return <FolderPicker tid={tid} onClose={onClose} />;
}

function FolderPicker({ tid, onClose }: { tid: number; onClose: () => void }) {
  const styles = useStyles();
  const theme = useTheme();
  const progress = useRef(new Animated.Value(0)).current;

  const { data: folders, error, isPending, refetch } = useFavoriteFolders();
  const known = useTopicFolderIds(tid);
  const apply = useApplyTopicFavorites();
  const create = useCreateFolder();

  // 打开这一刻的归属就是「改动前」的基准;之后勾选只动 selected,不跟着索引跑
  const [initial] = useState<readonly number[]>(known);
  const [selected, setSelected] = useState<readonly number[]>(known);
  const [newFolderOpen, setNewFolderOpen] = useState(false);

  useEffect(() => {
    const animation = Animated.timing(progress, {
      toValue: 1,
      duration: 200,
      easing: Easing.out(Easing.quad),
      useNativeDriver: true,
    });
    animation.start();
    return () => animation.stop();
  }, [progress]);

  const busy = apply.isPending || create.isPending;

  const toggle = (folderId: number) => {
    setSelected((current) =>
      current.includes(folderId)
        ? current.filter((id) => id !== folderId)
        : [...current, folderId],
    );
  };

  const nameOf = (folderId: number) =>
    folders?.find((folder) => folder.id === folderId)?.name ?? `收藏夹 ${folderId}`;

  const confirm = () => {
    const diff = diffFolderSelection(initial, selected);
    if (diff.added.length === 0 && diff.removed.length === 0) {
      onClose();
      return;
    }
    apply.mutate(
      { tid, added: diff.added, removed: diff.removed },
      {
        onSuccess: () => {
          showToast(favoriteResultText(diff.added.map(nameOf), diff.removed.map(nameOf)));
          onClose();
        },
        // 串行写到一半失败:已做成的那几个不回滚,提示里说清哪一步断的
        onError: (failure) =>
          showToast(failure instanceof Error ? failure.message : '收藏没写进去'),
      },
    );
  };

  const createFolder = (name: string) => {
    const trimmed = name.trim();
    if (trimmed === '') {
      showToast('收藏夹名不能是空的');
      return;
    }
    create.mutate(
      { name: trimmed },
      {
        onSuccess: (folderId) => {
          setNewFolderOpen(false);
          // 新建完顺手勾上——用户点「新建收藏夹…」就是想把这帖收进去
          if (folderId !== undefined) setSelected((current) => [...current, folderId]);
          showToast(`已新建收藏夹「${trimmed}」`);
        },
        onError: (failure) =>
          showToast(failure instanceof Error ? failure.message : '收藏夹没建成'),
      },
    );
  };

  const list = () => {
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
          <Text style={styles.errorText}>
            {error instanceof Error ? error.message : '收藏夹列表拉不下来'}
          </Text>
          <Pressable onPress={() => void refetch()} hitSlop={8}>
            <Text style={styles.retryText}>重试</Text>
          </Pressable>
        </View>
      );
    }

    return (
      <ScrollView style={styles.list} showsVerticalScrollIndicator={false}>
        {folders.map((folder) => {
          const checked = selected.includes(folder.id);
          return (
            <Pressable
              key={folder.id}
              style={styles.row}
              onPress={() => toggle(folder.id)}
              disabled={busy}
              android_ripple={{ color: theme.colors.divider }}
              accessibilityRole="checkbox"
              accessibilityState={{ checked }}
            >
              <Icon
                name={checked ? 'check_box' : 'check_box_outline_blank'}
                size={21}
                color={checked ? theme.colors.primary : theme.colors.meta}
              />
              <View style={styles.rowText}>
                <Text style={styles.rowLabel} numberOfLines={1}>
                  {folder.name}
                </Text>
                <Text style={styles.rowSub} numberOfLines={1}>
                  {folder.count} 个主题{folder.isDefault ? ' · 默认夹' : ''}
                </Text>
              </View>
            </Pressable>
          );
        })}

        {folders.length < FOLDER_LIMIT && (
          <Pressable
            style={styles.row}
            onPress={() => setNewFolderOpen(true)}
            disabled={busy}
            android_ripple={{ color: theme.colors.divider }}
          >
            <Icon name="add" size={21} color={theme.colors.accent} />
            <View style={styles.rowText}>
              <Text style={styles.rowLabel}>新建收藏夹…</Text>
            </View>
          </Pressable>
        )}

        {/* 勾选状态是本机攒的(服务端给不出反查),这层限制得跟用户说清楚 */}
        <Text style={styles.footnote}>
          勾选状态取自本机记录，在网页版等别处收藏的帖子可能显示为未勾选。
        </Text>
      </ScrollView>
    );
  };

  // 「新建收藏夹」在设计稿里是同一个对话框槽位的另一个形态,所以整面板换掉而不是叠一层
  // (叠着的话两层遮罩会把底下压得死黑)。勾选状态留在本组件里,建完就回到多选。
  if (newFolderOpen) {
    return (
      <InputDialog
        open
        title="新建收藏夹"
        hint={`最多 ${FOLDER_LIMIT} 个收藏夹`}
        confirmLabel={create.isPending ? '创建中…' : '创建'}
        onCancel={() => setNewFolderOpen(false)}
        onConfirm={createFolder}
      />
    );
  }

  return (
    <View style={styles.root}>
      <Pressable style={StyleSheet.absoluteFill} onPress={onClose} accessibilityLabel="关闭对话框" />
      <Animated.View
        style={[
          styles.panel,
          {
            opacity: progress,
            transform: [
              { scale: progress.interpolate({ inputRange: [0, 1], outputRange: [0.94, 1] }) },
            ],
          },
        ]}
      >
        <Text style={styles.title}>收藏到…</Text>
        {list()}
        <View style={styles.actions}>
          <Pressable style={styles.cancel} onPress={onClose} disabled={busy}>
            <Text style={styles.cancelLabel}>取消</Text>
          </Pressable>
          <Pressable
            style={[styles.confirm, busy && styles.confirmBusy]}
            onPress={confirm}
            disabled={busy || folders === undefined}
          >
            <Text style={styles.confirmLabel}>{apply.isPending ? '保存中…' : '完成'}</Text>
          </Pressable>
        </View>
      </Animated.View>
    </View>
  );
}

/** 完成后的提示语。设计稿是「已收藏到「默认收藏夹」」,取消收藏时换个说法。 */
function favoriteResultText(added: readonly string[], removed: readonly string[]): string {
  const parts: string[] = [];
  if (added.length > 0) parts.push(`已收藏到「${added.join('、')}」`);
  if (removed.length > 0) parts.push(`已从「${removed.join('、')}」移出`);
  return parts.join('；');
}

const useStyles = createThemedStyles((theme) => ({
  root: {
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
  panel: {
    width: '100%',
    borderRadius: theme.radius.dialog,
    backgroundColor: theme.colors.menu,
    paddingTop: 22,
    paddingHorizontal: 22,
    paddingBottom: theme.spacing.row,
    boxShadow: theme.shadows.elevation2,
  },
  title: {
    ...theme.typography.dialogTitle,
    color: theme.colors.fg,
  },
  list: {
    marginTop: theme.spacing.md,
    // 夹多了也不让对话框顶到屏幕外,列表自己滚
    maxHeight: 320,
  },
  center: {
    marginTop: theme.spacing.md,
    minHeight: 96,
    alignItems: 'center',
    justifyContent: 'center',
    gap: theme.spacing.sm,
  },
  errorText: {
    ...theme.typography.notice,
    color: theme.colors.fg2,
    textAlign: 'center',
  },
  retryText: {
    ...theme.typography.dialogAction,
    color: theme.colors.primary,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 13,
    height: 48,
  },
  rowText: {
    flex: 1,
  },
  rowLabel: {
    ...theme.typography.dialogListItem,
    color: theme.colors.fg,
  },
  rowSub: {
    ...theme.typography.meta,
    color: theme.colors.meta,
  },
  footnote: {
    ...theme.typography.meta,
    color: theme.colors.meta,
    lineHeight: 17,
    paddingTop: 6,
    paddingBottom: 2,
  },
  actions: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 6,
    marginTop: theme.spacing.row,
  },
  cancel: {
    height: 40,
    paddingHorizontal: theme.spacing.lg,
    borderRadius: theme.radius.full,
    alignItems: 'center',
    justifyContent: 'center',
  },
  cancelLabel: {
    ...theme.typography.dialogAction,
    color: theme.colors.fg2,
  },
  confirm: {
    height: 40,
    paddingHorizontal: theme.spacing.xl,
    borderRadius: theme.radius.full,
    backgroundColor: theme.colors.primary,
    alignItems: 'center',
    justifyContent: 'center',
  },
  confirmBusy: {
    opacity: 0.6,
  },
  confirmLabel: {
    ...theme.typography.dialogAction,
    color: theme.colors.onPrimary,
  },
}));

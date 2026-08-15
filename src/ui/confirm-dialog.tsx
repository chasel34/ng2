import { Pressable, Text, View } from 'react-native';

import Reanimated from 'react-native-reanimated';

import { useOverlayAnimation, OverlayScrim } from './overlay';
import { createThemedStyles } from './theme';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  /** 正文说明,例如「将取消收藏全部 5 个版块」;对话框关着时允许缺省 */
  message?: string;
  /** 确定按钮文案 */
  confirmLabel: string;
  /** 危险操作(清空/删除)把确定钮染成 danger */
  destructive?: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

/**
 * 「标题 + 一段正文 + 取消/确定」的确认对话框。
 * 面板样式与 input-dialog.tsx 同一形状(设计稿 sign 对话框就是这种带正文的变体),
 * 只是把输入行换成说明文字。
 */
export function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel,
  destructive = false,
  onCancel,
  onConfirm,
}: ConfirmDialogProps) {
  const styles = useStyles();
  const { scrimStyle, panelStyle } = useOverlayAnimation(open);

  if (!open) return null;

  return (
    <View style={styles.root}>
      <OverlayScrim style={scrimStyle} onPress={onCancel} />
      <Reanimated.View style={[styles.panel, panelStyle]}>
        <Text style={styles.title}>{title}</Text>
        {message !== undefined && <Text style={styles.message}>{message}</Text>}
        <View style={styles.actions}>
          <Pressable style={styles.cancel} onPress={onCancel}>
            <Text style={styles.cancelLabel}>取消</Text>
          </Pressable>
          <Pressable
            style={[styles.confirm, destructive && styles.confirmDanger]}
            onPress={onConfirm}
          >
            <Text style={styles.confirmLabel}>{confirmLabel}</Text>
          </Pressable>
        </View>
      </Reanimated.View>
    </View>
  );
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
  // 设计稿:正文 13.5 · 1.6,距标题 9
  message: {
    ...theme.typography.dialogBody,
    color: theme.colors.fg2,
    marginTop: 9,
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
  confirmDanger: {
    backgroundColor: theme.colors.danger,
  },
  confirmLabel: {
    ...theme.typography.dialogAction,
    color: theme.colors.onPrimary,
  },
}));

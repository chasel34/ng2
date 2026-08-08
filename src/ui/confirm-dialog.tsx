import { useEffect, useRef } from 'react';
import { Animated, Easing, Pressable, StyleSheet, Text, View } from 'react-native';

import { createThemedStyles } from './theme';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  /** 标题下面那段说明,换行照原样排 */
  body?: string;
  confirmLabel: string;
  /** 删除这类不可撤销的操作:确定钮换成危险色 */
  destructive?: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

/**
 * 设计稿那个「标题 + 一段说明 + 取消/确定」的对话框(`dlgBody` 那一档)。
 *
 * 与 `InputDialog` 是同一个壳,区别只在中间放的是说明文字还是输入框。
 * 删收藏夹这种删了就回不来的操作走它,不走 Android 原生 Alert——
 * 原生弹窗的配色不跟着 app 主题走,深色下会白得刺眼。
 */
export function ConfirmDialog({
  open,
  title,
  body,
  confirmLabel,
  destructive = false,
  onCancel,
  onConfirm,
}: ConfirmDialogProps) {
  const styles = useStyles();
  const progress = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    if (!open) {
      progress.setValue(0);
      return;
    }
    const animation = Animated.timing(progress, {
      toValue: 1,
      duration: 200,
      easing: Easing.out(Easing.quad),
      useNativeDriver: true,
    });
    animation.start();
    return () => animation.stop();
  }, [open, progress]);

  if (!open) return null;

  return (
    <View style={styles.root}>
      <Pressable style={StyleSheet.absoluteFill} onPress={onCancel} accessibilityLabel="关闭对话框" />
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
        <Text style={styles.title}>{title}</Text>
        {body !== undefined && <Text style={styles.body}>{body}</Text>}
        <View style={styles.actions}>
          <Pressable style={styles.cancel} onPress={onCancel}>
            <Text style={styles.cancelLabel}>取消</Text>
          </Pressable>
          <Pressable
            style={[styles.confirm, destructive && styles.confirmDestructive]}
            onPress={onConfirm}
          >
            <Text style={styles.confirmLabel}>{confirmLabel}</Text>
          </Pressable>
        </View>
      </Animated.View>
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
  body: {
    ...theme.typography.notice,
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
  confirmDestructive: {
    backgroundColor: theme.colors.danger,
  },
  confirmLabel: {
    ...theme.typography.dialogAction,
    color: theme.colors.onPrimary,
  },
}));

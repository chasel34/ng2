import { useEffect, useRef, useState } from 'react';
import {
  Animated,
  Easing,
  Keyboard,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
  type KeyboardTypeOptions,
} from 'react-native';

import { createThemedStyles, useTheme } from './theme';

export interface InputDialogProps {
  open: boolean;
  title: string;
  /** 输入框下面那行灰字,例如「共 7 页 · 输入 1 – 7」 */
  hint?: string;
  /**
   * 输入不合法时就地顶掉 hint 的红字(24 的「由 URL 读取」:链接解不开不跳转、不关框)。
   * 位置与 hint 同一行,只换颜色,不动版式。
   */
  error?: string;
  /** 确定按钮的文案,设计稿各对话框不一样 */
  confirmLabel: string;
  initialValue?: string;
  keyboardType?: KeyboardTypeOptions;
  /** 多行输入(签名这种可以换行的内容);此时回车是换行,确定只能点按钮 */
  multiline?: boolean;
  /**
   * 每次改动都通知一次。给的就是「一动手就把 `error` 撤了」——
   * 报了红字之后清空重输,红字不该赖到下一次点确定才刷新(M3 验收缺陷 4)。
   */
  onChangeText?: (value: string) => void;
  onCancel: () => void;
  onConfirm: (value: string) => void;
}

/**
 * 设计稿那个「标题 + 一行下划线输入 + 取消/确定」的对话框。
 *
 * 先给跳页用;24 票的「由 URL 读取」、11 票的「新建收藏夹」都是同一个形状,
 * 到时候直接复用。
 */
export function InputDialog({
  open,
  title,
  hint,
  error,
  confirmLabel,
  initialValue = '',
  keyboardType,
  multiline = false,
  onChangeText,
  onCancel,
  onConfirm,
}: InputDialogProps) {
  const styles = useStyles();
  const theme = useTheme();
  const [value, setValue] = useState(initialValue);
  const progress = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    if (!open) {
      progress.setValue(0);
      return;
    }
    // 每次打开都回到调用方给的初始值,不留上一次的残留
    setValue(initialValue);
    const animation = Animated.timing(progress, {
      toValue: 1,
      duration: 200,
      easing: Easing.out(Easing.quad),
      useNativeDriver: true,
    });
    animation.start();
    return () => animation.stop();
  }, [open, initialValue, progress]);

  if (!open) return null;

  const confirm = () => {
    Keyboard.dismiss();
    onConfirm(value);
  };

  return (
    <View style={styles.root}>
      <Pressable style={StyleSheet.absoluteFill} onPress={onCancel} accessibilityLabel="关闭对话框" />
      <Animated.View
        style={[
          styles.panel,
          {
            opacity: progress,
            transform: [
              { scale: progress.interpolate({ inputRange: [0, 1], outputRange: [0.92, 1] }) },
            ],
          },
        ]}
      >
        <Text style={styles.title}>{title}</Text>
        <View style={styles.field}>
          <TextInput
            value={value}
            onChangeText={(next) => {
              setValue(next);
              onChangeText?.(next);
            }}
            keyboardType={keyboardType}
            autoFocus
            // 多行时回车是换行,不能拿它当「确定」,选中全文也碍事
            selectTextOnFocus={!multiline}
            multiline={multiline}
            {...(multiline ? {} : { returnKeyType: 'go' as const, onSubmitEditing: confirm })}
            style={[styles.input, multiline && styles.inputMultiline]}
            cursorColor={theme.colors.primary}
            selectionColor={theme.colors.primary}
          />
          {error !== undefined ? (
            <Text style={[styles.hint, styles.error]}>{error}</Text>
          ) : (
            hint !== undefined && <Text style={styles.hint}>{hint}</Text>
          )}
        </View>
        <View style={styles.actions}>
          <Pressable style={styles.cancel} onPress={onCancel}>
            <Text style={styles.cancelLabel}>取消</Text>
          </Pressable>
          <Pressable style={styles.confirm} onPress={confirm}>
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
  field: {
    marginTop: theme.spacing.lg,
  },
  input: {
    // 设计稿的对话框输入是 15 · 400,与抽屉条目同一档
    ...theme.typography.drawerItem,
    color: theme.colors.fg,
    borderBottomWidth: 2,
    borderBottomColor: theme.colors.primary,
    paddingHorizontal: 2,
    paddingBottom: 7,
    paddingTop: 0,
  },
  inputMultiline: {
    minHeight: 84,
    maxHeight: 180,
    textAlignVertical: 'top',
    paddingBottom: theme.spacing.md,
  },
  hint: {
    ...theme.typography.meta,
    color: theme.colors.meta,
    marginTop: 7,
  },
  error: {
    color: theme.colors.danger,
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
  confirmLabel: {
    ...theme.typography.dialogAction,
    color: theme.colors.onPrimary,
  },
}));

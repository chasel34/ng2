import { useEffect, useRef, useState } from 'react';
import { Animated, Easing, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';

import { Icon } from './icon';
import { createThemedStyles, useTheme } from './theme';

/**
 * 单选对话框(设计稿 `dialog:'theme'` 那种「标题 + 单选列表 + 取消/应用」)。
 *
 * 设置三屏里凡是「一组互斥档位」的行都用它:NGA 域名、主题风格、图片加载策略、
 * 网页数据源兜底档位。选中先只改本地态,点「应用」才回调——设计稿的按钮就是「应用」,
 * 而且域名这种改了要重打请求的档位,手滑点中不该立刻生效。
 */

export interface DialogOption<T extends string> {
  value: T;
  label: string;
  /** 第二行灰字说明 */
  sub?: string;
}

export interface OptionDialogProps<T extends string> {
  open: boolean;
  title: string;
  options: readonly DialogOption<T>[];
  value: T;
  /** 列表下方的一段说明,例如「切换后下一个请求就发到新域名」 */
  hint?: string;
  confirmLabel?: string;
  onCancel: () => void;
  onConfirm: (value: T) => void;
}

export function OptionDialog<T extends string>({
  open,
  title,
  options,
  value,
  hint,
  confirmLabel = '应用',
  onCancel,
  onConfirm,
}: OptionDialogProps<T>) {
  const styles = useStyles();
  const theme = useTheme();
  const progress = useRef(new Animated.Value(0)).current;
  const [picked, setPicked] = useState<T>(value);

  // 每次打开都从当前生效的档位开始:上次点了取消,选中态不该留在那儿
  useEffect(() => {
    if (open) setPicked(value);
  }, [open, value]);

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
              { scale: progress.interpolate({ inputRange: [0, 1], outputRange: [0.92, 1] }) },
            ],
          },
        ]}
      >
        <Text style={styles.title}>{title}</Text>
        <ScrollView style={styles.list} bounces={false}>
          {options.map((option) => {
            const selected = option.value === picked;
            return (
              <Pressable
                key={option.value}
                style={styles.option}
                onPress={() => setPicked(option.value)}
                accessibilityRole="radio"
                accessibilityState={{ selected }}
                accessibilityLabel={option.label}
              >
                <Icon
                  name={selected ? 'radio_button_checked' : 'radio_button_unchecked'}
                  size={22}
                  color={selected ? theme.colors.primary : theme.colors.meta}
                />
                <View style={styles.optionText}>
                  <Text style={[styles.optionLabel, selected && styles.optionLabelSelected]}>
                    {option.label}
                  </Text>
                  {option.sub !== undefined && <Text style={styles.optionSub}>{option.sub}</Text>}
                </View>
              </Pressable>
            );
          })}
        </ScrollView>
        {hint !== undefined && <Text style={styles.hint}>{hint}</Text>}
        <View style={styles.actions}>
          <Pressable style={styles.cancel} onPress={onCancel}>
            <Text style={styles.cancelLabel}>取消</Text>
          </Pressable>
          <Pressable style={styles.confirm} onPress={() => onConfirm(picked)}>
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
    paddingBottom: theme.spacing.row,
    boxShadow: theme.shadows.elevation2,
  },
  title: {
    ...theme.typography.dialogTitle,
    color: theme.colors.fg,
    paddingHorizontal: 22,
  },
  // 域名有五个、兜底档位有四个,长列表在面板里滚而不是把面板顶出屏幕
  list: {
    flexGrow: 0,
    maxHeight: 340,
    marginTop: theme.spacing.md,
  },
  option: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.md,
    paddingVertical: 11,
    paddingHorizontal: 22,
  },
  optionText: {
    flex: 1,
    minWidth: 0,
  },
  optionLabel: {
    ...theme.typography.dialogListItem,
    color: theme.colors.fg,
  },
  optionLabelSelected: {
    color: theme.colors.primary,
    fontWeight: '600',
  },
  optionSub: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
    marginTop: 3,
  },
  hint: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
    paddingHorizontal: 22,
    paddingTop: theme.spacing.sm,
  },
  actions: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 6,
    marginTop: theme.spacing.md,
    paddingHorizontal: theme.spacing.lg,
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

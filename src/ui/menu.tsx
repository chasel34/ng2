import { useEffect, useRef } from 'react';
import { Animated, Easing, Pressable, StyleSheet, Text, View } from 'react-native';

import { createThemedStyles, useTheme } from './theme';

export interface MenuItem {
  key: string;
  label: string;
  /** 设计稿里菜单分组之间空 10pt */
  gapBefore?: boolean;
  /** 一组互斥选项里当前生效的那条(排序切换),用主题色 + 加粗标出来 */
  selected?: boolean;
  onPress: () => void;
}

export interface OverflowMenuProps {
  open: boolean;
  onClose: () => void;
  items: readonly MenuItem[];
  /** 菜单顶边距屏幕顶部的距离,由调用方按顶栏高度算 */
  top: number;
}

/** 顶栏右上角的弹出菜单。设计稿:右侧留 8,圆角 14,条目高 50,弹出 .16s。 */
export function OverflowMenu({ open, onClose, items, top }: OverflowMenuProps) {
  const styles = useStyles();
  const theme = useTheme();
  const progress = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    if (!open) {
      progress.setValue(0);
      return;
    }
    const animation = Animated.timing(progress, {
      toValue: 1,
      duration: 160,
      easing: Easing.out(Easing.quad),
      useNativeDriver: true,
    });
    animation.start();
    return () => animation.stop();
  }, [open, progress]);

  if (!open) return null;

  return (
    <View style={StyleSheet.absoluteFill}>
      <Pressable style={StyleSheet.absoluteFill} onPress={onClose} accessibilityLabel="关闭菜单" />
      <Animated.View
        style={[
          styles.panel,
          {
            top,
            opacity: progress,
            transform: [
              { scale: progress.interpolate({ inputRange: [0, 1], outputRange: [0.94, 1] }) },
            ],
          },
        ]}
      >
        {items.map((item) => (
          <Pressable
            key={item.key}
            onPress={item.onPress}
            android_ripple={{ color: theme.colors.divider }}
            style={[styles.item, item.gapBefore === true && styles.itemGap]}
          >
            <Text style={[styles.label, item.selected === true && styles.labelSelected]}>
              {item.label}
            </Text>
          </Pressable>
        ))}
      </Animated.View>
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  panel: {
    position: 'absolute',
    right: theme.spacing.sm,
    minWidth: 186,
    maxHeight: 520,
    paddingVertical: 6,
    borderRadius: theme.radius.lg,
    backgroundColor: theme.colors.menu,
    boxShadow: theme.shadows.elevation2,
    transformOrigin: 'top right',
  },
  item: {
    height: 50,
    justifyContent: 'center',
    paddingHorizontal: 22,
  },
  itemGap: {
    marginTop: 10,
  },
  label: {
    ...theme.typography.menuItem,
    color: theme.colors.fg,
  },
  labelSelected: {
    color: theme.colors.primary,
    fontWeight: '600',
  },
}));

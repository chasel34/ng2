import { useEffect } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import Reanimated, {
  interpolate,
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';

import { useLeftHanded } from './appearance';
import { duration, easeStandardWorklet, POP_SCALE } from './motion';
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

/**
 * 顶栏右上角的弹出菜单。设计稿:右侧留 8,圆角 14,条目高 50,弹出 .16s。
 *
 * 左手模式(22 票)下整块镜像到左上角——它是浮在内容上、要单手够的东西,
 * 缩放的原点也跟着换边,免得动画从一个够不着的角上长出来。
 */
export function OverflowMenu({ open, onClose, items, top }: OverflowMenuProps) {
  const styles = useStyles();
  const theme = useTheme();
  const leftHanded = useLeftHanded();
  const progress = useSharedValue(0);

  useEffect(() => {
    if (!open) {
      progress.value = 0;
      return;
    }
    progress.value = withTiming(1, { duration: duration.menu, easing: easeStandardWorklet });
  }, [open, progress]);

  const popStyle = useAnimatedStyle(() => ({
    opacity: progress.value,
    transform: [{ scale: interpolate(progress.value, [0, 1], [POP_SCALE, 1]) }],
  }));

  if (!open) return null;

  return (
    <View style={StyleSheet.absoluteFill}>
      <Pressable style={StyleSheet.absoluteFill} onPress={onClose} accessibilityLabel="关闭菜单" />
      <Reanimated.View
        style={[styles.panel, leftHanded ? styles.panelLeft : styles.panelRight, { top }, popStyle]}
      >
        {/* 设计稿给面板设了 max-height 520 + overflow-y:auto——条目多到顶格时要能滚,
            不然最下面几条够不着(收藏夹切换菜单的夹数是用户定的) */}
        <ScrollView bounces={false} showsVerticalScrollIndicator={false}>
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
        </ScrollView>
      </Reanimated.View>
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  panel: {
    position: 'absolute',
    minWidth: 186,
    maxHeight: 520,
    paddingVertical: 6,
    borderRadius: theme.radius.lg,
    backgroundColor: theme.colors.menu,
    boxShadow: theme.shadows.elevation2,
  },
  panelRight: {
    right: theme.spacing.sm,
    transformOrigin: 'top right',
  },
  panelLeft: {
    left: theme.spacing.sm,
    transformOrigin: 'top left',
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

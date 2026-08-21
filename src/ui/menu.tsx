import { Fragment, useEffect } from 'react';
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
  /** 这一条起一个新分组:上面画一条分割线(设计稿是空 10pt 的留白,见 `separator`) */
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
          {items.map((item, index) => (
            <Fragment key={item.key}>
              {/* 第一条上面不画:面板顶上贴着一条线没有分组意义,还会怼到圆角上
                  (版块菜单的排序组在没有待办条目时正好落在第一条) */}
              {item.gapBefore === true && index > 0 && (
                <View style={styles.separator} pointerEvents="none" />
              )}
              <Pressable
                onPress={item.onPress}
                android_ripple={{ color: theme.colors.divider }}
                style={styles.item}
              >
                <Text style={[styles.label, item.selected === true && styles.labelSelected]}>
                  {item.label}
                </Text>
              </Pressable>
            </Fragment>
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
  /**
   * 分组分割线。设计稿在分组之间空 10pt,但菜单本身没有分隔线,那段留白看着
   * 就是「间距做错了」(用户两次都是这么报的)——所以把留白整个换成一条 hairline:
   * **不留外距**,条目还是一条挨一条(间距全齐),分组靠线本身表达,不靠空隙。
   * 线不吃点击(hairline 进不了热区),两侧条目的 50 高热区也不受影响。
   */
  separator: {
    height: StyleSheet.hairlineWidth,
    backgroundColor: theme.colors.divider,
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

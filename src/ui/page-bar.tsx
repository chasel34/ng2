import { useEffect, useRef } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';

import { Icon } from './icon';
import { visiblePages } from './paging';
import { createThemedStyles, useTheme } from './theme';
import { topbarOverlay } from './tokens';

/** 设计稿:页码格 28 高、最小 30 宽(圆角走 token 的 radius.sm,它的注释指的就是页码格)。 */
const CHIP_HEIGHT = 28;
const CHIP_MIN_WIDTH = 30;
/** 格间距,设计稿 `gap:6`。 */
const CHIP_GAP = 6;

/** 滚到当前页时给它左边留几格的余量,不然当前页永远贴在最左边。 */
const SCROLL_LEAD = 2;

export interface PageBarProps {
  page: number;
  totalPages: number;
  onPick: (page: number) => void;
  onJump: () => void;
}

/**
 * 顶栏下面那条页码条(设计稿 isArticle 的第二行)。
 *
 * 页数多的帖子有上千页,全铺出来 ScrollView 会卡,所以只画一个围绕当前页的窗口,
 * 首尾两页固定露出来——跳到最后一页是最常用的动作之一。
 */
export function PageBar({ page, totalPages, onPick, onJump }: PageBarProps) {
  const styles = useStyles();
  const theme = useTheme();
  const scrollRef = useRef<ScrollView>(null);

  const pages = visiblePages(page, totalPages);
  // 每格的左边界。格子不等宽(三位数页码更宽、跳号处还多一个省略号),
  // 按格数乘固定宽度估出来的位置在页数大时会偏出好几格,所以量实际布局。
  const offsets = useRef(new Map<number, number>()).current;

  useEffect(() => {
    const x = offsets.get(page);
    if (x === undefined) return;
    scrollRef.current?.scrollTo({ x: Math.max(0, x - SCROLL_LEAD * CHIP_MIN_WIDTH), animated: true });
  }, [page, totalPages, offsets]);

  return (
    <ScrollView
      ref={scrollRef}
      horizontal
      showsHorizontalScrollIndicator={false}
      contentContainerStyle={styles.bar}
    >
      {pages.map((value, index) => (
        <View
          key={value}
          style={styles.chipSlot}
          onLayout={(event) => offsets.set(value, event.nativeEvent.layout.x)}
        >
          {/* 窗口跳号的地方画个省略号,免得 3 后面直接跟 128 看着像少了页 */}
          {index > 0 && value - pages[index - 1]! > 1 && <Text style={styles.gap}>…</Text>}
          <Pressable
            style={[styles.chip, value === page && styles.chipActive]}
            onPress={() => onPick(value)}
            accessibilityLabel={`第 ${value} 页`}
          >
            <Text style={[styles.chipLabel, value === page && styles.chipLabelActive]}>{value}</Text>
          </Pressable>
        </View>
      ))}
      <Pressable style={styles.jump} onPress={onJump} accessibilityLabel="跳页">
        <Icon name="low_priority" size={15} color={theme.colors.onTopbar} />
        <Text style={styles.jumpLabel}>跳页</Text>
      </Pressable>
    </ScrollView>
  );
}

const useStyles = createThemedStyles((theme) => ({
  bar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: CHIP_GAP,
    paddingHorizontal: 10,
    paddingBottom: theme.spacing.sm,
  },
  chipSlot: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: CHIP_GAP,
  },
  chip: {
    minWidth: CHIP_MIN_WIDTH,
    height: CHIP_HEIGHT,
    paddingHorizontal: 9,
    borderRadius: theme.radius.sm,
    alignItems: 'center',
    justifyContent: 'center',
  },
  chipActive: {
    backgroundColor: topbarOverlay,
  },
  chipLabel: {
    ...theme.typography.pageChip,
    color: theme.colors.onTopbar,
    opacity: 0.62,
  },
  chipLabelActive: {
    opacity: 1,
  },
  gap: {
    ...theme.typography.pageChip,
    color: theme.colors.onTopbar,
    opacity: 0.5,
  },
  jump: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.xs,
    height: CHIP_HEIGHT,
    paddingHorizontal: 10,
    borderRadius: theme.radius.sm,
  },
  jumpLabel: {
    ...theme.typography.listMeta,
    color: theme.colors.onTopbar,
    opacity: 0.8,
  },
}));

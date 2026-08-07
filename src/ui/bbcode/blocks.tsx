import type { ReactNode } from 'react';
import { ScrollView, Text, View, type StyleProp, type TextStyle, type ViewStyle } from 'react-native';

import type { BoxNode, CollapseNode, ListNode, TableNode } from '@/core/bbcode';

import { CollapsibleCard } from '../collapsible-card';
import { beginHorizontalDrag, endHorizontalDrag } from '../horizontal-drag';
import { createThemedStyles } from '../theme';
import { tableCellWidth, tableColumnCount, tablePaddingCells } from './table';

/**
 * 结构类进阶标签的容器:折叠块、列表、表格、`[lessernuke]` 这类警告框。
 *
 * 它们的共同点是「自己占一块、里面还要继续走渲染器」,所以正文交给调用方传进来的
 * 回调(`render.tsx` 用 `BBCodeBody` 递归),这里只管框和交互。
 */

/** `[collapse]` / `[collapse=标题]`。默认收起,和网页版一致。 */
export function CollapseBlock({
  node,
  children,
}: {
  node: CollapseNode;
  children: () => ReactNode;
}) {
  return (
    <CollapsibleCard
      icon="article"
      title={node.title ?? '折叠的内容'}
      openLabel="点击展开"
      children={children}
    />
  );
}

/** `[list]` / `[list=1]`。`items` 已经由解析器按 `[*]` 切好。 */
export function ListBlock({
  node,
  renderItem,
}: {
  node: ListNode;
  renderItem: (index: number) => ReactNode;
}) {
  const styles = useStyles();
  return (
    <View style={styles.list}>
      {node.items.map((_item, index) => (
        <View key={index} style={styles.listRow}>
          <Text style={styles.listMarker}>{node.ordered ? `${index + 1}.` : '·'}</Text>
          <View style={styles.listContent}>{renderItem(index)}</View>
        </View>
      ))}
    </View>
  );
}

/**
 * `[table]`。简化排版见 `./table`:固定列宽 + 整表横向滚动,`rowspan` 忽略。
 *
 * 两处「不这么写就滚不动」的细节:
 * - `nestedScrollEnabled` —— Android 上嵌套滚动容器的手势要显式放行;
 * - `beginHorizontalDrag` —— 详情页的左右滑动翻页是在**捕获阶段**认领手势的,
 *   祖先先手,子孙抢不过。所以摸到表格时先把翻页手势按住,松手再放开。
 */
export function TableBlock({
  node,
  renderCell,
}: {
  node: TableNode;
  renderCell: (rowIndex: number, cellIndex: number) => ReactNode;
}) {
  const styles = useStyles();
  const columnCount = tableColumnCount(node.rows);

  return (
    <ScrollView
      horizontal
      nestedScrollEnabled
      showsHorizontalScrollIndicator
      style={styles.tableScroll}
      contentContainerStyle={styles.table}
      onTouchStart={beginHorizontalDrag}
      onTouchEnd={endHorizontalDrag}
      onTouchCancel={endHorizontalDrag}
    >
      <View>
        {node.rows.map((row, rowIndex) => (
          <View key={rowIndex} style={styles.tableRow}>
            {row.cells.map((cell, cellIndex) => (
              <View
                key={cellIndex}
                style={[styles.tableCell, { width: tableCellWidth(cell.colspan) }]}
              >
                {renderCell(rowIndex, cellIndex)}
              </View>
            ))}
            {Array.from({ length: tablePaddingCells(row, columnCount) }, (_value, index) => (
              <View key={`pad-${index}`} style={[styles.tableCell, { width: tableCellWidth(1) }]} />
            ))}
          </View>
        ))}
      </View>
    </ScrollView>
  );
}

/** 官方 `ubbcode.lesserNuke` 的三句提示语,按标签名末尾那位数字挑。 */
const PUNISHMENT_NOTICES = {
  post: '用户因此帖中的发言被处罚',
  topic: '用户在主题中被处罚',
  locked: '被锁定账号发布的内容无法查看',
} as const;

/** `[lessernuke]` 是版规处罚提示,内容默认收起;`[hip]` / `[item]` 只是普通的一块。 */
export function BoxBlock({ node, children }: { node: BoxNode; children: () => ReactNode }) {
  const styles = useStyles();

  if (node.variant !== 'lessernuke') {
    return <View style={styles.plainBox}>{children()}</View>;
  }

  return (
    <CollapsibleCard
      icon="warning"
      tone="danger"
      title={PUNISHMENT_NOTICES[node.punishment ?? 'post']}
      openLabel="点击查看"
      children={children}
    />
  );
}

/** `[align]` / `[l]` / `[r]`:横向对齐要同时作用在容器和文字上,只给一个都不够。 */
export function alignStyles(align: 'left' | 'center' | 'right'): {
  container: StyleProp<ViewStyle>;
  text: StyleProp<TextStyle>;
} {
  if (align === 'left') return { container: undefined, text: undefined };
  return {
    container: { alignItems: align === 'center' ? 'center' : 'flex-end' },
    text: { textAlign: align },
  };
}

const useStyles = createThemedStyles((theme) => ({
  list: {
    marginTop: theme.spacing.xs,
    gap: 2,
  },
  listRow: {
    flexDirection: 'row',
    gap: theme.spacing.sm,
  },
  listMarker: {
    ...theme.typography.body,
    color: theme.colors.meta,
    minWidth: 18,
    textAlign: 'right',
  },
  listContent: {
    flex: 1,
    minWidth: 0,
  },
  tableScroll: {
    marginTop: 11,
  },
  table: {
    borderTopWidth: 1,
    borderLeftWidth: 1,
    borderColor: theme.colors.divider,
    borderRadius: theme.radius.sm,
  },
  tableRow: {
    flexDirection: 'row',
  },
  tableCell: {
    paddingVertical: 7,
    paddingHorizontal: theme.spacing.sm,
    borderRightWidth: 1,
    borderBottomWidth: 1,
    borderColor: theme.colors.divider,
  },
  plainBox: {
    marginTop: theme.spacing.sm,
    paddingVertical: theme.spacing.sm,
    paddingHorizontal: theme.spacing.md,
    borderRadius: theme.radius.md,
    backgroundColor: theme.colors.surface2,
  },
}));

import { Fragment, type ReactNode } from 'react';
import { Linking, Text, View, type StyleProp, type TextStyle } from 'react-native';

import { attachmentUrl } from '@/core/api';
import type { BBCodeNode } from '@/core/bbcode';

import { createThemedStyles, useTheme, type Theme } from '../theme';
import { resolveBBColor, resolveBBSizeScale } from './colors';
import { ContentImage } from './content-image';
import { splitIntoSegments } from './segments';
import { Smiley } from './smiley';

/**
 * AST → 组件。本票只画 03 票节点清单里的**基础标签**:
 * 文字样式、颜色、字号、quote、url、img、表情。
 * collapse / table / list / dice / vote 这些进阶标签归 08 票,
 * 这里先按「不丢内容」的原则降级:有 children 的照常渲染 children,
 * 叶子节点渲染成一句灰色占位文本。
 *
 * 排版分两层:
 * - **行内**(`renderInline`)——能塞进同一个 `<Text>` 的东西,靠 RN 的 Text 嵌套继承样式;
 * - **块级**(`BlockNode`)——引用块、图片、分割线这类必须自己占一个 `<View>` 的。
 *
 * 块级节点不能嵌在 `<Text>` 里(Android 上会直接不显示),所以渲染前先用
 * `./segments` 把节点序列切成「行内段 / 块级节点」交替的序列,每个行内段包一个 `<Text>`。
 */

export interface BBCodeRenderOptions {
  /** 附件图片基址,来自 `read.php` 的 `__GLOBAL._ATTACH_BASE_VIEW`(每页都可能变) */
  readonly attachBase: string;
  /** 所在楼层的发帖时间,`[noimg]` 的相对路径要靠它补 `mon_YYYYMM/DD/` */
  readonly postedAt?: number;
  /** 点图片(25 票的大图查看器接进来) */
  readonly onOpenImage?: (uri: string) => void;
}

/** 进阶标签的占位文案(08 票接管后这张表就空了)。 */
function placeholderLabel(node: BBCodeNode): string | undefined {
  switch (node.type) {
    case 'dice':
      return `[骰子 ${node.expression}]`;
    case 'album':
      return '[相册]';
    case 'attach':
      return '[附件]';
    case 'flash':
      return node.media === 'audio' ? '[音频]' : '[视频]';
    default:
      return undefined;
  }
}

interface InlineProps {
  nodes: readonly BBCodeNode[];
  options: BBCodeRenderOptions;
  styles: ReturnType<typeof useStyles>;
  theme: Theme;
}

/** 行内节点 → `<Text>` 里的东西。返回值一定能安全地放进 `<Text>`。 */
function renderInline({ nodes, options, styles, theme }: InlineProps): ReactNode {
  return nodes.map((node, index) => {
    const key = `${node.type}-${index}`;
    const children = (child: readonly BBCodeNode[]) =>
      renderInline({ nodes: child, options, styles, theme });

    const wrap = (style: StyleProp<TextStyle>, child: readonly BBCodeNode[]) => (
      <Text key={key} style={style}>
        {children(child)}
      </Text>
    );

    switch (node.type) {
      case 'text':
        return <Fragment key={key}>{node.value}</Fragment>;
      case 'linebreak':
        return <Fragment key={key}>{'\n'}</Fragment>;
      case 'bold':
        return wrap(styles.bold, node.children);
      case 'italic':
        return wrap(styles.italic, node.children);
      case 'underline':
        return wrap(styles.underline, node.children);
      case 'strike':
        return wrap(styles.strike, node.children);
      case 'color': {
        const color = resolveBBColor(node.value);
        return wrap(color === undefined ? undefined : { color }, node.children);
      }
      case 'size': {
        const scale = resolveBBSizeScale(node.value);
        // 行高跟着一起放大,不然大字会被上下行压住
        return wrap(
          scale === undefined
            ? undefined
            : {
                fontSize: theme.typography.body.fontSize * scale,
                lineHeight: theme.typography.body.lineHeight * scale,
              },
          node.children,
        );
      }
      // 字体名基本是 Windows 字体,Android 上没有;按 03 票的约定只渲染内容
      case 'font':
        return <Fragment key={key}>{children(node.children)}</Fragment>;
      case 'link': {
        const label = node.children.length === 0 ? node.href : children(node.children);
        return (
          <Text
            key={key}
            style={styles.link}
            onPress={() => {
              void Linking.openURL(node.href);
            }}
          >
            {label}
          </Text>
        );
      }
      // 用户 / 主题 / 楼层的站内引用:14、26 票才有落点,先按链接样式显示文字
      case 'userRef':
        return wrap(styles.link, node.children.length === 0 ? [textNode(node.uid)] : node.children);
      case 'topicRef':
        return wrap(
          styles.link,
          node.children.length === 0 ? [textNode(`#${node.tid}`)] : node.children,
        );
      case 'floorRef':
        return wrap(
          styles.link,
          node.children.length === 0 ? [textNode(`#${node.pid}`)] : node.children,
        );
      case 'mention':
        return (
          <Text key={key} style={styles.link}>
            @{node.username}
          </Text>
        );
      case 'smiley':
        return <Smiley key={key} code={node.code} />;
      case 'code':
        return (
          <Text key={key} style={styles.code}>
            {node.value}
          </Text>
        );
      // 下面这些是 08 票的进阶标签。有 children 的一律照常渲染内容,
      // 只是暂时没有专属样式——宁可少一层框,也不能把用户写的字吞掉。
      case 'collapse':
      case 'align':
      case 'box':
        return <Fragment key={key}>{children(node.children)}</Fragment>;
      case 'list':
        return (
          <Fragment key={key}>
            {node.items.map((item, itemIndex) => (
              <Fragment key={itemIndex}>
                {'\n· '}
                {children(item)}
              </Fragment>
            ))}
          </Fragment>
        );
      case 'table':
        return (
          <Fragment key={key}>
            {node.rows.map((row, rowIndex) => (
              <Fragment key={rowIndex}>
                {rowIndex === 0 ? null : '\n'}
                {row.cells.map((cell, cellIndex) => (
                  <Fragment key={cellIndex}>
                    {cellIndex === 0 ? null : ' | '}
                    {children(cell.children)}
                  </Fragment>
                ))}
              </Fragment>
            ))}
          </Fragment>
        );
      default: {
        const label = placeholderLabel(node);
        return label === undefined ? null : (
          <Text key={key} style={styles.placeholder}>
            {label}
          </Text>
        );
      }
    }
  });
}

/** 造一个文本节点,给「标签没写内容、拿参数当内容显示」的几个分支用。 */
const textNode = (value: string): BBCodeNode => ({ type: 'text', value });

/**
 * 一段 AST。`style` 覆盖正文字号(引用块里的正文比楼层正文小一档)。
 */
export function BBCodeBody({
  nodes,
  options,
  style,
}: {
  nodes: readonly BBCodeNode[];
  options: BBCodeRenderOptions;
  style?: StyleProp<TextStyle>;
}) {
  const styles = useStyles();
  const theme = useTheme();
  const segments = splitIntoSegments(nodes);

  return (
    <>
      {segments.map((segment, index) => {
        if (segment.kind === 'inline') {
          return (
            <Text key={index} style={[styles.body, style]}>
              {renderInline({ nodes: segment.nodes, options, styles, theme })}
            </Text>
          );
        }
        return <BlockNode key={index} node={segment.node} options={options} style={style} />;
      })}
    </>
  );
}

function BlockNode({
  node,
  options,
  style,
}: {
  node: BBCodeNode;
  options: BBCodeRenderOptions;
  style?: StyleProp<TextStyle>;
}) {
  const styles = useStyles();

  switch (node.type) {
    case 'quote':
      return (
        <View style={styles.quote}>
          {/* 引用块里那句「Post by 谁 (时间)」是服务端塞在 BBCode 里的,
              原样渲染就够,不另外合成一行标题——合成的话作者名会重复出现两遍 */}
          <BBCodeBody nodes={node.children} options={options} style={styles.quoteText} />
        </View>
      );
    case 'image': {
      const uri = attachmentUrl(node, {
        base: options.attachBase,
        ...(options.postedAt === undefined ? {} : { postedAt: options.postedAt }),
      });
      return (
        <View style={styles.imageWrap}>
          <ContentImage
            uri={uri}
            {...(options.onOpenImage === undefined ? {} : { onPress: options.onOpenImage })}
          />
        </View>
      );
    }
    case 'divider':
      return <View style={styles.divider} />;
    case 'heading':
      return <BBCodeBody nodes={node.children} options={options} style={[style, styles.heading]} />;
    default:
      // 剩下的都是「裹着块级内容的行内标签」(见 splitIntoSegments):
      // 递归展开内容,并把这一层的文字样式往下带,行内部分的粗体/颜色/字号不丢。
      return <ContainerNode node={node} options={options} style={style} />;
  }
}

/**
 * 行内标签里裹了块级内容时的展开。
 *
 * `align` 还能保住对齐,`list`/`table` 这类进阶标签只保内容——它们的正式排版是 08 票的活。
 */
function ContainerNode({
  node,
  options,
  style,
}: {
  node: BBCodeNode;
  options: BBCodeRenderOptions;
  style?: StyleProp<TextStyle>;
}) {
  const styles = useStyles();
  const inherited: StyleProp<TextStyle> = [style, inlineStyleOf(node, styles)];

  if ('children' in node) {
    const align =
      node.type === 'align'
        ? node.align === 'center'
          ? styles.alignCenter
          : node.align === 'right'
            ? styles.alignRight
            : undefined
        : undefined;
    return (
      <View style={align}>
        <BBCodeBody nodes={node.children} options={options} style={inherited} />
      </View>
    );
  }
  if (node.type === 'list') {
    return (
      <View>
        {node.items.map((item, index) => (
          <BBCodeBody key={index} nodes={item} options={options} style={inherited} />
        ))}
      </View>
    );
  }
  if (node.type === 'table') {
    return (
      <View>
        {node.rows.map((row, rowIndex) =>
          row.cells.map((cell, cellIndex) => (
            <BBCodeBody
              key={`${rowIndex}-${cellIndex}`}
              nodes={cell.children}
              options={options}
              style={inherited}
            />
          )),
        )}
      </View>
    );
  }
  return null;
}

/** 一个行内容器节点自己贡献的文字样式(往块级内容里递的那份)。 */
function inlineStyleOf(
  node: BBCodeNode,
  styles: ReturnType<typeof useStyles>,
): StyleProp<TextStyle> {
  switch (node.type) {
    case 'bold':
      return styles.bold;
    case 'italic':
      return styles.italic;
    case 'underline':
      return styles.underline;
    case 'strike':
      return styles.strike;
    case 'color': {
      const color = resolveBBColor(node.value);
      return color === undefined ? undefined : { color };
    }
    default:
      return undefined;
  }
}

const useStyles = createThemedStyles((theme) => ({
  body: {
    ...theme.typography.body,
    color: theme.colors.fg,
  },
  bold: {
    fontWeight: '700',
  },
  italic: {
    fontStyle: 'italic',
  },
  underline: {
    textDecorationLine: 'underline',
  },
  strike: {
    textDecorationLine: 'line-through',
  },
  link: {
    color: theme.colors.link,
    textDecorationLine: 'underline',
  },
  code: {
    fontFamily: 'monospace',
    color: theme.colors.fg2,
  },
  placeholder: {
    color: theme.colors.meta,
  },
  heading: {
    fontWeight: '700',
  },
  alignCenter: {
    alignItems: 'center',
  },
  alignRight: {
    alignItems: 'flex-end',
  },
  /** 设计稿:引用块 11/13 内距、圆角 12、底色 quote、左侧 3 的 track 竖条 */
  quote: {
    marginTop: 11,
    paddingVertical: 11,
    paddingHorizontal: 13,
    borderRadius: theme.radius.md,
    backgroundColor: theme.colors.quote,
    borderLeftWidth: 3,
    borderLeftColor: theme.colors.track,
    gap: 6,
  },
  quoteText: {
    ...theme.typography.quoteBody,
    color: theme.colors.fg2,
  },
  imageWrap: {
    marginTop: 11,
  },
  divider: {
    marginVertical: theme.spacing.md,
    height: 1,
    backgroundColor: theme.colors.divider,
  },
}));

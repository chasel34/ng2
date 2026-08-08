import { Fragment, type ReactNode } from 'react';
import { Linking, Text, View, type StyleProp, type TextStyle } from 'react-native';

import { attachmentUrl, thumbnailUrl } from '@/core/api';
import type { BBCodeNode } from '@/core/bbcode';

import { createThemedStyles, useTheme, type Theme } from '../theme';
import { alignStyles, BoxBlock, CollapseBlock, ListBlock, TableBlock } from './blocks';
import { resolveBBColor, resolveBBSizeScale } from './colors';
import { ContentImage } from './content-image';
import { AlbumCard, AttachCard, DiceCard, MediaCard } from './media';
import { attachOptions, type BBCodeRenderOptions } from './options';
import { splitIntoSegments } from './segments';
import { Smiley } from './smiley';

/**
 * AST → 组件。03 票节点清单里的每一种 `type` 在这里都有落点,不再有占位文本。
 *
 * 排版分两层:
 * - **行内**(`renderInline`)——能塞进同一个 `<Text>` 的东西,靠 RN 的 Text 嵌套继承样式;
 * - **块级**(`BlockNode`)——引用块、图片、折叠块、表格这类必须自己占一个 `<View>` 的。
 *
 * 块级节点不能嵌在 `<Text>` 里(Android 上会直接不显示),所以渲染前先用
 * `./segments` 把节点序列切成「行内段 / 块级节点」交替的序列,每个行内段包一个 `<Text>`。
 * 哪些算块级也归 `./segments` 定,那张表和这里的 `BlockNode` 分支一一对应。
 */

export type { BBCodeRenderOptions } from './options';

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
        // 行高跟着一起放大,不然大字会被上下行压住。基准是用户设的正文字号(22 票),
        // 不是 token 默认值——否则把正文调大之后,`[size=150%]` 反而比正文小
        return wrap(
          scale === undefined
            ? undefined
            : {
                fontSize: (options.bodyFontSize ?? theme.typography.body.fontSize) * scale,
                lineHeight: (options.bodyLineHeight ?? theme.typography.body.lineHeight) * scale,
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
      default:
        // 剩下的全是块级类型(`./segments` 的 BLOCK_TYPES),按理走不到这里——
        // 真走到了说明两张表不同步,那也宁可把内容原样吐出来,不能吞字。
        return 'children' in node ? <Fragment key={key}>{children(node.children)}</Fragment> : null;
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
  const body = (nodes: readonly BBCodeNode[], extra?: StyleProp<TextStyle>) => (
    <BBCodeBody nodes={nodes} options={options} style={extra === undefined ? style : [style, extra]} />
  );

  switch (node.type) {
    case 'quote':
      return (
        <View style={styles.quote}>
          {/* 引用块里那句「Post by 谁 (时间)」是服务端塞在 BBCode 里的,
              原样渲染就够,不另外合成一行标题——合成的话作者名会重复出现两遍 */}
          {body(node.children, styles.quoteText)}
        </View>
      );
    case 'image': {
      const uri = attachmentUrl(node, attachOptions(options));
      return (
        <View style={styles.imageWrap}>
          <ContentImage
            uri={uri}
            thumbnailUri={thumbnailUrl(uri, options.attachBase)}
            {...(options.onOpenImage === undefined ? {} : { onPress: options.onOpenImage })}
          />
        </View>
      );
    }
    case 'divider':
      return <View style={styles.divider} />;
    case 'heading':
      return <View style={styles.heading}>{body(node.children, styles.headingText)}</View>;
    case 'align': {
      const align = alignStyles(node.align);
      return <View style={align.container}>{body(node.children, align.text)}</View>;
    }
    case 'collapse':
      return <CollapseBlock node={node}>{() => body(node.children)}</CollapseBlock>;
    case 'box':
      return <BoxBlock node={node}>{() => body(node.children)}</BoxBlock>;
    case 'list':
      return <ListBlock node={node} renderItem={(index) => body(node.items[index] ?? [])} />;
    case 'table':
      return (
        <TableBlock
          node={node}
          renderCell={(rowIndex, cellIndex) =>
            body(node.rows[rowIndex]?.cells[cellIndex]?.children ?? [], styles.tableText)
          }
        />
      );
    case 'dice': {
      const outcome = options.dice?.get(node);
      // 点数要靠楼层的 authorId/tid/pid 才算得出来,调用方没给就退回显示表达式
      return outcome === undefined ? (
        <Text style={[styles.body, style, styles.placeholder]}>[骰子 {node.expression}]</Text>
      ) : (
        <DiceCard outcome={outcome} />
      );
    }
    case 'flash':
      return <MediaCard node={node} options={options} />;
    case 'attach':
      return <AttachCard node={node} options={options} />;
    case 'album':
      return <AlbumCard value={node.value} options={options} />;
    default:
      // 剩下的都是「裹着块级内容的行内标签」(见 splitIntoSegments):
      // 递归展开内容,并把这一层的文字样式往下带,行内部分的粗体/颜色/字号不丢。
      return 'children' in node ? (
        <View>{body(node.children, inlineStyleOf(node, styles))}</View>
      ) : null;
  }
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
  /** `[h]` 与 `===标题===`:网页版是一条带下划线的小标题 */
  heading: {
    marginTop: 11,
    paddingBottom: theme.spacing.xs,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  headingText: {
    ...theme.typography.section,
    fontWeight: '700',
    color: theme.colors.fg,
  },
  /** 表格里的字比正文小一档,不然固定列宽装不下几个字 */
  tableText: {
    ...theme.typography.quoteBody,
    color: theme.colors.fg,
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

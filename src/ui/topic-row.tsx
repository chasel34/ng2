import { memo } from 'react';
import { Pressable, Text, View } from 'react-native';

import type { Topic } from '@/core/api';
import type { TitleStyle } from '@/core/local';

import { useListTitleStyle } from './appearance';
import { ICON_FONT_FAMILY } from './icon';
import { ICON_GLYPHS } from './icons.generated';
import { createThemedStyles, useTheme, type Theme } from './theme';

/** 不断行空格(设计稿标题行里的 `&nbsp;`)。写成常量,免得被当成普通空格改掉。 */
const NBSP = ' ';

/**
 * 昵称截断(原 maxWidth:130 像素截断的近似):9 个全角字符 ≈ 117px。
 * 合并后的右侧 Text 只能整体加 numberOfLines,像素截断会把回复数一起省略掉,
 * 所以名字这段在 JS 里截。
 */
function clipName(name: string): string {
  return name.length > 9 ? `${name.slice(0, 9)}…` : name;
}

/** 彩色标题的掩码 → 文字样式(设计稿标题行:颜色 + 粗/斜/下划线)。 */
function titleTextStyle(style: TitleStyle, theme: Theme) {
  return {
    color: style.color === undefined ? theme.colors.fg : theme.titleColors[style.color],
    ...(style.bold ? { fontWeight: '600' as const } : {}),
    ...(style.italic ? { fontStyle: 'italic' as const } : {}),
    ...(style.underline ? { textDecorationLine: 'underline' as const } : {}),
  };
}

export interface TopicRowProps {
  topic: Topic;
  onPress: (topic: Topic) => void;
  /**
   * 二级列表(设计稿 simple-list:热帖/精华区)的时间文案。传了就切到那套行样式:
   * 标题降到 16 档(token 表「列表主题标题」),最后回复人的位置换成时间(meta 色)。
   */
  time?: string;
}

/**
 * 主题列表的一行,两行布局(设计稿 `isList`):
 *
 * - 标题行:彩色标题 + `[锁定]` + 附件 `+` + 来源子版块 `[…]`,四段流式排在一起
 * - 信息行:作者(匿名已还原) ——推到右边—— 最后回复人 · 回复数
 *
 * 合集与版块镜像行(`shortcut`)点开的是另一个版块的主题列表,不是讨论串。
 * 合集按设计稿加粗;镜像行不额外加粗——它的粗体本来就写在服务端下发的掩码里。
 *
 * memo:翻页追加时列表屏的 loading state 一翻转就整屏重渲染,几十行全量重画
 * 在 120Hz 下是肉眼可见的一顿(M4 滚动排查)。调用方的 onPress 都要 useCallback,
 * 不然 props 恒不等,memo 白包。
 */
export const TopicRow = memo(function TopicRow({ topic, onPress, time }: TopicRowProps) {
  const styles = useStyles();
  const theme = useTheme();
  // 「帖子列表字体大小」(22 票)。二级列表(simple-list)那一档不跟着改——
  // 它是「我的收藏 / 我的主题」这类固定 16 的窄行,设置项管的是主题列表屏
  const listTitle = useListTitleStyle();

  const titleStyle = titleTextStyle(topic.titleStyle, theme);

  return (
    <Pressable
      onPress={() => onPress(topic)}
      android_ripple={{ color: theme.colors.divider }}
      style={styles.row}
    >
      {/* 标题样式直接合并进外层 Text,常见情形(无锁定/附件/子版块标记)整行只有
          一个文本节点;标记 span 按需追加,样式覆盖关系与嵌套时一致 */}
      <Text
        style={[
          time === undefined ? listTitle : styles.titleLineSimple,
          titleStyle,
          topic.isCollection && styles.titleCollection,
        ]}
      >
        {topic.subject}
        {/* 标记与标题之间用不断行空格(设计稿的 &nbsp;),窄屏不会把 [锁定] 甩到下一行 */}
        {topic.locked && <Text style={styles.locked}>{`${NBSP}[锁定]`}</Text>}
        {topic.hasAttachment && <Text style={styles.attachment}>{`${NBSP}+`}</Text>}
        {topic.parent !== undefined && (
          <Text style={styles.tag}>{`${NBSP}[${topic.parent.name}]`}</Text>
        )}
      </Text>

      {/* meta 行:图标作为字形 span 内联进 Text,不再是独立的 Icon 视图。
          嵌套 Text 在 Android 上是同一个原生 TextView 里的 span——每行少挂 2 个
          原生视图。快速甩动时 FlashList 的挂载突发是 120Hz 掉队列的直接原因
          (2026-08-15 帧流水线排查),行越轻突发越短。 */}
      <View style={styles.metaLine}>
        <Text style={styles.author} numberOfLines={1}>
          <Text style={styles.metaIcon}>{`${ICON_GLYPHS.person} `}</Text>
          {topic.author}
        </Text>
        {/* 右侧合并成一个 Text:回收重绑时每行少 diff 一个原生视图(第四轮排查,
            重绑帧的成本直接决定拖拽期掉帧率)。昵称在 JS 侧截断,不能靠
            numberOfLines 的省略号——那会连图标和回复数一起吃掉 */}
        <Text style={time === undefined ? styles.lastPoster : styles.time} numberOfLines={1}>
          {clipName(time ?? topic.lastPoster ?? '')}
          <Text style={styles.metaIconSmall}>{`  ${ICON_GLYPHS.chat_bubble} `}</Text>
          <Text style={styles.replies}>{topic.replies}</Text>
        </Text>
      </View>
    </Pressable>
  );
});

const useStyles = createThemedStyles((theme) => ({
  // 设计稿:14/16/12 的行内边距 + 1px 分隔线
  row: {
    paddingTop: theme.spacing.row,
    paddingHorizontal: theme.spacing.lg,
    paddingBottom: theme.spacing.md,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  // 二级列表(simple-list)的标题是 16 档,比主题列表页的 17 小一号
  titleLineSimple: {
    ...theme.typography.listTitle,
  },
  titleCollection: {
    fontWeight: '600',
  },
  // 标记 span 会继承外层标题的彩色样式(粗/斜/下划线),这里逐项写死抵消——
  // 旧结构里它们是彩色 span 的兄弟节点,本来就不吃标题样式
  locked: {
    color: theme.colors.danger,
    fontWeight: '600',
    fontStyle: 'normal',
    textDecorationLine: 'none',
  },
  attachment: {
    color: theme.colors.accent,
    fontWeight: '700',
    fontStyle: 'normal',
    textDecorationLine: 'none',
  },
  tag: {
    color: theme.colors.tag,
    fontWeight: '400',
    fontStyle: 'normal',
    textDecorationLine: 'none',
  },
  metaLine: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    marginTop: 9,
  },
  author: {
    ...theme.typography.listMeta,
    color: theme.colors.link,
    // 原 Icon(15) + gap(6) + 名字(118)的总宽,内联字形后收进同一个 Text
    maxWidth: 139,
  },
  // 昵称的宽度控制在 clipName 里(JS 截断),这里不能再限 maxWidth——
  // 合并后的 Text 还装着图标和回复数,像素截断会把它们剪掉
  lastPoster: {
    ...theme.typography.listMeta,
    color: theme.colors.link,
    marginLeft: 'auto',
  },
  // simple-list 的 when 槽:同一位置,但用 meta 色(设计稿 color:var(--meta))
  time: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
    marginLeft: 'auto',
  },
  // 图标字形 span:垂直对齐靠字号贴近文字字号,不再有独立视图的 alignItems
  metaIcon: {
    fontFamily: ICON_FONT_FAMILY,
    fontSize: 15,
    color: theme.colors.meta,
  },
  metaIconSmall: {
    fontFamily: ICON_FONT_FAMILY,
    fontSize: 14,
    color: theme.colors.meta,
  },
  // span 里只有文字样式生效(minWidth/textAlign 这类布局属性在 span 上无效)
  replies: {
    ...theme.typography.listMeta,
    color: theme.colors.link,
  },
}));

import { Pressable, Text, View } from 'react-native';

import type { Topic } from '@/core/api';
import type { TitleStyle } from '@/core/local';

import { Icon } from './icon';
import { createThemedStyles, useTheme, type Theme } from './theme';

/** 不断行空格(设计稿标题行里的 `&nbsp;`)。写成常量,免得被当成普通空格改掉。 */
const NBSP = ' ';

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
}

/**
 * 主题列表的一行,两行布局(设计稿 `isList`):
 *
 * - 标题行:彩色标题 + `[锁定]` + 附件 `+` + 来源子版块 `[…]`,四段流式排在一起
 * - 信息行:作者(匿名已还原) ——推到右边—— 最后回复人 · 回复数
 *
 * 合集与版块镜像行(`shortcut`)点开的是另一个版块的主题列表,不是讨论串。
 * 合集按设计稿加粗;镜像行不额外加粗——它的粗体本来就写在服务端下发的掩码里。
 */
export function TopicRow({ topic, onPress }: TopicRowProps) {
  const styles = useStyles();
  const theme = useTheme();

  const titleStyle = titleTextStyle(topic.titleStyle, theme);

  return (
    <Pressable
      onPress={() => onPress(topic)}
      android_ripple={{ color: theme.colors.divider }}
      style={styles.row}
    >
      <Text style={styles.titleLine}>
        <Text style={[titleStyle, topic.isCollection && styles.titleCollection]}>
          {topic.subject}
        </Text>
        {/* 标记与标题之间用不断行空格(设计稿的 &nbsp;),窄屏不会把 [锁定] 甩到下一行 */}
        {topic.locked && <Text style={styles.locked}>{`${NBSP}[锁定]`}</Text>}
        {topic.hasAttachment && <Text style={styles.attachment}>{`${NBSP}+`}</Text>}
        {topic.parent !== undefined && (
          <Text style={styles.tag}>{`${NBSP}[${topic.parent.name}]`}</Text>
        )}
      </Text>

      <View style={styles.metaLine}>
        <Icon name="person" size={15} color={theme.colors.meta} />
        <Text style={styles.author} numberOfLines={1}>
          {topic.author}
        </Text>
        <Text style={styles.lastPoster} numberOfLines={1}>
          {topic.lastPoster ?? ''}
        </Text>
        <Icon name="chat_bubble" size={14} color={theme.colors.meta} />
        <Text style={styles.replies}>{topic.replies}</Text>
      </View>
    </Pressable>
  );
}

const useStyles = createThemedStyles((theme) => ({
  // 设计稿:14/16/12 的行内边距 + 1px 分隔线
  row: {
    paddingTop: theme.spacing.row,
    paddingHorizontal: theme.spacing.lg,
    paddingBottom: theme.spacing.md,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  titleLine: {
    ...theme.typography.topicTitle,
  },
  titleCollection: {
    fontWeight: '600',
  },
  locked: {
    color: theme.colors.danger,
    fontWeight: '600',
  },
  attachment: {
    color: theme.colors.accent,
    fontWeight: '700',
  },
  tag: {
    color: theme.colors.tag,
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
    maxWidth: 120,
  },
  lastPoster: {
    ...theme.typography.listMeta,
    color: theme.colors.link,
    marginLeft: 'auto',
    maxWidth: 130,
  },
  replies: {
    ...theme.typography.listMeta,
    color: theme.colors.link,
    minWidth: 22,
    textAlign: 'right',
  },
}));

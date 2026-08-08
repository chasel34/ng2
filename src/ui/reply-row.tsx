import { Pressable, Text, View } from 'react-native';

import type { Topic } from '@/core/api';

import { plainTextOf } from './bbcode';
import { Icon } from './icon';
import { createThemedStyles, useTheme } from './theme';

/**
 * 「我的回复」的一行(设计稿 simple-list 的两行布局,信息行换成回复摘要)。
 *
 * 和 `TopicRow` 分开而不是加个开关:这里的主角是**回复**,标题只是它落在哪个帖子里,
 * 两者的视觉层级正好反过来。搜索票(15)搜正文时也是这个形状,直接复用。
 */
export function ReplyRow({
  topic,
  onPress,
  time,
}: {
  topic: Topic;
  onPress: (topic: Topic) => void;
  time: string;
}) {
  const styles = useStyles();
  const theme = useTheme();

  // 服务端拒给内容的占位行(帖子过期/无权限):subject 就是拒绝理由,点进去也是空的
  const denied = topic.denied;
  const excerpt = topic.reply === undefined ? '' : plainTextOf(topic.reply.content);

  return (
    <Pressable
      onPress={() => onPress(topic)}
      android_ripple={{ color: theme.colors.divider }}
      style={styles.row}
    >
      <Text style={styles.excerpt} numberOfLines={2}>
        {excerpt === '' ? '(空回复)' : excerpt}
      </Text>
      <View style={styles.metaLine}>
        <Icon
          name={denied ? 'block' : 'article'}
          size={14}
          color={denied ? theme.colors.meta : theme.colors.tag}
        />
        <Text style={[styles.subject, denied && styles.subjectDenied]} numberOfLines={1}>
          {topic.subject}
        </Text>
        <Text style={styles.time}>{time}</Text>
      </View>
    </Pressable>
  );
}

const useStyles = createThemedStyles((theme) => ({
  row: {
    paddingTop: theme.spacing.row,
    paddingHorizontal: theme.spacing.lg,
    paddingBottom: theme.spacing.md,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  excerpt: {
    ...theme.typography.listTitle,
    color: theme.colors.fg,
  },
  metaLine: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    marginTop: 9,
  },
  subject: {
    ...theme.typography.listMeta,
    color: theme.colors.link,
    flex: 1,
  },
  subjectDenied: {
    color: theme.colors.meta,
  },
  time: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
  },
}));

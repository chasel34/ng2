import { Linking, Pressable, Text, View } from 'react-native';

import { attachmentUrl } from '@/core/api';
import type { AttachNode, FlashNode } from '@/core/bbcode';
import { formatDiceTerms, type DiceOutcome } from '@/core/local';

import { CollapsibleCard } from '../collapsible-card';
import { Icon } from '../icon';
import { createThemedStyles, useTheme } from '../theme';
import { albumImageUrls } from './album';
import { ContentImage } from './content-image';
import { attachOptions, type BBCodeRenderOptions } from './options';

/**
 * 媒体与特殊标签的卡片:`[dice]` `[flash]` `[attach]` `[album]`。
 *
 * `[flash]` 不内联播放(ADR-0001):Expo 里没有能吃 NGA 那些容器格式的播放器,
 * 与其做一个半残的内置播放器,不如给一张卡片、点了交给系统——手机上装了播放器的
 * 体验比任何内置方案都好,没装的话浏览器兜底。
 */

/** `[dice]` 的结果卡。排版照网页版那张小表:`ROLL : 表达式 = 展开 = 合计`。 */
export function DiceCard({ outcome }: { outcome: DiceOutcome }) {
  const styles = useStyles();
  const expanded = formatDiceTerms(outcome.terms);

  return (
    <View style={styles.dice}>
      <Text style={styles.diceLabel}>ROLL</Text>
      <Text style={styles.diceText}>
        {outcome.expression}
        {expanded === '' ? null : <Text style={styles.diceMuted}> = {expanded}</Text>}
        {outcome.sum === undefined ? (
          // 网页版此时显示 OUT OF LIMIT / ERROR：一次最多 10 颗、面数最多 100000
          <Text style={styles.diceError}> = 超出骰子上限</Text>
        ) : (
          <Text style={styles.diceSum}> = {outcome.sum}</Text>
        )}
      </Text>
    </View>
  );
}

const MEDIA_LABELS: Record<FlashNode['media'], string> = {
  video: '视频',
  audio: '音频',
  flash: '动画',
};

/** `[flash]` / `[flash=video]` / `[flash=audio]`:点了交给系统播放器或浏览器。 */
export function MediaCard({
  node,
  options,
}: {
  node: FlashNode;
  options: BBCodeRenderOptions;
}) {
  const styles = useStyles();
  const theme = useTheme();
  const uri = attachmentUrl(node, attachOptions(options));
  const label = MEDIA_LABELS[node.media];

  return (
    <Pressable
      style={styles.media}
      onPress={() => void Linking.openURL(uri)}
      accessibilityRole="button"
      accessibilityLabel={`播放${label}`}
    >
      <View style={styles.mediaIcon}>
        <Icon name="open_in_browser" size={20} color={theme.colors.onPrimary} />
      </View>
      <View style={styles.mediaText}>
        <Text style={styles.mediaTitle}>{label}</Text>
        <Text style={styles.mediaSub} numberOfLines={1}>
          {fileNameOf(uri)}
        </Text>
      </View>
      <Icon name="north_east" size={16} color={theme.colors.meta} />
    </Pressable>
  );
}

/** `[attach]`:附件本体,和楼层附件区一样点了外跳下载。 */
export function AttachCard({
  node,
  options,
}: {
  node: AttachNode;
  options: BBCodeRenderOptions;
}) {
  const styles = useStyles();
  const theme = useTheme();
  const uri = attachmentUrl(node, attachOptions(options));

  return (
    <Pressable style={styles.attach} onPress={() => void Linking.openURL(uri)}>
      <Icon name="download" size={16} color={theme.colors.link} />
      <Text style={styles.attachName} numberOfLines={1}>
        {fileNameOf(uri)}
      </Text>
    </Pressable>
  );
}

/**
 * `[album]`:默认收起成一条「共 N 张」,展开后按正文图片的样式竖排。
 *
 * 相册动辄十几张原图,一进楼就全拉等于把流量烧光——和附件宫格同一个理由。
 */
export function AlbumCard({
  value,
  options,
}: {
  value: string;
  options: BBCodeRenderOptions;
}) {
  const styles = useStyles();
  const urls = albumImageUrls(value, attachOptions(options));

  if (urls.length === 0) return null;

  return (
    <CollapsibleCard icon="image" title={`相册 · 共 ${urls.length} 张图片`} openLabel="点击查看">
      {() => (
        <View style={styles.albumBody}>
          {urls.map((uri) => (
            <ContentImage
              key={uri}
              uri={uri}
              {...(options.onOpenImage === undefined ? {} : { onPress: options.onOpenImage })}
            />
          ))}
        </View>
      )}
    </CollapsibleCard>
  );
}

/** 卡片副标题用的文件名。查询串和路径都去掉,剩下的就是人能认的那截。 */
function fileNameOf(uri: string): string {
  const path = uri.split(/[?#]/)[0] ?? uri;
  return path.slice(path.lastIndexOf('/') + 1) || uri;
}

const useStyles = createThemedStyles((theme) => ({
  dice: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.sm,
    marginTop: 11,
    paddingVertical: theme.spacing.sm,
    paddingHorizontal: theme.spacing.md,
    borderRadius: theme.radius.md,
    backgroundColor: theme.colors.quote,
    borderLeftWidth: 3,
    borderLeftColor: theme.colors.accent,
  },
  diceLabel: {
    ...theme.typography.caption,
    color: theme.colors.accent,
  },
  diceText: {
    ...theme.typography.note,
    color: theme.colors.fg2,
    flex: 1,
  },
  diceMuted: {
    color: theme.colors.meta,
  },
  diceSum: {
    color: theme.colors.fg,
    fontWeight: '700',
  },
  diceError: {
    color: theme.colors.danger,
  },
  media: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.md,
    marginTop: 11,
    padding: theme.spacing.md,
    borderRadius: theme.radius.md,
    backgroundColor: theme.colors.surface2,
  },
  mediaIcon: {
    width: 38,
    height: 38,
    borderRadius: 19,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: theme.colors.primary,
  },
  mediaText: {
    flex: 1,
    minWidth: 0,
  },
  mediaTitle: {
    ...theme.typography.notice,
    fontWeight: '600',
    color: theme.colors.fg,
  },
  mediaSub: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
  },
  attach: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.sm,
    marginTop: 7,
    paddingVertical: theme.spacing.sm,
    paddingHorizontal: theme.spacing.md,
    borderRadius: theme.radius.md,
    backgroundColor: theme.colors.surface2,
  },
  attachName: {
    ...theme.typography.listMeta,
    color: theme.colors.link,
    flex: 1,
  },
  albumBody: {
    gap: theme.spacing.sm,
    padding: theme.spacing.sm,
  },
}));

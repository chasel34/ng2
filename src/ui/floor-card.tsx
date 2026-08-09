import { Image } from 'expo-image';
import { useRouter } from 'expo-router';
import { memo, useMemo, useState } from 'react';
import { Linking, Pressable, Text, View } from 'react-native';

import type {
  Floor,
  FloorAttachment,
  FloorClient,
  FloorUser,
  RecommendAction,
  RecommendMark,
} from '@/core/api';
import { parseBBCode } from '@/core/bbcode';
import { formatReputation, parseVote, resolveDice } from '@/core/local';

import { useSettings } from '@/store/settings';

import { useBodyTextStyle } from './appearance';
import { Avatar } from './avatar';
import { BBCodeBody, plainTextOf, type BBCodeRenderOptions } from './bbcode';
import { Icon, type IconName } from './icon';
import { useImagesUnlocked } from './network';
import { createThemedStyles, useTheme } from './theme';
import { showNotAvailable } from './toast';
import { VoteBlock } from './vote';

/** 设计稿:附件宫格三列、格间距 6、方格圆角 10。 */
const ATTACH_COLUMNS = 3;
const ATTACH_GAP = 6;

/** 发帖设备图标(设计稿 `f.plat`)。认不出设备时用通用的 devices。 */
const CLIENT_ICONS: Record<FloorClient, IconName> = {
  android: 'android',
  ios: 'phone_iphone',
  other: 'devices',
};

/**
 * 画一个楼层要的、楼层本身之外的东西——全都来自它所在的那一页
 * (`TopicDetail`),所以打包一起传:楼层卡片、贴条区、热门回复区都要同一份。
 */
export interface FloorContext {
  /** 所在主题的 tid。骰子种子与投票的分组语法都要用到(CONTEXT.md「骰子」) */
  tid: number;
  /** 整页的用户表,按 `floor.authorKey` 查 */
  users: Readonly<Record<string, FloorUser>>;
  /** 附件图片基址,来自本页响应的 `__GLOBAL._ATTACH_BASE_VIEW` */
  attachBase: string;
  onOpenImage?: (uri: string) => void;
  /** 本会话的赞踩标记(12 票),按赞踩 pid 查(主楼是 0);没接线时卡片只读展示 */
  recommendOf?: (floor: Floor) => RecommendMark | undefined;
  /** 点了赞/踩钮。登录判断、乐观更新与回滚都在调用方 */
  onRecommend?: (floor: Floor, action: RecommendAction) => void;
  /** 打开楼层菜单(菜单钮或长按整卡) */
  onOpenMenu?: (floor: Floor) => void;
  /**
   * 回复链(26 票):这一楼可追溯的链深(含它自己),按 quote 索引算。
   * < 2(链上只有它自己)时引用块不出「查看对话链」入口。
   */
  chainDepthOf?: (floor: Floor) => number;
  /** 点了引用块里的「查看对话链(N 层)」 */
  onOpenChain?: (floor: Floor) => void;
}

export interface FloorCardProps {
  floor: Floor;
  context: FloorContext;
}

/**
 * 一个楼层卡片(设计稿 isArticle 的 `floors` 行)。
 *
 * 三种子形态都在这儿:引用块(渲染器画)、附件折叠宫格、贴条区。
 */
export const FloorCard = memo(function FloorCard({ floor, context }: FloorCardProps) {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();
  const bodyStyle = useBodyTextStyle();
  const showSignature = useSettings((state) => state.settings.showSignature);
  const nodes = useMemo(() => parseBBCode(floor.content), [floor.content]);
  const user = context.users[floor.authorKey];

  // 匿名楼层没有真身 uid(CONTEXT.md「匿名还原」),点了也没有资料可看
  const openProfile =
    user === undefined || user.anonymous || user.uid === undefined
      ? undefined
      : () =>
          router.push({
            pathname: '/user/[uid]',
            params: { uid: String(user.uid), name: user.name },
          });

  // 赞踩即时变色计数(12 票):状态与增量都来自调用方的本会话标记
  const mark = context.recommendOf?.(floor);
  const liked = mark?.state === 'liked';
  const disliked = mark?.state === 'disliked';
  const likeColor = liked ? theme.colors.primary : theme.colors.meta;

  // 骰子的点数是「整楼一条数列」算出来的,所以按楼层算一次,渲染器只负责查表
  const dice = useMemo(
    () => resolveDice(nodes, { authorId: floor.authorId, tid: context.tid, pid: floor.pid }),
    [nodes, floor.authorId, context.tid, floor.pid],
  );

  const vote = useMemo(
    () => (floor.vote === undefined ? undefined : parseVote(floor.vote, { tid: context.tid })),
    [floor.vote, context.tid],
  );

  const renderOptions = {
    attachBase: context.attachBase,
    // 发帖时间是 [noimg] 相对路径补 mon_YYYYMM/DD/ 的依据,所以按楼层给
    postedAt: floor.postedAt,
    dice,
    // 「帖子内字体大小 / 行高」(22 票):正文本身靠 style 覆盖,
    // 这两个值另外传一份是给 [size=] 当相对基准用的
    bodyFontSize: bodyStyle.fontSize,
    bodyLineHeight: bodyStyle.lineHeight,
    ...(context.onOpenImage === undefined ? {} : { onOpenImage: context.onOpenImage }),
  };

  // 「查看对话链(N 层)」(26 票):只接在正文的引用块上——签名档也走同一个渲染器,
  // 但签名里的引用块跟这一楼的回复关系无关,不给它链入口
  const chainDepth = context.chainDepthOf?.(floor) ?? 0;
  const bodyOptions = {
    ...renderOptions,
    ...(chainDepth >= 2 && context.onOpenChain !== undefined
      ? { quoteChain: { depth: chainDepth, onOpen: () => context.onOpenChain?.(floor) } }
      : {}),
  };

  return (
    // 长按整卡也能出楼层菜单(ticket 12:「长按或菜单钮」)
    <Pressable
      style={styles.card}
      onLongPress={context.onOpenMenu === undefined ? undefined : () => context.onOpenMenu?.(floor)}
    >
      <View style={styles.header}>
        <Pressable
          onPress={openProfile}
          disabled={openProfile === undefined}
          accessibilityLabel={`${user?.name ?? '用户'}的资料`}
        >
          <Avatar user={user} />
        </Pressable>
        <View style={styles.headerText}>
          <View style={styles.nameRow}>
            <Text style={styles.name} numberOfLines={1} onPress={openProfile}>
              {user?.name ?? '未知用户'}
              <UserBadges user={user} isStarter={floor.isStarter} />
            </Text>
            <Text style={styles.time}>
              {floor.postedAtText}
              {/* alterinfo 非空 = 被编辑过(API 文档 §3);编辑记录本身不展开 */}
              {floor.edited && <Text style={styles.edited}> · 已编辑</Text>}
            </Text>
          </View>
          <View style={styles.metaRow}>
            <Text style={styles.meta}>级别: {user?.level ?? '—'}</Text>
            <Text style={styles.meta}>威望: {formatReputation(user?.reputation ?? 0)}</Text>
            <Text style={styles.meta}>发帖: {user?.postCount ?? 0}</Text>
            <View style={styles.floorNo}>
              <Icon name={CLIENT_ICONS[floor.client]} size={13} color={theme.colors.meta} />
              <Text style={styles.meta}>[{floor.lou} 楼]</Text>
            </View>
          </View>
        </View>
      </View>

      {/* 回复楼层也可以自带标题;主楼的标题就是主题标题,顶栏已经有了 */}
      {floor.lou > 0 && floor.subject !== undefined && (
        <Text style={styles.subject}>{floor.subject}</Text>
      )}

      <View style={styles.body}>
        <BBCodeBody nodes={nodes} options={bodyOptions} style={bodyStyle} />
      </View>

      {/* 签名档(22 票的「显示签名档」)。签名也是 BBCode,但它是「附在正文后面的一小块」,
          所以压一档字号、上面加一条分隔线,不和正文混在一起 */}
      {showSignature && user?.signature !== undefined && user.signature !== '' && (
        <Signature signature={user.signature} options={renderOptions} />
      )}

      {/* 投票是楼层字段不是 BBCode(API 文档 §3),所以画在正文之后而不是渲染器里 */}
      {vote !== undefined && <VoteBlock vote={vote} />}

      {floor.attachments.length > 0 && (
        <AttachmentGrid
          attachments={floor.attachments}
          {...(context.onOpenImage === undefined ? {} : { onOpenImage: context.onOpenImage })}
        />
      )}

      {/* 赞踩与楼层菜单(ticket 12)。回复是 v1 排除项(spec §1),入口保留占位。
          赞数 = 服务端 score + 本会话增量;已赞时图标与数字染主题色(设计稿 f.likeColor) */}
      <View style={styles.actions}>
        <Pressable
          style={styles.likeButton}
          onPress={
            context.onRecommend === undefined
              ? showNotAvailable
              : () => context.onRecommend?.(floor, 'like')
          }
          accessibilityLabel="点赞"
        >
          <Icon name="thumb_up" size={19} color={likeColor} />
          <Text style={[styles.likeCount, { color: likeColor }]}>
            {floor.score + (mark?.scoreDelta ?? 0)}
          </Text>
        </Pressable>
        <Pressable
          style={styles.action}
          onPress={
            context.onRecommend === undefined
              ? showNotAvailable
              : () => context.onRecommend?.(floor, 'dislike')
          }
          accessibilityLabel="点踩"
        >
          <Icon
            name="thumb_down"
            size={19}
            color={disliked ? theme.colors.primary : theme.colors.meta}
          />
        </Pressable>
        <Pressable style={styles.action} onPress={showNotAvailable} accessibilityLabel="回复">
          <Icon name="reply" size={20} color={theme.colors.meta} />
        </Pressable>
        <Pressable
          style={styles.action}
          onPress={
            context.onOpenMenu === undefined ? showNotAvailable : () => context.onOpenMenu?.(floor)
          }
          accessibilityLabel="楼层菜单"
        >
          <Icon name="more_vert" size={19} color={theme.colors.meta} />
        </Pressable>
      </View>

      {floor.notes.length > 0 && <NoteList notes={floor.notes} users={context.users} />}
    </Pressable>
  );
});

/**
 * 用户状态标注(功能文档 §2.3「禁言/楼主/匿名/拉黑」)。
 * 拉黑属 21 票的屏蔽规则,这里只有前三种。
 */
function UserBadges({ user, isStarter }: { user: FloorUser | undefined; isStarter: boolean }) {
  const styles = useStyles();
  return (
    <>
      {isStarter && <Text style={styles.badgeStarter}>(楼主)</Text>}
      {user?.anonymous === true && <Text style={styles.badgeAnonymous}>(匿名)</Text>}
      {user?.muted === true && <Text style={styles.badgeDanger}>(禁言)</Text>}
      {user?.nuked === true && <Text style={styles.badgeDanger}>(已封禁)</Text>}
    </>
  );
}

/**
 * 签名档。用引用块那一档字号(14/1.6),颜色压到次级——签名再长也不该抢正文。
 * 内容是 BBCode(常带图与折叠),所以还是走正文渲染器。
 */
function Signature({
  signature,
  options,
}: {
  signature: string;
  options: BBCodeRenderOptions;
}) {
  const styles = useStyles();
  const nodes = useMemo(() => parseBBCode(signature), [signature]);
  return (
    <View style={styles.signature}>
      <BBCodeBody nodes={nodes} options={options} style={styles.signatureText} />
    </View>
  );
}

/** 贴条区(设计稿:surface2 底、圆角 12 的一块,每条一行「谁:内容」)。 */
function NoteList({
  notes,
  users,
}: {
  notes: readonly Floor[];
  users: Readonly<Record<string, FloorUser>>;
}) {
  const styles = useStyles();
  return (
    <View style={styles.notes}>
      {notes.map((note) => (
        <Text key={note.pid} style={styles.noteText}>
          <Text style={styles.noteAuthor}>{users[note.authorKey]?.name ?? '匿名'}</Text>
          {'：'}
          {/* 贴条正文里常带一整段 `[b]Reply to …[/b]` 引用头,连同图片一起展开会把
              这一小块撑爆,所以压成纯文本一行(引用关系在 26 票的回复链里看) */}
          {plainTextOf(note.content)}
        </Text>
      ))}
    </View>
  );
}

/**
 * 附件宫格。默认折叠成设计稿那条「点击显示附件(N)」,展开后是三列方格。
 *
 * 默认折叠不只是照设计稿:附件常常是几张几 MB 的原图,一进帖子全量拉图
 * 既费流量又慢。「仅 Wi-Fi 下加载图片」(22 票)关掉自动展开的那条路——
 * 折叠条上多一句「移动网络」,点了照样能看。
 */
function AttachmentGrid({
  attachments,
  onOpenImage,
}: {
  attachments: readonly FloorAttachment[];
  onOpenImage?: (uri: string) => void;
}) {
  const styles = useStyles();
  const theme = useTheme();
  const [open, setOpen] = useState(false);
  const [gridWidth, setGridWidth] = useState(0);
  const unlocked = useImagesUnlocked();

  if (!open) {
    return (
      <Pressable style={styles.attachToggle} onPress={() => setOpen(true)}>
        <Icon name={unlocked ? 'image' : 'signal_cellular_alt'} size={18} color={theme.colors.fg2} />
        <Text style={styles.attachToggleLabel}>
          {unlocked
            ? `点击显示附件(${attachments.length})`
            : `移动网络 · 点击显示附件(${attachments.length})`}
        </Text>
      </Pressable>
    );
  }

  // RN 没有 calc(),等分列宽只能自己算:量出可用宽度再扣掉列间距
  const cellSize =
    gridWidth === 0 ? 0 : (gridWidth - ATTACH_GAP * (ATTACH_COLUMNS - 1)) / ATTACH_COLUMNS;

  // 只有图片进宫格。附件里也会有压缩包、种子这类东西,当图片渲染就是一格加载失败,
  // 所以另起一行按「文件名 · 大小」列出来。
  const images = attachments.filter((attachment) => attachment.kind === 'img');
  const files = attachments.filter((attachment) => attachment.kind !== 'img');

  return (
    <View style={styles.attachOpen}>
      <View
        style={styles.attachGrid}
        onLayout={(event) => setGridWidth(event.nativeEvent.layout.width)}
      >
        {images.map((attachment) => (
          <Pressable
            key={attachment.url}
            style={[styles.attachCell, { width: cellSize, height: cellSize }]}
            onPress={() => onOpenImage?.(attachment.url)}
          >
            {/* 宫格里用缩略图,点开大图才拉原图 */}
            <Image
              source={{ uri: attachment.thumbnailUrl ?? attachment.url }}
              style={styles.attachImage}
              contentFit="cover"
              cachePolicy="disk"
              transition={120}
              recyclingKey={attachment.url}
              accessibilityIgnoresInvertColors
            />
          </Pressable>
        ))}
      </View>
      {files.map((attachment) => (
        <Pressable
          key={attachment.url}
          style={styles.attachFile}
          onPress={() => void Linking.openURL(attachment.url)}
        >
          <Icon name="download" size={16} color={theme.colors.link} />
          <Text style={styles.attachFileName} numberOfLines={1}>
            {attachment.name ?? attachment.url}
          </Text>
          {attachment.sizeKb !== undefined && (
            <Text style={styles.meta}>{formatSize(attachment.sizeKb)}</Text>
          )}
        </Pressable>
      ))}
      <Pressable onPress={() => setOpen(false)}>
        <Text style={styles.attachCollapse}>收起附件</Text>
      </Pressable>
    </View>
  );
}

/** 服务端给的 `size` 单位是 KB。 */
const formatSize = (sizeKb: number): string =>
  sizeKb >= 1024 ? `${(sizeKb / 1024).toFixed(1)} MB` : `${sizeKb} KB`;

const useStyles = createThemedStyles((theme) => ({
  /** 设计稿:楼层内距 14/16/6,底部一条 divider */
  card: {
    paddingTop: theme.spacing.row,
    paddingHorizontal: theme.spacing.lg,
    paddingBottom: 6,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  header: {
    flexDirection: 'row',
    gap: 11,
  },
  headerText: {
    flex: 1,
    minWidth: 0,
  },
  nameRow: {
    flexDirection: 'row',
    alignItems: 'baseline',
    gap: theme.spacing.sm,
  },
  name: {
    ...theme.typography.floorName,
    color: theme.colors.primary,
    flexShrink: 1,
  },
  time: {
    ...theme.typography.floorTime,
    color: theme.colors.meta,
    marginLeft: 'auto',
  },
  metaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    marginTop: theme.spacing.xs,
  },
  meta: {
    ...theme.typography.meta,
    color: theme.colors.meta,
  },
  floorNo: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.xs,
    marginLeft: 'auto',
  },
  badgeStarter: {
    color: theme.colors.accent,
  },
  badgeAnonymous: {
    color: theme.colors.meta,
  },
  badgeDanger: {
    color: theme.colors.danger,
  },
  subject: {
    ...theme.typography.floorName,
    color: theme.colors.fg,
    marginTop: 11,
  },
  edited: {
    color: theme.colors.meta,
  },
  body: {
    marginTop: 11,
  },
  actions: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-end',
    gap: 2,
    marginTop: theme.spacing.sm,
  },
  likeButton: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    height: 40,
    paddingHorizontal: theme.spacing.sm,
    borderRadius: 10,
  },
  likeCount: {
    ...theme.typography.floorLike,
    color: theme.colors.meta,
  },
  action: {
    width: 38,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 10,
  },
  signature: {
    marginTop: 10,
    paddingTop: theme.spacing.sm,
    borderTopWidth: 1,
    borderTopColor: theme.colors.divider,
  },
  signatureText: {
    ...theme.typography.quoteBody,
    color: theme.colors.meta,
  },
  notes: {
    marginBottom: theme.spacing.md,
    paddingVertical: 10,
    paddingHorizontal: theme.spacing.md,
    borderRadius: theme.radius.md,
    backgroundColor: theme.colors.surface2,
  },
  noteText: {
    ...theme.typography.note,
    color: theme.colors.fg2,
    paddingVertical: 2,
  },
  noteAuthor: {
    color: theme.colors.link,
    fontWeight: '700',
  },
  attachToggle: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 7,
    height: 42,
    marginTop: theme.spacing.md,
    borderRadius: theme.radius.md,
    backgroundColor: theme.colors.surface2,
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: theme.colors.track,
  },
  attachToggleLabel: {
    ...theme.typography.notice,
    fontWeight: '600',
    color: theme.colors.fg2,
  },
  attachOpen: {
    marginTop: theme.spacing.md,
  },
  attachGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: ATTACH_GAP,
  },
  attachCell: {
    // 宽高由 AttachmentGrid 量出来后传进来(三列等分)
    borderRadius: 10,
    overflow: 'hidden',
    backgroundColor: theme.colors.surface2,
  },
  attachImage: {
    width: '100%',
    height: '100%',
  },
  attachFile: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.sm,
    marginTop: 7,
    paddingVertical: theme.spacing.sm,
    paddingHorizontal: theme.spacing.md,
    borderRadius: theme.radius.md,
    backgroundColor: theme.colors.surface2,
  },
  attachFileName: {
    ...theme.typography.listMeta,
    color: theme.colors.link,
    flex: 1,
  },
  attachCollapse: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
    textAlign: 'center',
    marginTop: 7,
  },
}));

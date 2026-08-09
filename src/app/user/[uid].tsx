import { Image } from 'expo-image';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useMemo, useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';

import { ATTACH_BASE_FALLBACK, type AdminForum, type UserProfile } from '@/core/api';
import { parseBBCode, unescapeNgaText } from '@/core/bbcode';
import { formatMoney, formatReputation } from '@/core/local';
import { useAccounts } from '@/store/accounts';
import { useUpdateSignature, useUserProfile } from '@/store/user-profile';
import { avatarColorFor } from '@/ui/avatar';
import { BBCodeBody } from '@/ui/bbcode';
import { Icon } from '@/ui/icon';
import { initialOf } from '@/ui/initial';
import { InputDialog } from '@/ui/input-dialog';
import { showSnackbar } from '@/ui/snackbar';
import { LoadFailedNotice } from '@/ui/error-screen';
import { LoadingState } from '@/ui/state-view';
import { createThemedStyles, useTheme, type Theme } from '@/ui/theme';
import { dateText } from '@/ui/time-text';
import { showNotAvailable } from '@/ui/toast';
import { TopBar, TopBarButton, TopBarTitle, topBarSpacer } from '@/ui/top-bar';

/** 设计稿:banner 118 高、头像 62 见方带 2px 白描边。 */
const BANNER_HEIGHT = 118;
const BANNER_AVATAR = 62;

/** 设计稿 banner 的 `repeating-linear-gradient(135deg, …0 12px, …12px 24px)`。 */
const STRIPE_WIDTH = 12;
const STRIPE_PITCH = 34;
const STRIPE_COUNT = 20;

/** 设计稿:声望条 96 宽、6 高。 */
const REPUTATION_BAR_WIDTH = 96;

/**
 * 用户资料页(设计稿 isProfile 屏)。
 *
 * 从楼层头像/用户名、「我的主题/回复」列表进来,路由参数只有 uid;
 * 名字也带一份,免得资料还没回来时顶栏是空的。
 */
export default function UserProfileScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();

  const { uid, name } = useLocalSearchParams<{ uid: string; name?: string }>();
  const userId = Number(uid);

  const { data, error, isPending, refetch } = useUserProfile(userId);

  // 签名只能改自己的(服务端认 cookie 里的账号,别人的改不动),入口也只对自己出现
  const currentUid = useAccounts((state) => state.currentUid);
  const isMine = currentUid !== null && Number(currentUid) === userId;
  const [signOpen, setSignOpen] = useState(false);
  const saveSignature = useUpdateSignature(userId);

  /**
   * 存的是原文,转义交给 core(提交时转、读回来时解)。存完重拉资料——
   * 页面上那段签名要以服务端存下来的为准,不能拿输入框里的字冒充。
   */
  const confirmSignature = (text: string) => {
    setSignOpen(false);
    void saveSignature(text).then(
      () => showSnackbar('签名已保存'),
      (cause: unknown) =>
        showSnackbar(cause instanceof Error ? cause.message : '签名没能保存到服务端'),
    );
  };

  const body = () => {
    // 这两块挂在 ScrollView 的内容里,撑不出 flex:1 的高度,所以走 inline 档
    if (isPending) return <LoadingState variant="inline" />;
    if (data === undefined) {
      return <LoadFailedNotice error={error} onRetry={() => void refetch()} />;
    }
    return (
      <ProfileBody
        profile={data}
        {...(isMine ? { onEditSignature: () => setSignOpen(true) } : {})}
      />
    );
  };

  return (
    <View style={styles.root}>
      <TopBar paddingHorizontal={4}>
        <TopBarButton
          icon="arrow_back"
          box={46}
          size={24}
          onPress={() => router.back()}
          accessibilityLabel="返回"
        />
        <TopBarTitle variant="sub">用户资料</TopBarTitle>
        {/* 短消息不在 v1(spec §1、CONTEXT.md「短消息」),入口按设计稿留着 */}
        <TopBarButton
          icon="sms"
          size={22}
          onPress={showNotAvailable}
          accessibilityLabel="发短消息"
          style={topBarSpacer}
        />
        <TopBarButton
          icon="more_vert"
          size={22}
          onPress={showNotAvailable}
          accessibilityLabel="更多"
        />
      </TopBar>

      <ScrollView
        style={styles.scroll}
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}
      >
        <Banner
          uid={userId}
          name={data?.name ?? name ?? `UID ${uid}`}
          {...(data?.avatarUrl === undefined ? {} : { avatarUrl: data.avatarUrl })}
        />
        {body()}
      </ScrollView>

      {/* 签名可以换行,所以是多行输入;写的是原文,BBCode 标签照打 */}
      <InputDialog
        open={signOpen}
        title="修改签名"
        hint="支持 BBCode 与 emoji;留空即清除签名"
        confirmLabel="保存"
        multiline
        initialValue={unescapeNgaText(data?.signature ?? '')}
        onCancel={() => setSignOpen(false)}
        onConfirm={confirmSignature}
      />
    </View>
  );
}

/** banner:斜纹底 + 头像 + 用户名 + UID(设计稿 isProfile 顶部那块)。 */
function Banner({ uid, name, avatarUrl }: { uid: number; name: string; avatarUrl?: string }) {
  const styles = useStyles();
  const [failed, setFailed] = useState(false);

  return (
    <View style={styles.banner}>
      {/* 设计稿的 135° 斜条纹。RN 没有 repeating-linear-gradient,拿等距的旋转窄条铺 */}
      <View style={styles.stripes} pointerEvents="none">
        {Array.from({ length: STRIPE_COUNT }, (_, index) => (
          <View key={index} style={[styles.stripe, { left: index * STRIPE_PITCH }]} />
        ))}
      </View>

      {avatarUrl === undefined || failed ? (
        <View
          style={[styles.bannerAvatar, { backgroundColor: avatarColorFor(String(uid)) }]}
        >
          <Text style={styles.bannerInitial} allowFontScaling={false}>
            {initialOf(name)}
          </Text>
        </View>
      ) : (
        <Image
          source={{ uri: avatarUrl }}
          style={styles.bannerAvatar}
          contentFit="cover"
          cachePolicy="disk"
          transition={120}
          onError={() => setFailed(true)}
          accessibilityIgnoresInvertColors
        />
      )}

      <View style={styles.bannerText}>
        <Text style={styles.bannerName} numberOfLines={1}>
          {name}
        </Text>
        <Text style={styles.bannerUid}>用户 ID：{uid}</Text>
      </View>
    </View>
  );
}

/** 设计稿基础信息卡:两列八格,状态一格带颜色。 */
interface BasicField {
  key: string;
  label: string;
  value: string;
  color?: string;
}

function basicFields(profile: UserProfile, theme: Theme): BasicField[] {
  const status = {
    active: { text: '已激活', color: theme.titleColors.green },
    muted: { text: '禁言中', color: theme.colors.danger },
    nuked: { text: '已封禁(NUKED)', color: theme.colors.danger },
  }[profile.status];

  return [
    { key: 'email', label: '邮箱', value: profile.email ?? 'N/A' },
    { key: 'phone', label: 'Tel', value: profile.phone ?? 'N/A' },
    { key: 'group', label: '用户组', value: profile.group ?? 'N/A' },
    { key: 'posts', label: '发帖数', value: String(profile.postCount) },
    // 金钱是铜币总数,显示成「金.银.铜」(API 文档 §11.1)
    { key: 'money', label: '金钱', value: formatMoney(profile.money) },
    // 设计稿的八格里没有威望,但它是资料页该有的一项(功能文档 §用户资料页),
    // 补在金钱后面凑满一行;值已按 rvrc ÷ 10 换算过
    { key: 'reputation', label: '威望', value: formatReputation(profile.reputation) },
    { key: 'status', label: '状态', value: status.text, color: status.color },
    {
      key: 'regdate',
      label: '注册日期',
      value: profile.registeredAt === undefined ? 'N/A' : dateText(profile.registeredAt),
    },
    { key: 'ip', label: '属地', value: profile.ipLocation ?? 'N/A' },
  ];
}

function ProfileBody({
  profile,
  onEditSignature,
}: {
  profile: UserProfile;
  /** 只有自己的资料页才给,给了就在签名卡上出现编辑入口 */
  onEditSignature?: () => void;
}) {
  const styles = useStyles();
  const theme = useTheme();

  const fields = basicFields(profile, theme);
  const signatureNodes = useMemo(
    () => (profile.signature === undefined ? [] : parseBBCode(profile.signature)),
    [profile.signature],
  );

  // 条形图按本页最大绝对值归一化:声望的绝对刻度没有上限,
  // 拿本人各版的最大值当满格才看得出彼此的高低
  const maxReputation = profile.reputations.reduce(
    (max, entry) => Math.max(max, Math.abs(entry.value)),
    0,
  );

  return (
    <View style={styles.cards}>
      <View style={styles.card}>
        <Text style={styles.cardTitle}>:: 基础信息 ::</Text>
        <View style={styles.grid}>
          {fields.map((field, index) => (
            <Text
              key={field.key}
              style={[styles.gridCell, index % 2 === 1 && styles.gridCellRight]}
              numberOfLines={1}
            >
              {field.label}：
              <Text style={{ color: field.color ?? theme.colors.fg }}>{field.value}</Text>
            </Text>
          ))}
        </View>
        {/* 禁言有到期时间时把它说清楚,只标一个「禁言中」看不出还剩多久 */}
        {profile.mutedUntil !== undefined && (
          <Text style={styles.mutedNote}>禁言至 {dateText(profile.mutedUntil)}</Text>
        )}
      </View>

      {/* 自己的资料页即使还没有签名也要出这张卡,不然没有地方点「编辑」 */}
      {(profile.signature !== undefined || onEditSignature !== undefined) && (
        <View style={styles.card}>
          <View style={styles.cardHeader}>
            <Text style={[styles.cardTitle, styles.cardHeaderTitle]}>:: 签名 ::</Text>
            {onEditSignature !== undefined && (
              <Pressable
                style={styles.cardAction}
                onPress={onEditSignature}
                hitSlop={8}
                accessibilityLabel="修改签名"
              >
                <Icon name="edit" size={16} color={theme.colors.primary} />
                <Text style={styles.cardActionLabel}>编辑</Text>
              </Pressable>
            )}
          </View>
          {profile.signature === undefined ? (
            <Text style={styles.cardCaption}>还没有签名,点「编辑」写一段。</Text>
          ) : (
            <View style={styles.signature}>
              {/* 签名是 BBCode,和楼层正文同一个渲染器;签名里没有附件,基址走兜底。
                  设计稿的签名比楼层正文小一档(13.5 · 1.7,fg-2 色),覆盖掉默认的 body 档 */}
              <BBCodeBody
                nodes={signatureNodes}
                options={{ attachBase: ATTACH_BASE_FALLBACK }}
                style={styles.signatureText}
              />
            </View>
          )}
        </View>
      )}

      {profile.adminForums.length > 0 && (
        <View style={styles.card}>
          <Text style={styles.cardTitle}>:: 管理权限 ::</Text>
          <Text style={styles.cardCaption}>在以下版面担任版主</Text>
          <View style={styles.chips}>
            {profile.adminForums.map((forum: AdminForum) => (
              <Text key={forum.fid} style={styles.chip}>
                {forum.name}
              </Text>
            ))}
          </View>
        </View>
      )}

      {profile.reputations.length > 0 && (
        <View style={styles.card}>
          <Text style={styles.cardTitle}>:: 声望 ::</Text>
          <Text style={styles.cardCaption}>表示与 论坛/某版面/某用户 的关系</Text>
          {profile.reputations.map((entry) => (
            <View key={entry.fid} style={styles.repRow}>
              <Text style={styles.repBoard} numberOfLines={1}>
                {entry.name}
              </Text>
              <View style={styles.repTrack}>
                <View
                  style={[
                    styles.repFill,
                    {
                      width:
                        maxReputation === 0
                          ? 0
                          : (Math.abs(entry.value) / maxReputation) * REPUTATION_BAR_WIDTH,
                      backgroundColor:
                        entry.value < 0 ? theme.colors.danger : theme.colors.primary,
                    },
                  ]}
                />
              </View>
              <Text
                style={[
                  styles.repValue,
                  { color: entry.value < 0 ? theme.colors.danger : theme.colors.primary },
                ]}
              >
                {entry.value > 0 ? `+${entry.value}` : String(entry.value)}
              </Text>
            </View>
          ))}
        </View>
      )}
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  root: {
    flex: 1,
    backgroundColor: theme.colors.bg,
  },
  scroll: {
    flex: 1,
  },
  scrollContent: {
    paddingBottom: 24,
  },
  banner: {
    height: BANNER_HEIGHT,
    flexDirection: 'row',
    alignItems: 'flex-end',
    gap: theme.spacing.row,
    paddingHorizontal: theme.spacing.page,
    paddingBottom: theme.spacing.row,
    backgroundColor: theme.colors.primary,
    overflow: 'hidden',
  },
  stripes: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
  },
  stripe: {
    position: 'absolute',
    top: -BANNER_HEIGHT,
    width: STRIPE_WIDTH,
    height: BANNER_HEIGHT * 3,
    backgroundColor: theme.colors.primaryDark,
    transform: [{ rotate: '45deg' }],
  },
  bannerAvatar: {
    width: BANNER_AVATAR,
    height: BANNER_AVATAR,
    borderRadius: BANNER_AVATAR / 2,
    borderWidth: 2,
    borderColor: 'rgba(255, 255, 255, 0.75)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  bannerInitial: {
    fontSize: 20,
    fontWeight: '700',
    color: theme.colors.onPrimary,
  },
  bannerText: {
    flex: 1,
    minWidth: 0,
    paddingBottom: theme.spacing.xs,
  },
  bannerName: {
    fontSize: 20,
    fontWeight: '600',
    color: theme.colors.onPrimary,
  },
  bannerUid: {
    ...theme.typography.bannerMeta,
    color: theme.colors.onPrimary,
    opacity: 0.85,
    marginTop: theme.spacing.xs,
  },
  // 底留白交给 scrollContent 的 24,这里不再叠一层
  cards: {
    paddingTop: theme.spacing.md,
    paddingHorizontal: theme.spacing.md,
    gap: theme.spacing.md,
  },
  card: {
    padding: theme.spacing.lg,
    borderRadius: theme.radius.lg,
    backgroundColor: theme.colors.surface,
    boxShadow: theme.shadows.elevation1,
  },
  cardTitle: {
    fontSize: 15.5,
    fontWeight: '600',
    color: theme.colors.accent,
    marginBottom: theme.spacing.md,
  },
  cardHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.md,
  },
  cardHeaderTitle: {
    flex: 1,
  },
  // 卡片标题行右侧的次要动作,按设计稿的 primary 文字按钮延伸
  cardAction: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    paddingVertical: 2,
    paddingLeft: theme.spacing.sm,
    marginBottom: theme.spacing.md,
  },
  cardActionLabel: {
    ...theme.typography.listMeta,
    fontWeight: '600',
    color: theme.colors.primary,
  },
  cardCaption: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
    marginTop: -theme.spacing.xs,
    marginBottom: 10,
  },
  // 设计稿是两列网格,行距 9、列距 12。列距不能写 columnGap:格子是 50% 宽的,
  // 加了 gap 就撑破一行,所以拆成两边各半个内距
  grid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    rowGap: 9,
  },
  gridCell: {
    width: '50%',
    paddingRight: theme.spacing.md / 2,
    fontSize: 13,
    color: theme.colors.fg2,
  },
  gridCellRight: {
    paddingRight: 0,
    paddingLeft: theme.spacing.md / 2,
    textAlign: 'right',
  },
  mutedNote: {
    ...theme.typography.note,
    color: theme.colors.danger,
    marginTop: 10,
  },
  signature: {
    paddingVertical: 11,
    paddingHorizontal: theme.spacing.md,
    borderWidth: 1.5,
    borderColor: theme.colors.track,
    borderRadius: theme.radius.xs,
  },
  signatureText: {
    ...theme.typography.signature,
    color: theme.colors.fg2,
  },
  chips: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 7,
  },
  chip: {
    paddingVertical: 6,
    paddingHorizontal: 11,
    borderRadius: theme.radius.xs,
    backgroundColor: theme.colors.primaryContainer,
    color: theme.colors.primary,
    fontSize: 12.5,
    fontWeight: '600',
  },
  repRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 11,
    paddingVertical: theme.spacing.sm,
  },
  repBoard: {
    flex: 1,
    fontSize: 13.5,
    color: theme.colors.fg,
  },
  repTrack: {
    width: REPUTATION_BAR_WIDTH,
    height: 6,
    borderRadius: 3,
    backgroundColor: theme.colors.track,
    overflow: 'hidden',
  },
  repFill: {
    height: '100%',
  },
  repValue: {
    fontSize: 13,
    fontWeight: '600',
    minWidth: 34,
    textAlign: 'right',
  },
}));

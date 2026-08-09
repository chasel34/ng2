import { Pressable, Text, View } from 'react-native';

import { NgaError, describeFetchFailure, diagnosticSummary, type FetchFailureCopy } from '@/core/net';

import { Icon } from './icon';
import { createThemedStyles, useTheme } from './theme';
import { monoFontFamily } from './tokens';

/**
 * 把 TanStack Query 手里那个 `unknown` 翻成错误页的两行文案。
 *
 * 不是 NgaError 的（渲染期异常、别的库抛的)也要有话说,所以兜一个 unknown 档;
 * 关键是**别把 `error.message` 直接摆到屏幕上**——反封锁链末端抛的是
 * `fetch failed: java.io.IOException: …` 这种给开发者看的东西(M3 验收缺陷 3)。
 */
export function loadFailureCopy(error: unknown): FetchFailureCopy {
  return describeFetchFailure(
    error instanceof NgaError
      ? error
      : { kind: 'unknown', message: error instanceof Error ? error.message : '这一页拉不下来' },
  );
}

export interface LoadFailedProps {
  /** 反封锁链最后抛出来的那个错误(TanStack Query 给的是 `Error | null`) */
  error: unknown;
  onRetry: () => void;
  /** 「用网页版打开」。19 票起指向站内的网页兜底页(`/web`),不再开系统浏览器 */
  onOpenWeb: () => void;
  onRelogin: () => void;
}

/**
 * 「加载失败」页(设计稿 isError 屏)。
 *
 * 反封锁链(ADR-0002)把格式 × 域名的组合、换账号、Web 反解都试遍还是不行时落到这里:
 * 说清楚服务端返回了什么 + 重试 / 用网页版打开 / 重新登录三个出路,
 * 底下那行是已经写进本地日志的诊断摘要(22 号票的「导出诊断日志」拿全量)。
 *
 * 它是屏内的一块而不是一个路由:设计稿里顶栏与页码条仍在,失败的只是内容区。
 */
export function LoadFailed({ error, onRetry, onOpenWeb, onRelogin }: LoadFailedProps) {
  const styles = useStyles();
  const theme = useTheme();

  const failure = loadFailureCopy(error);
  const summary =
    error instanceof NgaError && error.diagnostic !== undefined
      ? diagnosticSummary(error.diagnostic)
      : undefined;

  return (
    <View style={styles.root}>
      <View style={styles.iconBox}>
        <Icon name="cloud_off" size={34} color={theme.colors.meta} />
      </View>
      <Text style={styles.title}>这一页没能加载出来</Text>
      <Text style={styles.body}>
        <Text style={styles.headline}>{failure.headline}</Text>
        {'\n'}
        {failure.hint}
      </Text>

      <View style={styles.actions}>
        <Pressable style={styles.primaryAction} onPress={onRetry}>
          <Icon name="refresh" size={20} color={theme.colors.onPrimary} />
          <Text style={styles.primaryLabel}>重试</Text>
        </Pressable>
        <Pressable style={styles.secondaryAction} onPress={onOpenWeb}>
          <Icon name="public" size={20} color={theme.colors.primary} />
          <Text style={styles.secondaryLabel}>用网页版打开</Text>
        </Pressable>
        <Pressable style={styles.tertiaryAction} onPress={onRelogin}>
          <Text style={styles.tertiaryLabel}>重新登录账号</Text>
        </Pressable>
      </View>

      {summary !== undefined && (
        <View style={styles.diagnostic}>
          <Text style={styles.diagnosticText}>
            诊断信息已保存到本地日志:<Text style={styles.diagnosticMono}>{summary}</Text>
          </Text>
        </View>
      )}
    </View>
  );
}

export interface LoadFailedNoticeProps {
  error: unknown;
  onRetry: () => void;
}

/**
 * 「加载失败」的轻量形态:图标 + 同一套文案 + 重试。
 *
 * 给列表屏用(首页两个 tab、以及以后别的列表):那儿只有「重试」一个出路,
 * 摆不下整宽三动作,但文案必须和详情页错误页同一口径——不能一处说
 * 「连不上服务器」,另一处把 `java.io.IOException` 摊在用户脸上。
 */
export function LoadFailedNotice({ error, onRetry }: LoadFailedNoticeProps) {
  const styles = useStyles();
  const theme = useTheme();
  const failure = loadFailureCopy(error);

  return (
    <View style={styles.notice}>
      <Icon name="cloud_off" size={34} color={theme.colors.meta} />
      <Text style={styles.noticeHeadline}>{failure.headline}</Text>
      <Text style={styles.noticeHint}>{failure.hint}</Text>
      <Pressable style={styles.noticeRetry} onPress={onRetry}>
        <Icon name="refresh" size={18} color={theme.colors.onPrimary} />
        <Text style={styles.noticeRetryLabel}>重试</Text>
      </Pressable>
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  /** 设计稿 isError:内容区居中,内距 30 26 */
  root: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: theme.colors.bg,
    paddingVertical: 30,
    paddingHorizontal: 26,
  },
  iconBox: {
    width: 72,
    height: 72,
    borderRadius: theme.radius.dialog,
    backgroundColor: theme.colors.surface2,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: theme.spacing.xl,
  },
  title: {
    ...theme.typography.errorTitle,
    color: theme.colors.fg,
  },
  body: {
    ...theme.typography.errorBody,
    color: theme.colors.fg2,
    textAlign: 'center',
    marginTop: 10,
  },
  /** 设计稿把状态码那半句加粗并用等宽字 */
  headline: {
    fontWeight: '700',
    fontFamily: monoFontFamily,
  },
  actions: {
    alignSelf: 'stretch',
    gap: 10,
    marginTop: 26,
  },
  primaryAction: {
    height: 48,
    borderRadius: theme.radius.button,
    backgroundColor: theme.colors.primary,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: theme.spacing.sm,
  },
  primaryLabel: {
    ...theme.typography.errorAction,
    color: theme.colors.onPrimary,
  },
  secondaryAction: {
    height: 48,
    borderRadius: theme.radius.button,
    borderWidth: 1.5,
    borderColor: theme.colors.primary,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: theme.spacing.sm,
  },
  secondaryLabel: {
    ...theme.typography.errorAction,
    color: theme.colors.primary,
  },
  tertiaryAction: {
    height: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  tertiaryLabel: {
    ...theme.typography.errorBody,
    color: theme.colors.fg2,
  },
  diagnostic: {
    marginTop: 22,
    paddingVertical: theme.spacing.md,
    paddingHorizontal: theme.spacing.row,
    borderRadius: theme.radius.md,
    backgroundColor: theme.colors.surface2,
  },
  diagnosticText: {
    ...theme.typography.diagnostic,
    color: theme.colors.meta,
  },
  diagnosticMono: {
    fontFamily: monoFontFamily,
  },
  /** 轻量形态:没有整宽按钮那一列,所以内距按列表空态那套(纵 56)给 */
  notice: {
    alignItems: 'center',
    gap: theme.spacing.sm,
    paddingVertical: 56,
    paddingHorizontal: theme.spacing.xl,
  },
  noticeHeadline: {
    ...theme.typography.errorBody,
    fontWeight: '600',
    color: theme.colors.fg,
    textAlign: 'center',
    marginTop: theme.spacing.xs,
  },
  noticeHint: {
    ...theme.typography.notice,
    color: theme.colors.fg2,
    textAlign: 'center',
  },
  noticeRetry: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    height: 40,
    paddingHorizontal: theme.spacing.xl,
    borderRadius: theme.radius.full,
    backgroundColor: theme.colors.primary,
    marginTop: theme.spacing.xs,
  },
  noticeRetryLabel: {
    ...theme.typography.drawerItem,
    fontWeight: '600',
    color: theme.colors.onPrimary,
  },
}));

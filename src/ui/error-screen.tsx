import { Pressable, Text, View } from 'react-native';

import { NgaError, describeFetchFailure, diagnosticSummary } from '@/core/net';

import { Icon } from './icon';
import { createThemedStyles, useTheme } from './theme';
import { monoFontFamily } from './tokens';

export interface LoadFailedProps {
  /** 反封锁链最后抛出来的那个错误(TanStack Query 给的是 `Error | null`) */
  error: unknown;
  onRetry: () => void;
  /** 「用网页版打开」。19 号票会把它换成站内网页兜底页 */
  onOpenWeb: () => void;
  onRelogin: () => void;
}

/**
 * 「加载失败」页(设计稿 isError 屏)。
 *
 * 反封锁链(ADR-0002)把格式 × 域名的组合、换账号都试遍还是不行时落到这里:
 * 说清楚服务端返回了什么 + 重试 / 用网页版打开 / 重新登录三个出路,
 * 底下那行是已经写进本地日志的诊断摘要(22 号票的「导出诊断日志」拿全量)。
 *
 * 它是屏内的一块而不是一个路由:设计稿里顶栏与页码条仍在,失败的只是内容区。
 */
export function LoadFailed({ error, onRetry, onOpenWeb, onRelogin }: LoadFailedProps) {
  const styles = useStyles();
  const theme = useTheme();

  const failure = describeFetchFailure(
    error instanceof NgaError
      ? error
      : { kind: 'unknown', message: error instanceof Error ? error.message : '这一页拉不下来' },
  );
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
}));

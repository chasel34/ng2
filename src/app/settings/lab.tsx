import Constants from 'expo-constants';
import { useState } from 'react';
import { Share } from 'react-native';

import type { WebFallbackMode } from '@/core/net';
import { readDiagnosticLog, readRunLog } from '@/store/diagnostics';
import { successfulCombos } from '@/store/nga-client';
import { useNetSettings } from '@/store/net-settings';
import { OptionDialog, type DialogOption } from '@/ui/option-dialog';
import { SettingsNavRow, SettingsSection, SettingsSwitchRow } from '@/ui/settings-row';
import { SettingsShell } from '@/ui/settings-shell';
import { showToast } from '@/ui/toast';

/**
 * 网页数据源兜底的四档(ADR-0002 / API 文档 §0.8)。设计稿这行画的是开关,
 * 但 19 票落地的是四档档位,所以改成选项行——按同屏其它选项行的形状延伸。
 */
const FALLBACK_OPTIONS: readonly DialogOption<WebFallbackMode>[] = [
  { value: 'disabled', label: '关闭', sub: '原生接口失败就直接报错' },
  { value: 'secondary', label: '兜底(默认)', sub: '原生接口全垮了才去反解网页版' },
  { value: 'primary', label: '优先', sub: '先反解网页版,失败再走原生接口' },
  { value: 'only', label: '只用网页', sub: '排查用:完全不走原生接口' },
];

const FALLBACK_LABELS: Readonly<Record<WebFallbackMode, string>> = {
  disabled: '关闭',
  secondary: '兜底(默认)',
  primary: '优先',
  only: '只用网页',
};

/** 一次分享出去的诊断条数上限。日志一条就是多行,整份几百条分享面板会塞不下。 */
const EXPORT_LIMIT = 50;

/** 「本次运行」里分享出去的请求条数。 */
const RUN_LOG_EXPORT_LIMIT = 20;

/**
 * 实验室与诊断(`/settings` 的二级页)。
 *
 * 这一页只收「排查时才会用到」的四条:两档改反封锁链行为的开关,两个把内存里的
 * 链路状态倒出来的入口。原先这屏还挂着「手势返回」「阅读时常亮」「清理缓存」
 * 「恢复默认设置」「关于」,它们跟反封锁没关系,已经归回设置一级页。
 */
export default function LabSettingsScreen() {
  const webFallbackMode = useNetSettings((state) => state.webFallbackMode);
  const setWebFallbackMode = useNetSettings((state) => state.setWebFallbackMode);
  const windowsPhoneUa = useNetSettings((state) => state.readPhpWindowsPhoneUa);
  const setWindowsPhoneUa = useNetSettings((state) => state.setReadPhpWindowsPhoneUa);

  const [fallbackOpen, setFallbackOpen] = useState(false);

  const version = Constants.expoConfig?.version ?? '0.1.0';

  /**
   * 导出诊断日志。没装 expo-sharing,用 RN 自带的 Share 把日志当文本发出去
   * (选「保存到文件」也走得通),省一个原生依赖。
   */
  const exportLog = () => {
    const log = readDiagnosticLog();
    if (log.length === 0) {
      showToast('还没有诊断日志——反封锁链整条失败过才会攒');
      return;
    }
    const recent = log.slice(-EXPORT_LIMIT);
    const header = `ng2 ${version} · 诊断日志 ${recent.length}/${log.length} 条`;
    Share.share({ title: '导出诊断日志', message: [header, ...recent].join('\n\n') }).catch(
      () => showToast('分享面板没打开'),
    );
  };

  /**
   * 「本次运行的组合」(2026-08-13「版块全空」排查)。
   *
   * 反封锁链把每个接口钉在「上次试通的格式 × 域名」上,这个状态只活在内存里,
   * 出问题时(比如所有版块都空)最想知道的就是它——以前界面上完全看不见。
   * 顺带把本次运行的请求落点也分享出去:成功的请求同样在里面,
   * 「链自认为成功但拿回来 0 条」只有在这儿才看得出来。
   */
  const combos = successfulCombos();
  const comboSummary =
    combos.length === 0
      ? '还没有成功的请求'
      : combos.map(({ key, combo }) => `${key}: ${combo}`).join(' · ');

  const shareRunLog = () => {
    const runLog = readRunLog().slice(0, RUN_LOG_EXPORT_LIMIT);
    const lines = [
      `ng2 ${version} · 本次运行`,
      '【当前组合】',
      combos.length === 0 ? '(还没有成功的请求)' : comboSummary,
      `【最近 ${runLog.length} 个请求】`,
      ...runLog.map((entry) => {
        const query = Object.entries(entry.params)
          .map(([name, value]) => `${name}=${value}`)
          .join('&');
        const target = query === '' ? entry.path : `${entry.path}?${query}`;
        const time = new Date(entry.at).toISOString().slice(11, 19);
        return `${time} ${entry.ok ? '成功' : '失败'} ${target} (${entry.attempts} 次尝试) ${entry.message}`;
      }),
    ];
    Share.share({ title: '本次运行', message: lines.join('\n') }).catch(() =>
      showToast('分享面板没打开'),
    );
  };

  const dialogs = (
    <OptionDialog
      open={fallbackOpen}
      title="网页数据源兜底"
      options={FALLBACK_OPTIONS}
      value={webFallbackMode}
      hint="原生接口被封时,从网页版 HTML 里反解出同样的数据。改的是它在反封锁链上的位置。"
      onCancel={() => setFallbackOpen(false)}
      onConfirm={(mode) => {
        setFallbackOpen(false);
        setWebFallbackMode(mode);
      }}
    />
  );

  return (
    <SettingsShell title="实验室与诊断" overlays={dialogs}>
      <SettingsSection>实验室</SettingsSection>

      <SettingsNavRow
        label="网页数据源兜底"
        sub={FALLBACK_LABELS[webFallbackMode]}
        onPress={() => setFallbackOpen(true)}
      />
      <SettingsSwitchRow
        label="帖子接口使用 Windows Phone UA"
        sub="实测更不容易被封;被封表现变了可以关掉试试"
        value={windowsPhoneUa}
        onChange={setWindowsPhoneUa}
      />

      <SettingsSection>诊断</SettingsSection>

      <SettingsNavRow label="本次运行的组合" sub={comboSummary} onPress={shareRunLog} />
      <SettingsNavRow label="导出诊断日志" sub={`最近 ${EXPORT_LIMIT} 条`} onPress={exportLog} />
    </SettingsShell>
  );
}

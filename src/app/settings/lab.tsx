import Constants from 'expo-constants';
import { useState } from 'react';
import { Share } from 'react-native';

import { cacheTotalBytes, formatCacheSize } from '@/core/local';
import type { WebFallbackMode } from '@/core/net';
import { readDiagnosticLog } from '@/store/diagnostics';
import { useNetSettings } from '@/store/net-settings';
import { useSettings } from '@/store/settings';
import { clearTopicCache, useCachedTopics } from '@/store/topic-cache';
import { ConfirmDialog } from '@/ui/confirm-dialog';
import { OptionDialog, type DialogOption } from '@/ui/option-dialog';
import { SettingsNavRow, SettingsSection, SettingsSwitchRow } from '@/ui/settings-row';
import { SettingsShell } from '@/ui/settings-shell';
import { showNotAvailable, showToast } from '@/ui/toast';

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

/** 设置 3 / 3 —— 实验室与存储(设计稿 `settings3` 屏)。 */
export default function LabSettingsScreen() {
  const settings = useSettings((state) => state.settings);
  const setSetting = useSettings((state) => state.set);
  const resetAll = useSettings((state) => state.resetAll);

  const webFallbackMode = useNetSettings((state) => state.webFallbackMode);
  const setWebFallbackMode = useNetSettings((state) => state.setWebFallbackMode);
  const windowsPhoneUa = useNetSettings((state) => state.readPhpWindowsPhoneUa);
  const setWindowsPhoneUa = useNetSettings((state) => state.setReadPhpWindowsPhoneUa);

  const topics = useCachedTopics();
  const cacheBytes = cacheTotalBytes(topics);

  const [fallbackOpen, setFallbackOpen] = useState(false);
  const [clearCacheOpen, setClearCacheOpen] = useState(false);
  const [resetOpen, setResetOpen] = useState(false);

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

  return (
    <SettingsShell index={2}>
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
      <SettingsSwitchRow
        label="手势返回"
        sub="从左边缘右滑返回上一页"
        value={settings.gestureBack}
        onChange={(next) => setSetting('gestureBack', next)}
      />
      <SettingsSwitchRow
        label="阅读时常亮"
        sub="看帖子详情时屏幕不自动熄灭"
        value={settings.keepScreenOn}
        onChange={(next) => setSetting('keepScreenOn', next)}
      />

      <SettingsSection>存储与诊断</SettingsSection>

      <SettingsNavRow
        label="清理缓存"
        sub={
          topics.length === 0
            ? '还没有缓存的帖子'
            : `${topics.length} 个主题 · 已占用 ${formatCacheSize(cacheBytes)}`
        }
        onPress={() => {
          if (topics.length === 0) {
            showToast('还没有缓存可清');
            return;
          }
          setClearCacheOpen(true);
        }}
      />
      <SettingsNavRow label="导出诊断日志" sub={`最近 ${EXPORT_LIMIT} 条`} onPress={exportLog} />
      <SettingsNavRow
        label="恢复默认设置"
        sub="三屏全部设置回默认值,不动账号与缓存"
        onPress={() => setResetOpen(true)}
      />

      <SettingsSection>关于</SettingsSection>

      <SettingsNavRow
        label="关于本客户端"
        sub={`v${version}`}
        onPress={showNotAvailable}
      />

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

      <ConfirmDialog
        open={clearCacheOpen}
        title="清理缓存"
        message={`${topics.length} 个主题、共 ${formatCacheSize(cacheBytes)} 的离线数据将被删除。`}
        confirmLabel="清理"
        destructive
        onCancel={() => setClearCacheOpen(false)}
        onConfirm={() => {
          setClearCacheOpen(false);
          const freed = formatCacheSize(cacheBytes);
          clearTopicCache();
          showToast(`已清理 ${freed} 缓存`);
        }}
      />

      <ConfirmDialog
        open={resetOpen}
        title="恢复默认设置"
        message="三屏的全部开关、域名、字号与主题风格都会回到默认值。账号、收藏、缓存与屏蔽规则不受影响。"
        confirmLabel="恢复"
        destructive
        onCancel={() => setResetOpen(false)}
        onConfirm={() => {
          setResetOpen(false);
          resetAll();
          showToast('已恢复默认设置');
        }}
      />
    </SettingsShell>
  );
}

import { useState } from 'react';

import { HISTORY_LIMIT, IMAGE_QUALITY_LABELS, type ImageQuality } from '@/core/local';
import { clearHistory, useHistoryEntries } from '@/store/history';
import { useSettings } from '@/store/settings';
import { ConfirmDialog } from '@/ui/confirm-dialog';
import { OptionDialog, type DialogOption } from '@/ui/option-dialog';
import { SettingsNavRow, SettingsSection, SettingsSwitchRow } from '@/ui/settings-row';
import { SettingsShell } from '@/ui/settings-shell';
import { showToast } from '@/ui/toast';

const QUALITY_OPTIONS: readonly DialogOption<ImageQuality>[] = [
  { value: 'original', label: IMAGE_QUALITY_LABELS.original, sub: '最清楚,也最费流量' },
  { value: 'smart', label: IMAGE_QUALITY_LABELS.smart, sub: '按当前网络自动选' },
  { value: 'thumbnail', label: IMAGE_QUALITY_LABELS.thumbnail, sub: '省流量,点开大图才拉原图' },
];

/** 设置 2 / 3 —— 主题详情设置(设计稿 `settings2` 屏)。 */
export default function ReadingSettingsScreen() {
  const settings = useSettings((state) => state.settings);
  const setSetting = useSettings((state) => state.set);
  const historyCount = useHistoryEntries().length;

  const [qualityOpen, setQualityOpen] = useState(false);
  const [clearOpen, setClearOpen] = useState(false);

  return (
    <SettingsShell index={1}>
      <SettingsSection>主题详情设置</SettingsSection>

      <SettingsSwitchRow
        label="自动加载下一页"
        sub="滚动到底部时自动翻页"
        value={settings.autoLoadNextPage}
        onChange={(next) => setSetting('autoLoadNextPage', next)}
      />
      <SettingsSwitchRow
        label="仅 Wi-Fi 下加载图片"
        sub="移动网络显示「点击显示附件」"
        value={settings.wifiOnlyImages}
        onChange={(next) => setSetting('wifiOnlyImages', next)}
      />
      <SettingsSwitchRow
        label="显示签名档"
        sub="在楼层正文下面显示作者签名"
        value={settings.showSignature}
        onChange={(next) => setSetting('showSignature', next)}
      />
      <SettingsSwitchRow
        label="底部标签页"
        sub="把页码条移到屏幕底部"
        value={settings.bottomPageBar}
        onChange={(next) => setSetting('bottomPageBar', next)}
      />
      <SettingsNavRow
        label="图片加载策略"
        sub={IMAGE_QUALITY_LABELS[settings.imageQuality]}
        onPress={() => setQualityOpen(true)}
      />
      <SettingsNavRow
        label="阅读进度记录"
        sub={
          historyCount === 0
            ? `还没有记录(最多留最近 ${HISTORY_LIMIT} 个主题)`
            : `已记录 ${historyCount} 个主题 · 点此清空`
        }
        onPress={() => {
          if (historyCount === 0) {
            showToast('还没有阅读进度可清');
            return;
          }
          setClearOpen(true);
        }}
      />

      <OptionDialog
        open={qualityOpen}
        title="图片加载策略"
        options={QUALITY_OPTIONS}
        value={settings.imageQuality}
        hint="这一档定的是清晰度;要不要在流量下自动拉图,由上面的「仅 Wi-Fi 下加载图片」管。"
        onCancel={() => setQualityOpen(false)}
        onConfirm={(quality) => {
          setQualityOpen(false);
          setSetting('imageQuality', quality);
        }}
      />

      {/* 阅读进度与浏览历史是同一张表(16 票),清进度就是清历史,得说清楚 */}
      <ConfirmDialog
        open={clearOpen}
        title="清空阅读进度记录"
        message={`将删除 ${historyCount} 个主题的「上次读到第 N 楼」,浏览历史列表也会一起清空。`}
        confirmLabel="清空"
        destructive
        onCancel={() => setClearOpen(false)}
        onConfirm={() => {
          setClearOpen(false);
          clearHistory();
          showToast('已清空阅读进度');
        }}
      />
    </SettingsShell>
  );
}

import { useRouter } from 'expo-router';
import { useState } from 'react';

import {
  HISTORY_LIMIT,
  IMAGE_QUALITY_LABELS,
  THEME_STYLE_LABELS,
  cacheTotalBytes,
  formatCacheSize,
  type ImageQuality,
  type ThemeStyle,
} from '@/core/local';
import { NGA_HOSTS } from '@/core/net';
import { useAccounts } from '@/store/accounts';
import { useLocalFilters } from '@/store/filters';
import { clearHistory, useHistoryEntries } from '@/store/history';
import { useSettings } from '@/store/settings';
import { useThemeMode } from '@/store/theme';
import { clearTopicCache, useCachedTopics } from '@/store/topic-cache';
import { ConfirmDialog } from '@/ui/confirm-dialog';
import { OptionDialog, type DialogOption } from '@/ui/option-dialog';
import { SettingsNavRow, SettingsSection, SettingsSwitchRow } from '@/ui/settings-row';
import { SettingsShell } from '@/ui/settings-shell';
import { useTheme } from '@/ui/theme';
import { showToast } from '@/ui/toast';

/**
 * 「主题风格」对话框的三档(设计稿 `dialog:'theme'`)。第三档不是配色,是夜间模式本身
 * ——它的副标题原文就写着「跟随夜间模式开关」。
 */
type ThemeChoice = ThemeStyle | 'night';

const THEME_OPTIONS: readonly DialogOption<ThemeChoice>[] = [
  { value: 'ink', label: THEME_STYLE_LABELS.ink, sub: '顶栏墨绿 + 奶油背景' },
  { value: 'plain', label: THEME_STYLE_LABELS.plain, sub: '白底 + 深绿强调' },
  { value: 'night', label: '夜间近黑', sub: '跟随夜间模式开关' },
];

const HOST_OPTIONS: readonly DialogOption<string>[] = NGA_HOSTS.map((host) => ({
  value: host,
  label: host.replace('https://', ''),
}));

const QUALITY_OPTIONS: readonly DialogOption<ImageQuality>[] = [
  { value: 'original', label: IMAGE_QUALITY_LABELS.original, sub: '最清楚,也最费流量' },
  { value: 'smart', label: IMAGE_QUALITY_LABELS.smart, sub: '按当前网络自动选' },
  { value: 'thumbnail', label: IMAGE_QUALITY_LABELS.thumbnail, sub: '省流量,点开大图才拉原图' },
];

/**
 * 设置(设计稿 `settings` 屏)。
 *
 * 原来是「通用 / 主题详情 / 实验室」三屏向导,现在合成一屏五组:日常会改的都在这儿,
 * 只有排查时才用的四条收进了 `/settings/lab`。分组的边界按「用户什么时候会想起它」
 * 划,不按数据存在哪:「手势返回」「阅读时常亮」原先挂在实验室屏下,它们跟反封锁
 * 没有关系,就是普通阅读偏好,归到阅读组。
 */
export default function SettingsScreen() {
  const router = useRouter();
  const theme = useTheme();

  const settings = useSettings((state) => state.settings);
  const setSetting = useSettings((state) => state.set);
  const resetAll = useSettings((state) => state.resetAll);
  const mode = useThemeMode((state) => state.mode);
  const setMode = useThemeMode((state) => state.setMode);

  const accounts = useAccounts((state) => state.accounts);
  const ruleCount = useLocalFilters((state) => state.rules.length);
  const historyCount = useHistoryEntries().length;

  const topics = useCachedTopics();
  const cacheBytes = cacheTotalBytes(topics);

  const [hostOpen, setHostOpen] = useState(false);
  const [themeOpen, setThemeOpen] = useState(false);
  const [qualityOpen, setQualityOpen] = useState(false);
  const [clearHistoryOpen, setClearHistoryOpen] = useState(false);
  const [clearCacheOpen, setClearCacheOpen] = useState(false);
  const [resetOpen, setResetOpen] = useState(false);

  const dark = theme.scheme === 'dark';
  const { listFontSize, avatarScale, smileyScale } = settings.appearance;

  const dialogs = (
    <>
      <OptionDialog
        open={hostOpen}
        title="NGA 域名"
        options={HOST_OPTIONS}
        value={settings.host}
        hint="被封时换一个域名常常就通了;反封锁链本来也会自己轮换,这里定的是先试哪一个。"
        onCancel={() => setHostOpen(false)}
        onConfirm={(host) => {
          setHostOpen(false);
          setSetting('host', host);
        }}
      />

      <OptionDialog
        open={themeOpen}
        title="主题风格"
        options={THEME_OPTIONS}
        value={dark ? 'night' : settings.themeStyle}
        onCancel={() => setThemeOpen(false)}
        onConfirm={(choice) => {
          setThemeOpen(false);
          if (choice === 'night') {
            setMode('dark');
            return;
          }
          setSetting('themeStyle', choice);
          // 在夜间模式下选了一档浅色风格,那就是要退出夜间模式
          if (dark) setMode('light');
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
        open={clearHistoryOpen}
        title="清空阅读进度记录"
        message={`将删除 ${historyCount} 个主题的「上次读到第 N 楼」,浏览历史列表也会一起清空。`}
        confirmLabel="清空"
        destructive
        onCancel={() => setClearHistoryOpen(false)}
        onConfirm={() => {
          setClearHistoryOpen(false);
          clearHistory();
          showToast('已清空阅读进度');
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
        message="全部开关、域名、字号与主题风格都会回到默认值。账号、收藏、缓存与屏蔽规则不受影响。"
        confirmLabel="恢复"
        destructive
        onCancel={() => setResetOpen(false)}
        onConfirm={() => {
          setResetOpen(false);
          resetAll();
          showToast('已恢复默认设置');
        }}
      />
    </>
  );

  return (
    <SettingsShell title="设置" overlays={dialogs}>
      <SettingsSection>通用</SettingsSection>

      <SettingsNavRow label="NGA 域名" sub={settings.host} onPress={() => setHostOpen(true)} />
      <SettingsNavRow
        label="账号管理"
        sub={accounts.length === 0 ? '还没有登录账号' : `已登录 ${accounts.length} 个账号`}
        onPress={() => router.push('/accounts')}
      />
      {/* 夜间模式与「跟随系统」是同一个档位的两面:开关记的是最终深浅,
          跟随系统打开时那个开关只是在显示系统现在是深还是浅 */}
      <SettingsSwitchRow
        label="夜间模式"
        sub={mode === 'system' ? '当前跟随系统' : undefined}
        value={dark}
        onChange={(next) => setMode(next ? 'dark' : 'light')}
      />
      <SettingsSwitchRow
        label="夜间模式跟随系统"
        value={mode === 'system'}
        onChange={(next) => setMode(next ? 'system' : theme.scheme)}
      />
      <SettingsNavRow
        label="主题风格"
        sub={dark ? '夜间近黑' : THEME_STYLE_LABELS[settings.themeStyle]}
        onPress={() => setThemeOpen(true)}
      />
      <SettingsSwitchRow
        label="左手模式"
        sub="FAB 与菜单移到左侧"
        value={settings.leftHanded}
        onChange={(next) => setSetting('leftHanded', next)}
      />
      <SettingsSwitchRow
        label="使用纯色背景"
        sub="主题列表和详情页使用纯色背景"
        value={settings.solidBackground}
        onChange={(next) => setSetting('solidBackground', next)}
      />

      <SettingsSection>阅读</SettingsSection>

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
      <SettingsNavRow
        label="图片加载策略"
        sub={IMAGE_QUALITY_LABELS[settings.imageQuality]}
        onPress={() => setQualityOpen(true)}
      />
      <SettingsSwitchRow
        label="显示签名档"
        sub="在楼层正文下面显示作者签名"
        value={settings.showSignature}
        onChange={(next) => setSetting('showSignature', next)}
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
      <SettingsNavRow
        label="字体和头像大小"
        sub={`列表字体 ${listFontSize} · 头像 ${avatarScale}% · 表情 ${smileyScale}%`}
        onPress={() => router.push('/settings/font-size')}
      />

      <SettingsSection>通知</SettingsSection>

      <SettingsSwitchRow
        label="启用被喷提示"
        sub="关掉后不再轮询通知,抽屉也不显示未读角标"
        value={settings.sprayNotice}
        onChange={(next) => setSetting('sprayNotice', next)}
      />
      <SettingsSwitchRow
        label="提示声音"
        value={settings.noticeSound}
        onChange={(next) => setSetting('noticeSound', next)}
      />

      <SettingsSection>内容与存储</SettingsSection>

      <SettingsNavRow
        label="屏蔽规则"
        sub={ruleCount === 0 ? '还没有本地规则' : `本地 ${ruleCount} 条`}
        onPress={() => router.push('/filters')}
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
          setClearHistoryOpen(true);
        }}
      />
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

      <SettingsSection>高级</SettingsSection>

      <SettingsNavRow
        label="实验室与诊断"
        sub="网页兜底 · 请求组合 · 诊断日志"
        onPress={() => router.push('/settings/lab')}
      />
      <SettingsNavRow
        label="恢复默认设置"
        sub="全部设置回默认值,不动账号与缓存"
        onPress={() => setResetOpen(true)}
      />
    </SettingsShell>
  );
}

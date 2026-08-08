import { useRouter } from 'expo-router';
import { useState } from 'react';

import { THEME_STYLE_LABELS, type ThemeStyle } from '@/core/local';
import { NGA_HOSTS } from '@/core/net';
import { useAccounts } from '@/store/accounts';
import { useLocalFilters } from '@/store/filters';
import { useSettings } from '@/store/settings';
import { useThemeMode } from '@/store/theme';
import { OptionDialog, type DialogOption } from '@/ui/option-dialog';
import { SettingsNavRow, SettingsSection, SettingsSwitchRow } from '@/ui/settings-row';
import { SettingsShell } from '@/ui/settings-shell';
import { useTheme } from '@/ui/theme';

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

/** 设置 1 / 3 —— 通用设置(设计稿 `settings` 屏)。 */
export default function GeneralSettingsScreen() {
  const router = useRouter();
  const theme = useTheme();

  const settings = useSettings((state) => state.settings);
  const setSetting = useSettings((state) => state.set);
  const mode = useThemeMode((state) => state.mode);
  const setMode = useThemeMode((state) => state.setMode);

  const accounts = useAccounts((state) => state.accounts);
  const ruleCount = useLocalFilters((state) => state.rules.length);

  const [hostOpen, setHostOpen] = useState(false);
  const [themeOpen, setThemeOpen] = useState(false);

  const dark = theme.scheme === 'dark';
  const { listFontSize, avatarScale, smileyScale } = settings.appearance;

  return (
    <SettingsShell index={0}>
      <SettingsSection>通用设置</SettingsSection>

      <SettingsNavRow
        label="NGA 域名"
        sub={settings.host}
        onPress={() => setHostOpen(true)}
      />
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
      <SettingsNavRow
        label="主题风格"
        sub={dark ? '夜间近黑' : THEME_STYLE_LABELS[settings.themeStyle]}
        onPress={() => setThemeOpen(true)}
      />
      <SettingsNavRow
        label="调整字体和头像大小"
        sub={`列表字体 ${listFontSize} · 头像 ${avatarScale}% · 表情 ${smileyScale}%`}
        onPress={() => router.push('/settings/font-size')}
      />
      <SettingsNavRow
        label="屏蔽规则"
        sub={ruleCount === 0 ? '还没有本地规则' : `本地 ${ruleCount} 条`}
        onPress={() => router.push('/filters')}
      />

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
    </SettingsShell>
  );
}

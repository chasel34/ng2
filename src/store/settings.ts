import { create } from 'zustand';

import {
  DEFAULT_SETTINGS,
  clampSlider,
  parseSettings,
  type AppSettings,
  type AppearanceSettings,
  type SliderSpec,
} from '@/core/local';
import { DEFAULT_WEB_FALLBACK_MODE } from '@/core/net';

import { useNetSettings } from './net-settings';
import { storage } from './storage';
import { connectThemeModeStorage, useThemeMode, type ThemeMode } from './theme';

/**
 * 设置三屏(22 票)的持久化。整张表存成一条 JSON 而不是每项一个 MMKV key:
 * 「恢复默认」只要删一条,加设置项也不用管迁移(坏项由 `parseSettings` 逐项回落)。
 *
 * 夜间模式档位仍住在 `store/theme.ts`(05 建的),这里只负责把它接上 MMKV——
 * 那个 store 留了 `ThemeModeStorage` 接口就是等这一步,接之前夜间模式重启就丢。
 */

const SETTINGS_KEY = 'settings.v1';
const THEME_MODE_KEY = 'settings.themeMode.v1';

const THEME_MODES: readonly ThemeMode[] = ['system', 'light', 'dark'];

function load(): AppSettings {
  try {
    const raw = storage.getString(SETTINGS_KEY);
    return raw === undefined ? DEFAULT_SETTINGS : parseSettings(JSON.parse(raw));
  } catch {
    // 存档坏了就当没设置过,别让一条脏数据把 app 挡在启动那一步
    return DEFAULT_SETTINGS;
  }
}

function save(settings: AppSettings): void {
  try {
    storage.set(SETTINGS_KEY, JSON.stringify(settings));
  } catch {
    // 写不进就只活在内存,重启回默认值
  }
}

interface SettingsState {
  readonly settings: AppSettings;
  /** 改一项。设置项之间互不影响,所以统一走一个入口而不是十几个 setter */
  set: <K extends keyof AppSettings>(key: K, value: AppSettings[K]) => void;
  /** 改一根滑杆(值会先量化到步长) */
  setAppearance: (spec: SliderSpec, value: number) => void;
  /** 恢复默认:本表 + 夜间模式 + 网络层那两项(18、19 票)一起回默认 */
  resetAll: () => void;
}

export const useSettings = create<SettingsState>()((set, get) => ({
  settings: load(),
  set: (key, value) => {
    const next = { ...get().settings, [key]: value };
    set({ settings: next });
    save(next);
  },
  setAppearance: (spec, value) => {
    const appearance: AppearanceSettings = {
      ...get().settings.appearance,
      [spec.key]: clampSlider(spec, value),
    };
    const next = { ...get().settings, appearance };
    set({ settings: next });
    save(next);
  },
  resetAll: () => {
    set({ settings: DEFAULT_SETTINGS });
    save(DEFAULT_SETTINGS);
    useThemeMode.getState().setMode('system');
    // read.php 的 Windows Phone UA 默认开(ADR-0002),兜底档位默认 secondary
    useNetSettings.getState().setReadPhpWindowsPhoneUa(true);
    useNetSettings.getState().setWebFallbackMode(DEFAULT_WEB_FALLBACK_MODE);
  },
}));

/** 订阅整张设置表。 */
export const useAppSettings = (): AppSettings => useSettings((state) => state.settings);

/** 同步读一项(渲染之外的地方要用,比如请求层取域名)。 */
export function currentSettings(): AppSettings {
  return useSettings.getState().settings;
}

/** 给 core/net fetcher 的域名读取口:设置里改完,下一个请求就发到新域名。 */
export function currentHost(): string {
  return useSettings.getState().settings.host;
}

/**
 * 把夜间模式档位接上 MMKV。
 *
 * 模块作用域执行一次(`store/theme.ts` 的注释交代过:不能放进会被 Fast Refresh
 * 跳过的深层 effect),import 这个模块的地方就已经接好了。
 */
connectThemeModeStorage({
  load: () => {
    try {
      const raw = storage.getString(THEME_MODE_KEY);
      return THEME_MODES.find((mode) => mode === raw) ?? null;
    } catch {
      return null;
    }
  },
  save: (mode) => {
    try {
      storage.set(THEME_MODE_KEY, mode);
    } catch {
      // 同上
    }
  },
});

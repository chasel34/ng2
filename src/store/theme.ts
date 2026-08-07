import { create } from 'zustand';

import type { ColorScheme } from '@/ui/tokens';

/** 用户选择的主题模式。system 表示跟随系统深浅色。 */
export type ThemeMode = 'system' | 'light' | 'dark';

/**
 * 主题模式的持久化后端。留成接口是为了 22(设置三屏)接 MMKV 时不用改这里,
 * 单测里也能塞假实现。未接入前主题模式只活在内存里。
 */
export interface ThemeModeStorage {
  load: () => ThemeMode | null;
  save: (mode: ThemeMode) => void;
}

let storage: ThemeModeStorage | null = null;

interface ThemeModeState {
  mode: ThemeMode;
  setMode: (mode: ThemeMode) => void;
}

export const useThemeMode = create<ThemeModeState>()((set) => ({
  mode: 'system',
  setMode: (mode) => {
    set({ mode });
    storage?.save(mode);
  },
}));

/**
 * 接上持久化后端:立刻把已存模式灌回 store,之后的 setMode 都会写回。
 * 返回断开函数。
 *
 * 注意 storage 是模块级单例:22(设置三屏)接 MMKV 时要在模块作用域或根组件挂载时调用一次,
 * 别放在会被 Fast Refresh 跳过的深层 effect 里,否则热更后持久化会静默失效。
 */
export function connectThemeModeStorage(next: ThemeModeStorage): () => void {
  storage = next;
  const saved = next.load();
  if (saved !== null) {
    useThemeMode.setState({ mode: saved });
  }
  return () => {
    if (storage === next) {
      storage = null;
    }
  };
}

/** 系统上报的配色。RN 的 useColorScheme 拿不到时会给 'unspecified' 或 null。 */
export type SystemColorScheme = ColorScheme | 'unspecified' | null | undefined;

/** 把模式与系统色合成最终配色。系统色未知时按浅色走。 */
export function resolveColorScheme(
  mode: ThemeMode,
  systemScheme: SystemColorScheme,
): ColorScheme {
  if (mode !== 'system') {
    return mode;
  }
  return systemScheme === 'dark' ? 'dark' : 'light';
}

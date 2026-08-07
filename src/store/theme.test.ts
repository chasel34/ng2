import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  connectThemeModeStorage,
  resolveColorScheme,
  useThemeMode,
  type ThemeMode,
  type ThemeModeStorage,
} from './theme';

beforeEach(() => {
  useThemeMode.setState({ mode: 'system' });
});

describe('resolveColorScheme', () => {
  it('mode 为 system 时跟随系统', () => {
    expect(resolveColorScheme('system', 'dark')).toBe('dark');
    expect(resolveColorScheme('system', 'light')).toBe('light');
  });

  it('系统色未知时退回浅色', () => {
    expect(resolveColorScheme('system', null)).toBe('light');
    expect(resolveColorScheme('system', undefined)).toBe('light');
    expect(resolveColorScheme('system', 'unspecified')).toBe('light');
  });

  it('手动覆盖时忽略系统色', () => {
    expect(resolveColorScheme('dark', 'light')).toBe('dark');
    expect(resolveColorScheme('light', 'dark')).toBe('light');
  });
});

describe('useThemeMode', () => {
  it('默认跟随系统', () => {
    expect(useThemeMode.getState().mode).toBe('system');
  });

  it('setMode 改写当前模式', () => {
    useThemeMode.getState().setMode('dark');
    expect(useThemeMode.getState().mode).toBe('dark');
  });
});

describe('connectThemeModeStorage', () => {
  const makeStorage = (saved: ThemeMode | null) => ({
    load: () => saved,
    save: vi.fn<(mode: ThemeMode) => void>(),
  }) satisfies ThemeModeStorage;

  it('未接持久化时 setMode 不报错', () => {
    expect(() => useThemeMode.getState().setMode('light')).not.toThrow();
  });

  it('接入时把已存模式灌回 store', () => {
    const disconnect = connectThemeModeStorage(makeStorage('dark'));
    expect(useThemeMode.getState().mode).toBe('dark');
    disconnect();
  });

  it('已存模式为空时保留当前模式', () => {
    const disconnect = connectThemeModeStorage(makeStorage(null));
    expect(useThemeMode.getState().mode).toBe('system');
    disconnect();
  });

  it('接入后 setMode 写回持久化', () => {
    const storage = makeStorage(null);
    const disconnect = connectThemeModeStorage(storage);
    useThemeMode.getState().setMode('light');
    expect(storage.save).toHaveBeenCalledWith('light');
    disconnect();
  });

  it('断开后不再写回持久化', () => {
    const storage = makeStorage(null);
    connectThemeModeStorage(storage)();
    useThemeMode.getState().setMode('light');
    expect(storage.save).not.toHaveBeenCalled();
  });
});

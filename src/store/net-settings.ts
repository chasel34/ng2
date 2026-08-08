import { create } from 'zustand';

import { DEFAULT_WEB_FALLBACK_MODE, type UserAgentProfile, type WebFallbackMode } from '@/core/net';

import { storage } from './storage';

const UA_KEY = 'net.readPhpWindowsPhoneUa.v1';
const WEB_FALLBACK_KEY = 'net.webFallbackMode.v1';

const WEB_FALLBACK_MODES: readonly WebFallbackMode[] = [
  'disabled',
  'secondary',
  'primary',
  'only',
];

interface NetSettingsState {
  /**
   * `read.php` 改用 Windows Phone UA(ADR-0002:MNGA 强制用它,实测更不容易被封)。
   *
   * **默认开**:07 票起 read.php 就一直用这一档,并已过真机验收——18 票把它从
   * 写死改成开关(被封的表现会变,这一档要能关),但不改已验证过的默认行为。
   * 关掉后 read.php 与其它接口一样走系统 WebView UA。
   */
  readPhpWindowsPhoneUa: boolean;
  setReadPhpWindowsPhoneUa: (enabled: boolean) => void;
  /**
   * Web 反解档位(ADR-0002 / API 文档 §0.8 的四档,19 票)。
   *
   * 默认 `secondary`:排在换账号之后,原生接口全垮了才去反解网页版。
   * `primary`/`only` 是排查用的档位(怀疑 read.php 被封时,让它先走或只走网页),
   * `disabled` 则完全关掉。22 票把它接进设置页的「实验室 · 网页数据源兜底」。
   */
  webFallbackMode: WebFallbackMode;
  setWebFallbackMode: (mode: WebFallbackMode) => void;
}

function loadUa(): boolean {
  try {
    return storage.getBoolean(UA_KEY) ?? true;
  } catch {
    return true;
  }
}

function loadWebFallbackMode(): WebFallbackMode {
  try {
    const stored = storage.getString(WEB_FALLBACK_KEY);
    return WEB_FALLBACK_MODES.find((mode) => mode === stored) ?? DEFAULT_WEB_FALLBACK_MODE;
  } catch {
    return DEFAULT_WEB_FALLBACK_MODE;
  }
}

/**
 * 网络层的开关。22 号票(设置三屏)的域名切换也归这里。
 */
export const useNetSettings = create<NetSettingsState>()((set) => ({
  readPhpWindowsPhoneUa: loadUa(),
  setReadPhpWindowsPhoneUa: (enabled) => {
    set({ readPhpWindowsPhoneUa: enabled });
    try {
      storage.set(UA_KEY, enabled);
    } catch {
      // 写不进就只活在内存,重启回默认值
    }
  },
  webFallbackMode: loadWebFallbackMode(),
  setWebFallbackMode: (mode) => {
    set({ webFallbackMode: mode });
    try {
      storage.set(WEB_FALLBACK_KEY, mode);
    } catch {
      // 同上
    }
  },
}));

/**
 * 给 core/net fetcher 的 UA 档位读取口(每次请求现取,设置改了下一个请求即生效)。
 * 返回 null = 不覆盖,按默认的系统 WebView UA 走。
 */
export function readPhpUserAgent(): UserAgentProfile | null {
  return useNetSettings.getState().readPhpWindowsPhoneUa ? 'windowsPhone' : null;
}

/** 同上,Web 反解档位的读取口。 */
export function webFallbackMode(): WebFallbackMode {
  return useNetSettings.getState().webFallbackMode;
}

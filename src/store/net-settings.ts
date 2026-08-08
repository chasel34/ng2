import { create } from 'zustand';

import type { UserAgentProfile } from '@/core/net';

import { storage } from './storage';

const STORE_KEY = 'net.readPhpWindowsPhoneUa.v1';

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
}

function load(): boolean {
  try {
    return storage.getBoolean(STORE_KEY) ?? true;
  } catch {
    return true;
  }
}

/**
 * 网络层的开关。现在只有一项,22 号票(设置三屏)的域名切换、网页兜底档位
 * 也归这里,所以先开成一个 store 而不是一个孤零零的布尔。
 */
export const useNetSettings = create<NetSettingsState>()((set) => ({
  readPhpWindowsPhoneUa: load(),
  setReadPhpWindowsPhoneUa: (enabled) => {
    set({ readPhpWindowsPhoneUa: enabled });
    try {
      storage.set(STORE_KEY, enabled);
    } catch {
      // 写不进就只活在内存,重启回默认值
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

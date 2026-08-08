import type { NativeIntent } from 'expo-router';

import { ngaLinkPath, parseNgaLink } from '@/core/local';

/**
 * 系统深链入口(24)。外部点进来的链接——intent-filter 接管的官方域名,
 * 以及自定义 scheme `ng2://`——在这儿翻成 app 内的路由。
 *
 * **解不出来就原样放行**:开发客户端自己的启动 URL、以及没带深链的冷启动
 * (`ng2:///`)都从这个口子过,一律改写会让 dev build 打不开。
 * 解析本身不抛异常(core 那层把失败收成了返回值),再兜一层 try 是因为
 * 这个回调抛错会直接崩掉冷启动。
 */
export const redirectSystemPath: NonNullable<NativeIntent['redirectSystemPath']> = ({ path }) => {
  try {
    const result = parseNgaLink(path);
    return result.ok ? ngaLinkPath(result.link) : path;
  } catch {
    return path;
  }
};

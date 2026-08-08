import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import * as SystemUI from 'expo-system-ui';
import { useEffect, useState } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { useNotificationsPoller } from '@/store/notifications';
import { useAppSettings } from '@/store/settings';
import { useIconFont } from '@/ui/icon';
import { SnackbarHost } from '@/ui/snackbar';
import { useTheme } from '@/ui/theme';

/**
 * 深链(24)冷启动时栈里只有落地页那一屏,顶栏返回箭头(各页都是 `router.back()`)
 * 会点不动。声明首页为 anchor,expo-router 会在深链落地页下面垫一层 index。
 */
export const unstable_settings = { anchor: 'index' };

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // NGA 会封第三方客户端(ADR-0002),失败重试交给 core/net 的策略链,
        // Query 这层只兜一次,避免一个接口被封时反复打
        retry: 1,
        refetchOnWindowFocus: false,
      },
    },
  });
}

export default function RootLayout() {
  const theme = useTheme();
  // QueryClient 必须只建一次:放进 state 而不是模块顶层,Fast Refresh 时不会串到旧实例
  const [queryClient] = useState(createQueryClient);
  const iconFontLoaded = useIconFont();
  const settings = useAppSettings();
  // 通知的前台轮询(13):挂在根上,登录后自己转起来,登出/切号自己停(spec §4)
  useNotificationsPoller();

  useEffect(() => {
    void SystemUI.setBackgroundColorAsync(theme.colors.bg);
  }, [theme]);

  return (
    <QueryClientProvider client={queryClient}>
      <SafeAreaProvider>
        {/* 图标字体没就位时整屏都是豆腐块,等它加载完再渲染路由 */}
        {iconFontLoaded && (
          <Stack
            screenOptions={{
              headerShown: false,
              contentStyle: { backgroundColor: theme.colors.bg },
              // 「手势返回」(22 票)。Android 上这一档由 react-native-screens 转成
              // predictive back 的开关,关掉后只能点顶栏返回箭头
              gestureEnabled: settings.gestureBack,
            }}
          />
        )}
        {/* Snackbar 盖在所有页面上:发起它的页面退场后,「撤销」还能等得到 */}
        <SnackbarHost />
        {/* 状态栏压在顶栏上:浅色下顶栏是墨绿、深色下是近黑,两种都需要浅色图标 */}
        <StatusBar style="light" />
      </SafeAreaProvider>
    </QueryClientProvider>
  );
}

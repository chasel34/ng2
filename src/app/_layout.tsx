import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import * as SystemUI from 'expo-system-ui';
import { useEffect, useState } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { useNotificationsPoller } from '@/store/notifications';
import { useIconFont } from '@/ui/icon';
import { useTheme } from '@/ui/theme';

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
            }}
          />
        )}
        {/* 状态栏压在顶栏上:浅色下顶栏是墨绿、深色下是近黑,两种都需要浅色图标 */}
        <StatusBar style="light" />
      </SafeAreaProvider>
    </QueryClientProvider>
  );
}

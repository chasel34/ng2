import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Stack } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import * as SystemUI from 'expo-system-ui';
import { useEffect, useState } from 'react';
import { StyleSheet } from 'react-native';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { useNotificationsPoller } from '@/store/notifications';
import { useAppSettings } from '@/store/settings';
import { useIconFont } from '@/ui/icon';
import { duration, screenTransition } from '@/ui/motion';
import { SnackbarHost } from '@/ui/snackbar';
import { useTheme } from '@/ui/theme';

/**
 * 深链(24)冷启动时栈里只有落地页那一屏,顶栏返回箭头(各页都是 `router.back()`)
 * 会点不动。声明首页为 anchor,expo-router 会在深链落地页下面垫一层 index。
 */
export const unstable_settings = { anchor: 'index' };

// 深色模式冷启动(M4 验收缺陷 A5):窗口背景色是构建期资源、只有浅色一档,
// 启动屏一撤就露出奶油底。把启动屏(带深色档)按住,等首帧真正能画了再放
void SplashScreen.preventAutoHideAsync();

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

  // 图标字体就位 = 路由树开始渲染,这时才撤启动屏;深色下从深色启动屏
  // 直接接到深色首屏,中间不再闪浅色窗口底
  useEffect(() => {
    if (iconFontLoaded) void SplashScreen.hideAsync();
  }, [iconFontLoaded]);

  return (
    <QueryClientProvider client={queryClient}>
      {/* 大图查看器(25)用 react-native-gesture-handler,它要求根上有这一层 */}
      <GestureHandlerRootView style={styles.root}>
      <SafeAreaProvider>
        {/* 图标字体没就位时整屏都是豆腐块,等它加载完再渲染路由 */}
        {iconFontLoaded && (
          <Stack
            screenOptions={{
              headerShown: false,
              contentStyle: { backgroundColor: theme.colors.bg },
              // 屏与屏之间的转场统一从 ui/motion 取,和菜单/对话框/toast 同一套口径
              animation: screenTransition.push,
              animationDuration: duration.panel,
              // 「手势返回」(22 票)。Android 上这一档由 react-native-screens 转成
              // predictive back 的开关,关掉后只能点顶栏返回箭头
              gestureEnabled: settings.gestureBack,
            }}
          >
            {/* 大图查看器(25):全屏、透明背景淡入,不走横推转场 */}
            <Stack.Screen
              name="image-viewer"
              options={{
                presentation: 'transparentModal',
                animation: screenTransition.overlay,
                contentStyle: { backgroundColor: 'transparent' },
              }}
            />
          </Stack>
        )}
        {/* Snackbar 盖在所有页面上:发起它的页面退场后,「撤销」还能等得到 */}
        <SnackbarHost />
        {/* 状态栏压在顶栏上:浅色下顶栏是墨绿、深色下是近黑,两种都需要浅色图标 */}
        <StatusBar style="light" />
      </SafeAreaProvider>
      </GestureHandlerRootView>
    </QueryClientProvider>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
});

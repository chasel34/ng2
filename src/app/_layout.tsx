import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import * as SystemUI from 'expo-system-ui';
import { useEffect } from 'react';

import { useTheme } from '@/ui/theme';

export default function RootLayout() {
  const theme = useTheme();

  useEffect(() => {
    void SystemUI.setBackgroundColorAsync(theme.colors.bg);
  }, [theme]);

  return (
    <>
      <Stack
        screenOptions={{
          headerShown: false,
          contentStyle: { backgroundColor: theme.colors.bg },
        }}
      />
      {/* 状态栏压在顶栏上:浅色下墨绿、深色下近黑,两种都要浅色图标 */}
      <StatusBar style="light" />
    </>
  );
}

import { useRouter, type Href } from 'expo-router';
import type { ReactNode } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';

import { createThemedStyles } from './theme';
import { TopBar, TopBarButton, TopBarTitle } from './top-bar';

/** 三屏的顺序即设计稿 `order`,页脚的「上一屏/下一屏」按它走。 */
export const SETTINGS_SCREENS: readonly Href[] = ['/settings', '/settings/reading', '/settings/lab'];

export interface SettingsShellProps {
  /** 本屏在三屏里的序号(0 起) */
  index: number;
  children: ReactNode;
}

/**
 * 设置三屏共用的外壳(设计稿 `isSettings`):顶栏「设置 + N / 3」、可滚的行区、
 * 底部一对「上一屏 / 下一屏」按钮。
 *
 * 屏与屏之间用 `replace` 而不是 `push`:设计稿这三屏是同一页的三页翻页,
 * push 会把返回栈堆成「设置 → 设置 → 设置」,退出时要按三次返回。
 */
export function SettingsShell({ index, children }: SettingsShellProps) {
  const styles = useStyles();
  const router = useRouter();

  const total = SETTINGS_SCREENS.length;
  const first = index === 0;
  const last = index === total - 1;

  const goPrev = () => {
    const target = SETTINGS_SCREENS[index - 1];
    if (target === undefined) router.back();
    else router.replace(target);
  };
  const goNext = () => {
    const target = SETTINGS_SCREENS[index + 1];
    if (target === undefined) router.back();
    else router.replace(target);
  };

  return (
    <View style={styles.root}>
      <TopBar paddingHorizontal={4}>
        <TopBarButton
          icon="arrow_back"
          box={46}
          size={24}
          onPress={() => router.back()}
          accessibilityLabel="返回"
        />
        <TopBarTitle variant="sub">设置</TopBarTitle>
        <Text style={styles.page}>
          {index + 1} / {total}
        </Text>
      </TopBar>

      {/* 页脚自带 30 的下留白,滚动容器不再另加,否则底部空出 38 */}
      <ScrollView style={styles.body}>
        {children}
        <View style={styles.footer}>
          <Pressable style={styles.prev} onPress={goPrev}>
            <Text style={styles.prevLabel}>{first ? '返回' : '上一屏'}</Text>
          </Pressable>
          <Pressable style={styles.next} onPress={goNext}>
            <Text style={styles.nextLabel}>
              {last ? '完成' : `下一屏 · ${index + 2} / ${total}`}
            </Text>
          </Pressable>
        </View>
      </ScrollView>
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  root: {
    flex: 1,
    backgroundColor: theme.colors.bg,
  },
  // 设计稿:页码在顶栏最右,压到 70% 不透明度
  page: {
    ...theme.typography.cardMeta,
    color: theme.colors.onTopbar,
    opacity: 0.7,
    marginLeft: 'auto',
    paddingRight: theme.spacing.row,
  },
  body: {
    flex: 1,
  },
  footer: {
    flexDirection: 'row',
    gap: 10,
    paddingHorizontal: theme.spacing.page,
    paddingTop: theme.spacing.xl,
    paddingBottom: 30,
  },
  prev: {
    flex: 1,
    height: 44,
    borderRadius: theme.radius.lg,
    borderWidth: 1,
    borderColor: theme.colors.divider,
    alignItems: 'center',
    justifyContent: 'center',
  },
  prevLabel: {
    ...theme.typography.dialogAction,
    color: theme.colors.fg2,
  },
  next: {
    flex: 1,
    height: 44,
    borderRadius: theme.radius.lg,
    backgroundColor: theme.colors.primary,
    alignItems: 'center',
    justifyContent: 'center',
  },
  nextLabel: {
    ...theme.typography.dialogAction,
    color: theme.colors.onPrimary,
  },
}));

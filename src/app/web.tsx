import { useLocalSearchParams, useRouter } from 'expo-router';
import * as WebBrowser from 'expo-web-browser';
import { useMemo, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { WebView } from 'react-native-webview';

import { Icon } from '@/ui/icon';
import { OverflowMenu, type MenuItem } from '@/ui/menu';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { showNotAvailable, showToast } from '@/ui/toast';
import { TopBar, TopBarButton } from '@/ui/top-bar';

/**
 * 网页版兜底页(设计稿 isWebview,ADR-0002 反封锁链的最后一档)。
 *
 * 反封锁链把格式 × 域名、换账号、Web 反解全试完还是拿不到数据时,
 * 用户至少还能读到这一页——直接把网页版装进 WebView。
 *
 * 它是**站内页**而不是系统浏览器:回退到系统浏览器就丢了「用 APP 阅读这一页」
 * 这个回切入口,而被封往往是一时的,下一次多半就通了。
 *
 * 登录态靠 Android 的原生 cookie 仓库(与登录页共用,见 modules/nga-cookies):
 * WebView 自动带上里面的 cookie。**多账号时那份 cookie 是最后一次登录的账号**,
 * 不一定是 app 里当前切到的那个——RN WebView 没有写 cookie 的接口,先记着这个差异。
 */
export default function WebFallbackScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const webRef = useRef<WebView>(null);

  const { url, title } = useLocalSearchParams<{ url: string; title?: string }>();
  const [loading, setLoading] = useState(true);
  const [menuOpen, setMenuOpen] = useState(false);

  /** 顶栏第二行那串地址,照设计稿去掉协议头。 */
  const urlHint = url === undefined ? '' : url.replace(/^https?:\/\//, '');

  /** 回切:退回来的就是那一屏帖子详情,它在重新获得焦点时会再打一次原生接口。 */
  const backToApp = () => router.back();

  /** 网页菜单,条目与顺序照设计稿 `MENUS.web`。 */
  const menuItems: readonly MenuItem[] = useMemo(() => {
    const pick = (run: () => void) => () => {
      setMenuOpen(false);
      run();
    };
    return [
      {
        key: 'reload',
        label: '刷新页面',
        onPress: pick(() => {
          webRef.current?.reload();
          showToast('已刷新网页');
        }),
      },
      { key: 'app', label: '用 APP 阅读', onPress: pick(() => router.back()) },
      // 复制要 expo-clipboard,本版本还没装(顶栏菜单的「复制链接」同样待办)
      { key: 'copy', label: '复制网址', onPress: pick(showNotAvailable) },
      {
        key: 'browser',
        label: '在系统浏览器打开',
        onPress: pick(() => {
          if (url !== undefined) void WebBrowser.openBrowserAsync(url);
        }),
      },
      // 网页字号要往页面里注 JS 改 zoom,归 22 票的字号调节一起做
      { key: 'font', label: '网页字号', onPress: pick(showNotAvailable) },
    ];
  }, [url, router]);

  return (
    <View style={styles.root}>
      <TopBar paddingHorizontal={4}>
        <TopBarButton
          icon="arrow_back"
          size={24}
          onPress={() => router.back()}
          accessibilityLabel="返回"
        />
        {/* 设计稿这里是两行:标题 + 等宽字的地址 */}
        <View style={styles.titleBox}>
          <Text style={styles.title} numberOfLines={1}>
            {title ?? '网页版'}
          </Text>
          <Text style={styles.url} numberOfLines={1}>
            {urlHint}
          </Text>
        </View>
        <TopBarButton
          icon="more_vert"
          size={22}
          onPress={() => setMenuOpen(true)}
          accessibilityLabel="网页菜单"
          style={styles.menuButton}
        />
      </TopBar>

      {url === undefined ? (
        <View style={styles.center}>
          <Icon name="cloud_off" size={38} color={theme.colors.meta} />
          <Text style={styles.emptyText}>没有可打开的网页地址</Text>
        </View>
      ) : (
        <View style={styles.web}>
          <WebView
            ref={webRef}
            source={{ uri: url }}
            // 登录 cookie 在原生仓库里(见文件头),incognito 会读不到
            domStorageEnabled
            onLoadStart={() => setLoading(true)}
            onLoadEnd={() => setLoading(false)}
            style={styles.webView}
          />
          {loading && (
            <View style={styles.loading} pointerEvents="none">
              <ActivityIndicator color={theme.colors.primary} />
            </View>
          )}
        </View>
      )}

      {/* 设计稿:悬浮在底部的回切按钮,左右 16、底 20、高 48 */}
      <Pressable
        style={[styles.backToApp, { bottom: insets.bottom + 20 }]}
        onPress={backToApp}
        accessibilityLabel="用 APP 阅读这一页"
      >
        <Icon name="smartphone" size={21} color={theme.colors.onFab} />
        <Text style={styles.backToAppLabel}>用 APP 阅读这一页</Text>
      </Pressable>

      <OverflowMenu
        open={menuOpen}
        onClose={() => setMenuOpen(false)}
        items={menuItems}
        top={insets.top + 6}
      />
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  root: {
    flex: 1,
    backgroundColor: theme.colors.bg,
  },
  titleBox: {
    flex: 1,
    minWidth: 0,
    marginLeft: 4,
  },
  title: {
    ...theme.typography.section,
    fontWeight: '600',
    color: theme.colors.onTopbar,
  },
  /** 设计稿:10.5px 等宽,透明度 .75 */
  url: {
    fontSize: 10.5,
    lineHeight: 14,
    fontFamily: 'monospace',
    color: theme.colors.onTopbar,
    opacity: 0.75,
  },
  menuButton: {
    marginLeft: 'auto',
  },
  web: {
    flex: 1,
  },
  webView: {
    flex: 1,
    // 网页版是浅色纸底,深色主题下也别在加载间隙闪黑
    backgroundColor: '#F2EFE6',
  },
  loading: {
    position: 'absolute',
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#F2EFE6',
  },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: theme.spacing.md,
  },
  emptyText: {
    ...theme.typography.notice,
    color: theme.colors.fg2,
  },
  backToApp: {
    position: 'absolute',
    left: 16,
    right: 16,
    height: 48,
    borderRadius: 15,
    backgroundColor: theme.colors.fab,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: theme.spacing.sm,
    boxShadow: theme.shadows.elevation2,
  },
  backToAppLabel: {
    ...theme.typography.errorAction,
    color: theme.colors.onFab,
  },
}));

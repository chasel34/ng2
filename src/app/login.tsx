import CookieManager from '@react-native-cookies/cookies';
import { useRouter } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { WebView } from 'react-native-webview';

import { decodeLoginUsername, extractLoginCookies } from '@/core/account';
import { DEFAULT_NGA_HOST } from '@/core/net';
import { useAccounts } from '@/store/accounts';
import { Icon } from '@/ui/icon';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { showToast } from '@/ui/toast';
import { TopBar, TopBarButton, TopBarTitle, topBarSpacer } from '@/ui/top-bar';

/** 登录页(API 文档 §0.2),两端一致,WebView 打开。 */
const LOGIN_PATH = 'nuke.php?__lib=login&__act=account&login';
const LOGIN_URL = `${DEFAULT_NGA_HOST}/${LOGIN_PATH}`;
/** 顶部提示条展示的地址,照设计稿只到 __lib=login 一段。 */
const URL_HINT = `${DEFAULT_NGA_HOST.replace('https://', '')}/nuke.php?__lib=login`;

/**
 * 抓 cookie 走原生 CookieManager 轮询(每 0.5s,MNGA 的节奏),而不是页内
 * document.cookie:ngaPassportCid 是 HttpOnly,页面 JS 根本看不到(真机实测
 * 2026-08-08,document.cookie 里只有 uid 和 uname)。MNGA(WKHTTPCookieStore)
 * 与 NGA-CLIENT(android.webkit.CookieManager)读的都是原生 cookie 仓库,
 * @react-native-cookies/cookies 在 Android 上包的正是后者。
 */
const COOKIE_POLL_MS = 500;

export default function LoginScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const webRef = useRef<WebView>(null);
  // 轮询会连着看到同一份 cookie,只处理第一次,免得重复落账号/重复退场
  const captured = useRef(false);
  // 先清掉 WebView 里上一个账号的 cookie 再加载登录页,否则添加第二个账号时
  // 轮询会立刻"捕获"到旧账号。清完才挂 WebView。
  const [ready, setReady] = useState(false);
  const add = useAccounts((state) => state.add);

  useEffect(() => {
    let alive = true;
    CookieManager.clearAll()
      .catch(() => {})
      .then(() => {
        if (alive) setReady(true);
      });
    return () => {
      alive = false;
    };
  }, []);

  useEffect(() => {
    if (!ready) return;
    const timer = setInterval(async () => {
      if (captured.current) return;
      let cookies: Awaited<ReturnType<typeof CookieManager.get>>;
      try {
        cookies = await CookieManager.get(DEFAULT_NGA_HOST);
      } catch {
        return;
      }
      if (captured.current) return;
      const raw = Object.values(cookies)
        .map((cookie) => `${cookie.name}=${cookie.value}`)
        .join('; ');
      const parsed = extractLoginCookies(raw);
      if (parsed === null) return;
      captured.current = true;
      const name =
        parsed.urlencodedUname === null ? null : decodeLoginUsername(parsed.urlencodedUname);
      const shownName = name ?? `UID ${parsed.uid}`;
      add({ uid: parsed.uid, cid: parsed.cid, name: shownName, loginAt: Date.now() });
      showToast(`已登录 ${shownName}`);
      router.back();
    }, COOKIE_POLL_MS);
    return () => clearInterval(timer);
  }, [ready, add, router]);

  return (
    <View style={styles.root}>
      <TopBar paddingHorizontal={4}>
        <TopBarButton icon="close" size={24} onPress={() => router.back()} accessibilityLabel="关闭登录页" />
        <TopBarTitle variant="sub">登录 NGA 账号</TopBarTitle>
        <TopBarButton
          icon="refresh"
          size={22}
          onPress={() => webRef.current?.reload()}
          accessibilityLabel="刷新登录页"
          style={topBarSpacer}
        />
      </TopBar>

      <View style={styles.urlBar}>
        <Icon name="lock" size={16} color={theme.colors.primary} />
        <Text style={styles.urlText} numberOfLines={1}>
          {URL_HINT}
        </Text>
      </View>

      {ready ? (
        <WebView
          ref={webRef}
          source={{ uri: LOGIN_URL }}
          style={styles.web}
          // 注意:不能用 incognito——Android 上 incognito 的 WebView 用独立的
          // cookie 仓库,原生 CookieManager 读不到里面的登录 cookie(且 incognito
          // 还会破坏 injectedJavaScript 通道,真机实测 2026-08-08)。
          // 多账号隔离改为挂载前 clearAll(见上面的 useEffect)。
          domStorageEnabled
        />
      ) : (
        <View style={styles.web} />
      )}

      <View style={[styles.noteWrap, { paddingBottom: insets.bottom + 12 }]}>
        <Text style={styles.note}>
          客户端仅托管官方登录页，不接触你的密码；cookie 保存在本地，可在「账号管理」中随时删除。
        </Text>
      </View>
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  root: {
    flex: 1,
    backgroundColor: theme.colors.bg,
  },
  urlBar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.sm,
    paddingVertical: 9,
    paddingHorizontal: theme.spacing.row,
    backgroundColor: theme.colors.surface2,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  urlText: {
    ...theme.typography.meta,
    fontFamily: 'monospace',
    color: theme.colors.fg2,
    flexShrink: 1,
  },
  web: {
    flex: 1,
    // 官方登录页本身是白底,深色主题下也保持,免得页面加载间隙闪黑
    backgroundColor: '#FFFFFF',
  },
  noteWrap: {
    padding: theme.spacing.md,
    backgroundColor: theme.colors.bg,
  },
  // 琥珀色提示卡照设计稿写死:它压在白底登录页下面,不跟主题走
  note: {
    padding: theme.spacing.md,
    borderRadius: theme.radius.sm,
    backgroundColor: '#FFF8E6',
    borderWidth: 1,
    borderColor: '#F0E0B0',
    fontSize: 11.5,
    lineHeight: 18.4,
    color: '#8A6D1F',
  },
}));

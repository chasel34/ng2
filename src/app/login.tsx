import { useRouter } from 'expo-router';
import { useRef } from 'react';
import { Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { WebView, type WebViewMessageEvent } from 'react-native-webview';

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
 * 抓 cookie 的两种时机之一:页面内每 0.5s 轮询 document.cookie(MNGA 的节奏)。
 * 登录动作是页内 AJAX,cookie 由 JS 落下、不触发导航,只有轮询能第一时间看到。
 * 另一种时机是加载回调(Android v4 的做法),见 onLoadEnd 里的 injectJavaScript。
 */
const CAPTURE_COOKIES_JS = `
(function () {
  var post = function () {
    if (window.ReactNativeWebView && document.cookie.indexOf('ngaPassportUid') !== -1) {
      window.ReactNativeWebView.postMessage(document.cookie);
    }
  };
  post();
  if (!window.__ng2CookiePoll) {
    window.__ng2CookiePoll = setInterval(post, 500);
  }
})();
true;
`;

export default function LoginScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const webRef = useRef<WebView>(null);
  // 轮询会连发好几条同样的 cookie,只处理第一条,免得重复落账号/重复退场
  const captured = useRef(false);
  const add = useAccounts((state) => state.add);

  const onMessage = (event: WebViewMessageEvent) => {
    if (captured.current) return;
    const cookies = extractLoginCookies(event.nativeEvent.data);
    if (cookies === null) return;
    captured.current = true;
    const name =
      cookies.urlencodedUname === null ? null : decodeLoginUsername(cookies.urlencodedUname);
    const shownName = name ?? `UID ${cookies.uid}`;
    add({ uid: cookies.uid, cid: cookies.cid, name: shownName, loginAt: Date.now() });
    showToast(`已登录 ${shownName}`);
    router.back();
  };

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

      <WebView
        ref={webRef}
        source={{ uri: LOGIN_URL }}
        style={styles.web}
        // 独立无痕 cookie 罐:添加第二个账号时不会被上一个账号的登录态顶掉,
        // 也避免刚打开就把旧 cookie 当成"登录成功"
        incognito
        domStorageEnabled
        injectedJavaScript={CAPTURE_COOKIES_JS}
        onMessage={onMessage}
        // 抓 cookie 的另一种时机:每次页面加载完成再注入一次
        // (登录成功若走整页跳转,新文档里的轮询定时器要重新装上)
        onLoadEnd={() => webRef.current?.injectJavaScript(CAPTURE_COOKIES_JS)}
      />

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

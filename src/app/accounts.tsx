import { useRouter } from 'expo-router';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { formatCookieExpiry } from '@/core/account';
import { useAccounts } from '@/store/accounts';
import { avatarColorFor } from '@/ui/avatar';
import { Icon } from '@/ui/icon';
import { nameAbbrev } from '@/ui/initial';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { showToast } from '@/ui/toast';
import { TopBar, TopBarButton, TopBarTitle } from '@/ui/top-bar';

/** 账号管理页,布局与数值照设计稿 isAccounts 屏。 */
export default function AccountsScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const { accounts, currentUid, switchTo, logout } = useAccounts();
  const now = Date.now();

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
        <TopBarTitle variant="sub">账号管理</TopBarTitle>
      </TopBar>

      <ScrollView
        style={styles.body}
        contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + 24 }]}
      >
        {accounts.map((account) => {
          const isCurrent = account.uid === currentUid;
          return (
            <Pressable
              key={account.uid}
              style={[styles.card, isCurrent && styles.cardCurrent]}
              onPress={() => {
                if (isCurrent) return;
                switchTo(account.uid);
                showToast(`已切换到 ${account.name}`);
              }}
              accessibilityLabel={`切换到 ${account.name}`}
            >
              <View style={[styles.avatar, { backgroundColor: avatarColorFor(account.uid) }]}>
                <Text style={styles.avatarText} allowFontScaling={false}>
                  {nameAbbrev(account.name, 2)}
                </Text>
              </View>
              <View style={styles.info}>
                <Text style={styles.name} numberOfLines={1}>
                  {account.name}
                </Text>
                <Text style={styles.meta} numberOfLines={1}>
                  UID {account.uid} · cookie {formatCookieExpiry(account.loginAt, now)}
                </Text>
              </View>
              <Icon
                name={isCurrent ? 'radio_button_checked' : 'radio_button_unchecked'}
                size={22}
                color={isCurrent ? theme.colors.primary : theme.colors.meta}
              />
              <Pressable
                onPress={() => {
                  logout(account.uid);
                  showToast(`已退出 ${account.name}`);
                }}
                hitSlop={10}
                accessibilityLabel={`退出 ${account.name}`}
              >
                <Icon name="logout" size={20} color={theme.colors.danger} />
              </Pressable>
            </Pressable>
          );
        })}

        <Pressable
          style={styles.addButton}
          onPress={() => router.push('/login')}
          accessibilityLabel="添加账号"
        >
          <Icon name="person_add" size={21} color={theme.colors.primary} />
          <Text style={styles.addLabel}>添加账号</Text>
        </Pressable>

        <Text style={styles.hint}>
          登录多个账号可减少跳转系统浏览器的概率；抽屉头部左右滑动即可快速切换当前账号。
        </Text>
      </ScrollView>
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  root: {
    flex: 1,
    backgroundColor: theme.colors.bg,
  },
  body: {
    flex: 1,
  },
  content: {
    paddingTop: theme.spacing.row,
    paddingHorizontal: theme.spacing.md,
  },
  card: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 13,
    padding: theme.spacing.row,
    marginBottom: 10,
    borderRadius: theme.radius.lg,
    backgroundColor: theme.colors.surface,
    borderWidth: 1.5,
    borderColor: theme.colors.divider,
  },
  cardCurrent: {
    borderColor: theme.colors.primary,
  },
  avatar: {
    width: 46,
    height: 46,
    borderRadius: 15,
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarText: {
    ...theme.typography.avatarInitial,
    color: theme.colors.onPrimary,
  },
  info: {
    flex: 1,
    minWidth: 0,
  },
  name: {
    ...theme.typography.floorName,
    color: theme.colors.fg,
  },
  meta: {
    ...theme.typography.floorTime,
    color: theme.colors.meta,
    marginTop: theme.spacing.xs,
  },
  addButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 9,
    height: 48,
    borderRadius: theme.radius.lg,
    borderWidth: 1.5,
    borderStyle: 'dashed',
    borderColor: theme.colors.track,
  },
  addLabel: {
    ...theme.typography.accountAction,
    color: theme.colors.primary,
  },
  // 设计稿:12 号 · 1.65 行高
  hint: {
    ...theme.typography.floorTime,
    lineHeight: 19.8,
    color: theme.colors.meta,
    marginTop: theme.spacing.lg,
    paddingHorizontal: theme.spacing.xs,
  },
}));

import Constants from 'expo-constants';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Linking, Pressable, ScrollView, Text, View } from 'react-native';

import { Icon, type IconName } from '@/ui/icon';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { TopBar, TopBarButton, TopBarTitle } from '@/ui/top-bar';

/**
 * 关于(设计稿 `isAbout` 屏)。
 *
 * 版本号从 app.json 经 expo-constants 读,不写死——真机上「用户报的版本」与
 * 「装的那一版」对不上是最难查的一类问题。build 号取 Android 的 `versionCode`。
 *
 * 设计稿那五行是给一个公开发行的客户端画的(检查更新 / 开源地址 / 反馈问题 /
 * 开源许可 / 免责声明)。这个客户端只给自己用:没有更新服务器、没有仓库地址、
 * 也没有 issue 收件人,所以前三行换成本机说得出口的三件事——数据来源、系统授权、
 * 诊断日志——行的形状与顺序照设计稿不动。
 */

/** 设计稿底部那句免责声明。 */
const DISCLAIMER =
  '本客户端与 NGA 官方无关,仅供个人学习与自用。所有内容版权归原作者与 NGA 所有,不做任何分发。';

/**
 * 「免责声明」行展开后的全文。底部那句常驻页脚已经把短版摆着了,
 * 行里再展开同一句会像渲染重复(M4 验收缺陷 D4),所以这里给的是说全乎的长版。
 */
const DISCLAIMER_DETAIL =
  '本客户端是个人开发的第三方阅读工具,与 NGA(bbs.nga.cn)及其运营方没有任何关联。' +
  '所有帖子、图片、表情等内容的版权归原作者与 NGA 所有;本应用只做阅读呈现,' +
  '不缓存分发任何内容,也不提供公开下载。仅供个人学习与自用。';

/** 打包进 APK 的第三方组件,都是 package.json 里实际在用的。 */
const LICENSES = [
  'React Native · Expo SDK — MIT',
  'TanStack Query · Zustand · Legend List — MIT',
  'react-native-mmkv · reanimated · gesture-handler — MIT',
  'Material Icons Outlined — Apache-2.0',
] as const;

const DATA_SOURCE =
  '所有数据直接读 NGA 官方接口,不经任何中转服务;登录凭证只存在本机,不上传。';

interface AboutRow {
  key: string;
  icon: IconName;
  label: string;
  sub?: string;
  /** 点开在行下面展开的长文本;与 onPress 二选一 */
  detail?: string;
  onPress?: () => void;
}

export default function AboutScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();
  const [expanded, setExpanded] = useState<string | undefined>(undefined);

  const version = Constants.expoConfig?.version ?? '0.0.0';
  const build = Constants.expoConfig?.android?.versionCode ?? 0;

  const rows: readonly AboutRow[] = [
    {
      key: 'source',
      icon: 'code',
      label: '数据来源',
      sub: '直接读 NGA 官方接口',
      detail: DATA_SOURCE,
    },
    {
      key: 'links',
      icon: 'update',
      label: '系统设置',
      // M3 验收缺陷 1:Android 12+ 要用户自己在系统设置里开「打开支持的链接」,
      // NGA 域名不归我们控制,assetlinks.json 的自动验证走不通
      sub: '开「打开支持的链接」后,NGA 链接才会跳进本 app',
      onPress: () => void Linking.openSettings(),
    },
    {
      key: 'diagnostic',
      icon: 'bug_report',
      label: '诊断日志',
      sub: '接口失败的记录在「设置 · 实验室」里导出',
      onPress: () => router.push('/settings/lab'),
    },
    {
      key: 'licenses',
      icon: 'description',
      label: '开源许可',
      sub: `${LICENSES.length} 个第三方组件`,
      detail: LICENSES.join('\n'),
    },
    {
      key: 'disclaimer',
      icon: 'gavel',
      label: '免责声明',
      detail: DISCLAIMER_DETAIL,
    },
  ];

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
        <TopBarTitle variant="sub">关于</TopBarTitle>
      </TopBar>

      <ScrollView style={styles.body}>
        {/* 设计稿:76 见方的圆角方块 logo + 应用名 + 版本行,整块居中 */}
        <View style={styles.header}>
          <View style={styles.logo}>
            <Text style={styles.logoText} allowFontScaling={false}>
              NG
            </Text>
          </View>
          <Text style={styles.name}>NGA 阅读器</Text>
          <Text style={styles.version}>
            v{version} (build {build}) · 第三方客户端
          </Text>
        </View>

        {rows.map((row) => {
          const open = expanded === row.key;
          return (
            <View key={row.key}>
              <Pressable
                style={styles.row}
                onPress={
                  row.onPress ??
                  (() => setExpanded((current) => (current === row.key ? undefined : row.key)))
                }
                android_ripple={{ color: theme.colors.divider }}
                accessibilityRole="button"
                accessibilityLabel={row.label}
              >
                <Icon name={row.icon} size={21} color={theme.colors.fg2} />
                <View style={styles.rowText}>
                  <Text style={styles.rowLabel}>{row.label}</Text>
                  {row.sub !== undefined && (
                    <Text style={styles.rowSub} numberOfLines={2}>
                      {row.sub}
                    </Text>
                  )}
                </View>
                <Icon
                  name={row.detail === undefined ? 'chevron_right' : 'expand_more'}
                  size={20}
                  color={theme.colors.meta}
                />
              </Pressable>
              {open && row.detail !== undefined && (
                <Text style={styles.detail}>{row.detail}</Text>
              )}
            </View>
          );
        })}

        <Text style={styles.disclaimer}>{DISCLAIMER}</Text>
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
  /** 设计稿:34 上 / 20 左右 / 26 下,整块居中 */
  header: {
    alignItems: 'center',
    paddingTop: 34,
    paddingHorizontal: theme.spacing.xl,
    paddingBottom: 26,
  },
  logo: {
    width: 76,
    height: 76,
    borderRadius: theme.radius.dialog,
    backgroundColor: theme.colors.primary,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: theme.spacing.row,
  },
  logoText: {
    fontSize: 26,
    fontWeight: '700',
    color: theme.colors.onPrimary,
  },
  name: {
    ...theme.typography.dialogTitle,
    color: theme.colors.fg,
  },
  version: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
    marginTop: 6,
  },
  /** 设计稿:gap 14、内距 14/18、底分隔线 */
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.row,
    paddingVertical: theme.spacing.row,
    paddingHorizontal: theme.spacing.page,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  rowText: {
    flex: 1,
    minWidth: 0,
  },
  rowLabel: {
    ...theme.typography.drawerItem,
    color: theme.colors.fg,
  },
  rowSub: {
    ...theme.typography.cardMeta,
    color: theme.colors.meta,
    marginTop: 3,
  },
  /** 展开的长文本:缩进到与行文字对齐,底色压一档区分层级 */
  detail: {
    ...theme.typography.notice,
    color: theme.colors.fg2,
    backgroundColor: theme.colors.surface2,
    paddingVertical: theme.spacing.md,
    paddingLeft: theme.spacing.page + 21 + theme.spacing.row,
    paddingRight: theme.spacing.page,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  /** 设计稿:20 内距、11.5 · 1.7、居中 */
  disclaimer: {
    ...theme.typography.meta,
    lineHeight: 19.55,
    color: theme.colors.meta,
    textAlign: 'center',
    padding: theme.spacing.xl,
  },
}));

import { useLocalSearchParams, useRouter } from 'expo-router';
import { Text, View } from 'react-native';

import { createThemedStyles } from '@/ui/theme';
import { TopBar, TopBarButton, TopBarTitle } from '@/ui/top-bar';

/**
 * 帖子详情页的占位。真正的详情是 07 票;这里先把路由与参数约定固定下来
 * (和 04 给主题列表留占位是同一个套路):
 *
 * - `tid` —— 真实 tid(列表层已按 `quote_from` 换算过,CONTEXT.md「主题」)
 * - `fav` —— fav 码(CONTEXT.md「fav 码」),有就要带,否则隐藏/过期主题打不开
 * - `title` —— 顶栏标题,免得进页面还要等 read.php 回来
 */
export default function TopicScreen() {
  const styles = useStyles();
  const router = useRouter();
  const { tid, title, fav } = useLocalSearchParams<{
    tid: string;
    title?: string;
    fav?: string;
  }>();

  return (
    <View style={styles.root}>
      <TopBar paddingHorizontal={4}>
        <TopBarButton
          icon="arrow_back"
          size={24}
          onPress={() => router.back()}
          accessibilityLabel="返回"
        />
        <TopBarTitle variant="sub">{title ?? `主题 ${tid}`}</TopBarTitle>
      </TopBar>
      <View style={styles.center}>
        <Text style={styles.placeholder}>帖子详情还没做(07 票)</Text>
        <Text style={styles.detail}>
          tid = {tid}
          {fav === undefined ? '' : ` · fav = ${fav}`}
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
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: theme.spacing.sm,
  },
  placeholder: {
    ...theme.typography.notice,
    color: theme.colors.fg2,
  },
  detail: {
    ...theme.typography.meta,
    color: theme.colors.meta,
  },
}));

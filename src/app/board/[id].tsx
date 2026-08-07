import { useLocalSearchParams, useRouter } from 'expo-router';
import { Text, View } from 'react-native';

import { createThemedStyles } from '@/ui/theme';
import { TopBar, TopBarButton, TopBarTitle } from '@/ui/top-bar';

/**
 * 主题列表页的占位。真正的列表是 05 票;这里先把路由与参数约定固定下来:
 *
 * - `id` —— 版块 id,合集是 stid、普通版块是 fid(stid 优先,CONTEXT.md「合集」)
 * - `kind` —— `collection` / `board`,决定 thread.php 传 stid 还是 fid
 * - `name` —— 顶栏标题,免得进页面还要等分类树查一次名字
 */
export default function BoardScreen() {
  const styles = useStyles();
  const router = useRouter();
  const { id, name, kind } = useLocalSearchParams<{
    id: string;
    name?: string;
    kind?: string;
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
        <TopBarTitle variant="sub">{name ?? `版块 ${id}`}</TopBarTitle>
      </TopBar>
      <View style={styles.center}>
        <Text style={styles.placeholder}>主题列表还没做(05 票)</Text>
        <Text style={styles.detail}>
          {kind === 'collection' ? 'stid' : 'fid'} = {id}
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

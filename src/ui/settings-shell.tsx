import { useRouter } from 'expo-router';
import type { ReactNode } from 'react';
import { ScrollView, View } from 'react-native';

import { ProgressiveChildren } from './progressive';
import { createThemedStyles } from './theme';
import { TopBar, TopBarButton, TopBarTitle } from './top-bar';

export interface SettingsShellProps {
  /** 顶栏标题 */
  title: string;
  children: ReactNode;
  /**
   * 本屏的对话框。单开一个口子而不是混在 `children` 里,是因为对话框的根容器是
   * `position:absolute` 四边贴 0——贴的是**父容器**。混在行里它就贴着滚动内容,
   * 居中的面板会被摆到内容中段(一屏到底之后是 1850dp 的中间),滚到别处就看不见,
   * 只剩铺满的遮罩。放在滚动容器外面才是贴着视口。
   */
  overlays?: ReactNode;
}

/**
 * 设置页的外壳:顶栏「← 标题」+ 一列可滚的行。
 *
 * 这里原本是个三屏向导(顶栏右上角 N/3、页脚一对「上一屏 / 下一屏」、屏间 `replace`)。
 * 拆掉的理由:向导是给「按顺序走完才算数」的流程用的,而设置是随机访问的——
 * 用户带着「我要关签名档」进来,要的是滚+找,不是猜它排在第几屏;而且屏间用
 * `replace` 拍平返回栈之后,从第 3 屏按系统返回会直接退出设置而不是回第 2 屏
 * (用 `push` 则堆成三层设置,退出要按三次)。这个两难是向导模型套在设置上必然
 * 产生的,一屏到底就没有。页脚那对按钮同时也是冗余:顶栏返回箭头 + 系统返回手势
 * 已经两个入口,而设置项即时生效,没有「完成」这一步。
 */
export function SettingsShell({ title, children, overlays }: SettingsShellProps) {
  const styles = useStyles();
  const router = useRouter();

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
        <TopBarTitle variant="sub">{title}</TopBarTitle>
      </TopBar>

      <ScrollView style={styles.body} contentContainerStyle={styles.content}>
        {/* 分帧揭示:一屏二十几行(自绘开关每行好几个视图)同步挂载要 16~19ms,
            push 动画第 1 帧就掉帧。行成本 ~2.5ms:2026-08-15 atrace 实测 step=3 时
            每帧 mount 3~6.5ms + traversal ~3ms,压着 120Hz 的 8.3ms 预算线仍偶发丢帧;
            降到首帧 1 行、每帧 +2(~5+3ms)才留得出余量。合并成一屏后是 26 个子节点、
            13 帧 ≈ 108ms,仍在 220ms 动画走完之前全就位 */}
        <ProgressiveChildren initial={1} step={2}>
          {children}
        </ProgressiveChildren>
      </ScrollView>

      {overlays}
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
  // 最后一行的分隔线不该贴着屏幕底边
  content: {
    paddingBottom: 30,
  },
}));

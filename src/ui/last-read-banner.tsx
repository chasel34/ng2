import { useEffect } from 'react';
import { Pressable, Text, View } from 'react-native';
import Reanimated, {
  interpolate,
  runOnJS,
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';

import { Icon } from './icon';
import { duration, easeStandardWorklet, RISE_OFFSET } from './motion';
import { createThemedStyles, useTheme } from './theme';

/**
 * 「上次读到第 N 楼」提示条(设计稿 progressTip),浮层版。
 *
 * 原先它是列表的 `ListHeaderComponent`,整条把楼层往下顶了一格,而且不会自己走;
 * 现在改成压在楼层列表上方的浮层:**不占布局**(容器绝对定位、`box-none` 不吃手势),
 * 一滚动 / 一翻页 / 5 秒没人理就淡出。什么时候该在由调用方(详情页的
 * `useReadingProgress`)裁决,这里只负责把 `visible` 翻译成进出场动画。
 *
 * 淡出后不卸载、只是留一层透明且 `pointerEvents:'none'` 的空壳:退场动画得跑完,
 * 而「animation 完成回调里 runOnJS 卸自己」既要多一份 state 又要处理动画被打断,
 * 换来的只是省下一个绝对定位、不参与布局的小 View——不划算。
 *
 * 进场照设计稿 progressTip:`.28s` 上浮 14px 淡入(omup);退场用短一档(`.2s`)
 * 原路淡回去——退场比进场快是通例,让位给用户正在做的那件事(滚动/翻页)。
 * 进场动画跑完会回一声 `onShown`:5 秒兜底得从「真的亮在屏上」起算,见下面的注释。
 *
 * 卡片本身要能从正文里分出来:浮层没有「跟着内容滚」这层暗示了,只靠 primary-c
 * 底会跟楼层卡糊在一起,所以补一档 elevation2 阴影(菜单/浮层那一档)。
 */
export function LastReadBanner({
  floor,
  visible,
  onShown,
  onJump,
  onClose,
}: {
  /** 上次读到的楼层号。退场动画期间父级也要继续给出它,不然淡出中的文字会闪空 */
  floor: number;
  visible: boolean;
  /**
   * 进场动画**在 UI 线程上真的跑完了**——也就是这张卡确实亮在屏幕上了。
   * 引用必须稳定(effect 的依赖),父级用它起算「5 秒没人理就自己走」。
   */
  onShown: () => void;
  onJump: () => void;
  onClose: () => void;
}) {
  const styles = useStyles();
  const theme = useTheme();
  const progress = useSharedValue(0);

  useEffect(() => {
    progress.value = withTiming(
      visible ? 1 : 0,
      {
        duration: visible ? duration.notice : duration.base,
        easing: easeStandardWorklet,
      },
      (finished) => {
        'worklet';
        // 只有进场那次、且没被打断,才回报「上屏了」。这个回调跑在 UI 线程的帧回调里:
        // UI 线程被首屏挂载堵住时动画根本不前进,回调自然跟着晚——正是我们要的判据
        if (finished === true && visible) runOnJS(onShown)();
      },
    );
  }, [visible, progress, onShown]);

  const riseStyle = useAnimatedStyle(() => ({
    opacity: progress.value,
    transform: [{ translateY: interpolate(progress.value, [0, 1], [RISE_OFFSET, 0]) }],
  }));

  return (
    // 浮层容器铺满列表区但不吃手势(box-none):楼层列表照常滚、照常长按
    <View style={styles.layer} pointerEvents="box-none">
      {/* 卡片自己也 box-none:手指落在提示条空白处往上一划,这一划要能落到
          底下的列表上去滚起来(滚起来正好把提示条淡掉)。只有两个 Pressable 吃点击 */}
      <Reanimated.View
        style={[styles.banner, riseStyle]}
        pointerEvents={visible ? 'box-none' : 'none'}
      >
        <Icon name="bookmark" size={19} color={theme.colors.primary} />
        <Text style={styles.text}>
          上次读到 <Text style={styles.strong}>第 {floor} 楼</Text>
        </Text>
        <Pressable onPress={onJump} accessibilityLabel={`回到第 ${floor} 楼`}>
          <Text style={styles.action}>回到那里</Text>
        </Pressable>
        <Pressable onPress={onClose} accessibilityLabel="关闭提示" hitSlop={8}>
          <Icon name="close" size={17} color={theme.colors.meta} />
        </Pressable>
      </Reanimated.View>
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  /** 贴着列表区顶边(也就是页码条/提示条下面)铺开,自身不占布局 */
  layer: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
  },
  /** 设计稿 progressTip:外距 10 12、内距 11 12 11 14、圆角 12、primary-c 底 */
  banner: {
    marginTop: 10,
    marginHorizontal: theme.spacing.md,
    paddingVertical: 11,
    paddingLeft: theme.spacing.row,
    paddingRight: theme.spacing.md,
    borderRadius: theme.radius.md,
    backgroundColor: theme.colors.primaryContainer,
    boxShadow: theme.shadows.elevation2,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  text: {
    ...theme.typography.resumeTip,
    color: theme.colors.fg,
    flex: 1,
  },
  strong: {
    fontWeight: '700',
  },
  action: {
    ...theme.typography.resumeTip,
    fontWeight: '700',
    color: theme.colors.primary,
    paddingVertical: theme.spacing.xs,
    paddingHorizontal: 6,
  },
}));

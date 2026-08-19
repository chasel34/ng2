import { useCallback, useLayoutEffect, useMemo, useRef, type ReactNode } from 'react';
import { StyleSheet, useWindowDimensions, View, type StyleProp, type ViewStyle } from 'react-native';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import Reanimated, {
  cancelAnimation,
  runOnJS,
  useAnimatedReaction,
  useAnimatedStyle,
  useSharedValue,
  withSpring,
  type SharedValue,
} from 'react-native-reanimated';

import { horizontalDragActive } from './horizontal-drag';
import {
  SWIPE_ACTIVATE,
  SWIPE_AXIS_RATIO,
  swipeOffset,
  swipeTargetPage,
} from './paging';

/**
 * 收尾弹簧。用弹簧而不是定时曲线,是拿真机录屏跟原生 ViewPager 逐帧对拍出来的:
 * 定时动画不吃松手速度,轻拖和猛甩都是同一条 260ms 曲线,松手瞬间速度不连续,
 * 手感就是「甩出去它自己另起一套」。弹簧带 `velocity` 起步,从手指速度平滑接管。
 * 参数取略过阻尼(临界阻尼 ≈ 2√stiffness ≈ 44.7),不回弹,尾巴 ~350ms,
 * 与 ViewPager2 的 fling 衰减曲线形状对上。
 */
const SETTLE_SPRING = {
  stiffness: 500,
  damping: 48,
  mass: 1,
  overshootClamping: true,
  restDisplacementThreshold: 0.5,
  restSpeedThreshold: 50,
} as const;

/**
 * 调用方没把 `page` 改成给的值时,多久之后把轨道拉回 React 真正落在的页。
 * 正常路径上 `page` 一变 pending 就清了,这里只是别让界面停在没人认账的页上。
 */
const COMMIT_GUARD_MS = 400;

export interface SwipePagerProps {
  /** 当前第几页,从 1 起 */
  page: number;
  /** 总共几页 */
  count: number;
  /**
   * 翻页。**必须真的把 `page` 改成给进来的值**——画面在手势松手那一刻就已经
   * 停在目标页上了,这次回调只是让 React 把面板窗口挪一格;吞掉的话有兜底,
   * `COMMIT_GUARD_MS` 后轨道会跳回 `page` 实际的值(会闪一下)。
   */
  onChange: (page: number) => void;
  /**
   * 松手定向那一刻就回调(ViewPager2 `onPageSelected` 的时机),动画还在滑。
   * 给 tab 高亮、页码条这类**轻量**指示 UI 用——重活别放这儿,收尾动画正跑着,
   * 这里挂大渲染就是把卡顿塞回动画窗口(`onChange` 才是干重活的地方)。
   */
  onTarget?: (page: number) => void;
  /** 画第 N 页。相邻两页也会被调到,数据没准备好就自己画个占位 */
  renderPage: (page: number) => ReactNode;
  /**
   * 左边缘留给别人的一条(首页的抽屉边缘手势)。从这条里起手的一律不认领。
   */
  edgeGuard?: number;
  /**
   * 连续翻页进度(0 起的浮点页位,跟手实时变),给 tab 指示器这类要与内容
   * 同帧联动的 UI 用。全程 UI 线程,不回 JS。
   */
  progress?: SharedValue<number>;
  style?: StyleProp<ViewStyle>;
}

/**
 * 横滑翻页的轨道。
 *
 * 相邻两页真的贴在当前页两边跟着一起走——这是「无缝」的全部含义。
 * 两个使用者:帖子详情按页翻,首页按分类 tab 翻。翻页算术全部走 `ui/paging`,
 * 与页码条、跳页对话框共用同一套规则。
 *
 * **页码真值归 UI 线程**(`pageSV`),这是本组件的立身之本:
 * 每块面板固定在自己的绝对位置 `left = (页号-1) * 屏宽`,轨道位移
 * `-(pageSV-1) * 屏宽 + drag` 全程由 UI 线程持有。松手翻页时,worklet 在**同一帧**
 * 里改 `pageSV` 并给 `drag` 重定基——数学上位移不变,画面纹丝不动;React 的
 * commit 只是随后把三页窗口挪一格(面板以页号为 key,可见那块换页前后是同一个
 * 实例)。旧实现的页码真值在 JS、靠「commit 后复位轨道」缝合,而 shared value
 * 写入与 Fabric 提交不同帧(真机实测差 4 帧),缝合处就是闪烁与连滑竞态的根。
 *
 * 收尾动画期间再次起手会**半路接管**:冻住当时的位移接着滑(页码真值早翻过去了,
 * 不存在读到旧页的窗口),快速连滑既不丢页也不会跳回旧页。
 *
 * 手势判定用 `manualActivation` 自己算而不是 `activeOffsetX`/`failOffsetY`:
 * 要的条件是「横向位移**压过**纵向」这个比例关系,原生阈值表达不了;而且自己算
 * 才有地方在认领前看一眼 `horizontalDragActive`——楼层里那张能横滚的表格正被拖着时,
 * 这一把要整个让给它(ui/horizontal-drag)。
 */
export function SwipePager({
  page,
  count,
  onChange,
  onTarget,
  renderPage,
  edgeGuard = 0,
  progress,
  style,
}: SwipePagerProps) {
  const { width } = useWindowDimensions();
  /** 页码真值,1 起。只在两处写:手势 onEnd 的 worklet、外部换页的同步 */
  const pageSV = useSharedValue(page);
  /** 离当前页基准位置的位移。跟手与收尾动画都走它 */
  const drag = useSharedValue(0);
  /** 半路接管收尾动画时,冻结那一刻的位移,新一把在它之上累加 */
  const dragBase = useSharedValue(0);
  /** 收尾动画跑着(可被下一次按下打断接管) */
  const settling = useSharedValue(false);
  /**
   * 翻了页、还没告诉 React 的目标页(0 = 没有)。commit **推迟到动画停稳**才发:
   * React 重渲染 + Fabric mount 会占住 UI 线程 2–4 帧,发在滑行中段的话,
   * 时间驱动的动画掉帧后直接跳位置追帧——真机录屏里就是一次 35ms 冻结接一帧
   * 140px 的瞬移。停稳后画面静止,这笔账就看不见了。半路被接管时当场补发,
   * 连滑要的下下页面板不会等太久。
   */
  const pendingCommit = useSharedValue(0);
  /** 按下时的触点。位移一律按「离按下点多远」算 */
  const origin = useSharedValue({ x: 0, y: 0 });
  // 手势跑在 UI 线程上,读不到 React 的最新值:总页数、屏宽、边缘让位镜像过去
  //(页码不用镜像——pageSV 本身就是真值)
  const geom = useSharedValue({ count, width, edgeGuard });

  // onChange 每渲染都是新的,而 worklet 那边要的是一个终生不变的入口
  const changeRef = useRef(onChange);
  changeRef.current = onChange;
  const targetRef = useRef(onTarget);
  targetRef.current = onTarget;
  const notifyTarget = useCallback((target: number) => {
    targetRef.current?.(target);
  }, []);
  const propPage = useRef(page);
  propPage.current = page;
  /** 已提交、还没看到 React 兑现的目标页 */
  const pending = useRef<number | null>(null);

  const commit = useCallback(
    (target: number) => {
      pending.current = target;
      changeRef.current(target);
      // 调用方吞掉/夹住时的兜底:把轨道拉回 React 真正落在的页
      setTimeout(() => {
        if (pending.current !== target) return;
        pending.current = null;
        cancelAnimation(drag);
        pageSV.value = propPage.current;
        drag.value = 0;
        settling.value = false;
      }, COMMIT_GUARD_MS);
    },
    [drag, pageSV, settling],
  );

  useLayoutEffect(() => {
    geom.value = { count, width, edgeGuard };
  }, [count, width, edgeGuard, geom]);

  /**
   * `page` 属性变了。两种来源:自己 commit 的回声(什么都不用做,画面早就停在
   * 目标页上),或外部换页(页码条、跳页对话框)——那种情况直接落到新页,
   * 不做滑动动画,与旧行为一致。连滑时中间那次 commit 的回声可能晚到:
   * 只要还有 pending,一律不当外部换页处理(真正的外部换页撞上 pending 的
   * 极端情况由 `COMMIT_GUARD_MS` 兜底拉回)。
   */
  useLayoutEffect(() => {
    if (pending.current !== null) {
      if (pending.current === page) pending.current = null;
      return;
    }
    cancelAnimation(drag);
    pageSV.value = page;
    drag.value = 0;
    settling.value = false;
    pendingCommit.value = 0;
  }, [page, drag, pageSV, settling, pendingCommit]);

  // tab 指示器要的连续页位。挂在共享值上而不是回调:全程 UI 线程,零 JS 往返
  const progressFallback = useSharedValue(0);
  const progressOut = progress ?? progressFallback;
  useAnimatedReaction(
    () => pageSV.value - 1 - drag.value / Math.max(1, geom.value.width),
    (position) => {
      progressOut.value = position;
    },
    [progressOut],
  );

  const gesture = useMemo(
    () =>
      Gesture.Pan()
        .manualActivation(true)
        // 只认第一根手指落下的那一点:后来的手指再下来不该把起点挪走
        .onTouchesDown((event) => {
          const touch = event.changedTouches[0];
          if (event.numberOfTouches === 1 && touch !== undefined) {
            origin.value = { x: touch.absoluteX, y: touch.absoluteY };
            // 收尾动画还在跑:当场冻住接管(原生 pager 的手感)。页码真值在上一把
            // 的 onEnd 里已经翻过去了,这一把就是从新页的余位上继续,没有旧页可读
            if (settling.value) {
              cancelAnimation(drag);
              settling.value = false;
              // 上一页还欠着的 commit 现在就补:接下来大概率要翻下下页,
              // React 那边得赶紧把窗口挪过来。手指压着时那点 mount 卡顿
              // 落在跟手阶段,位置由手指钉着,不会瞬移
              const owed = pendingCommit.value;
              if (owed !== 0) {
                pendingCommit.value = 0;
                runOnJS(commit)(owed);
              }
            }
            dragBase.value = drag.value;
          }
        })
        .onTouchesMove((event, manager) => {
          const touch = event.allTouches[0];
          if (touch === undefined) return;
          // 楼层里的表格已经在横滚了:这一把整个让给它(ui/horizontal-drag)
          if (horizontalDragActive.value) {
            manager.fail();
            return;
          }
          // 左边缘那一条是抽屉的地盘(首页),从那儿起手的一律不接
          if (origin.value.x <= geom.value.edgeGuard) {
            manager.fail();
            return;
          }
          const dx = touch.absoluteX - origin.value.x;
          const dy = touch.absoluteY - origin.value.y;
          // 认领不了就一直不认领(不主动 fail):斜着起手后又转成横滑的也还能翻页
          if (Math.abs(dx) >= SWIPE_ACTIVATE && Math.abs(dx) > Math.abs(dy) * SWIPE_AXIS_RATIO) {
            manager.activate();
          }
        })
        .onUpdate((event) => {
          const { count: total, width: screen } = geom.value;
          const dx = event.absoluteX - origin.value.x;
          // 只挂了相邻一页:位移压到一屏以内,再远就没有内容可看了
          const reach = Math.max(-screen, Math.min(screen, dragBase.value + dx));
          drag.value = swipeOffset(pageSV.value, reach, total);
        })
        .onEnd((event) => {
          const { count: total, width: screen } = geom.value;
          const current = pageSV.value;
          const dx = event.absoluteX - origin.value.x;
          const reach = Math.max(-screen, Math.min(screen, dragBase.value + dx));
          // gesture-handler 给的是 px/s,paging 那边按 px/ms 判
          const target = swipeTargetPage(current, reach, dx, total, screen, event.velocityX / 1000);
          if (target !== current) {
            // 真值当场翻页 + drag 同帧重定基:-(page-1)*w + drag 的和不变,画面连续。
            // React 那趟 commit 什么时候到、到不到,都不再影响画面
            pageSV.value = target;
            drag.value = drag.value + (target - current) * screen;
            pendingCommit.value = target;
            runOnJS(notifyTarget)(target);
          }
          settling.value = true;
          drag.value = withSpring(
            0,
            { ...SETTLE_SPRING, velocity: event.velocityX },
            (finished) => {
              // 没跑完 = 被下一次按下半路接管了,settling 和欠着的 commit 都由那边收
              if (finished !== true) return;
              settling.value = false;
              const owed = pendingCommit.value;
              if (owed !== 0) {
                pendingCommit.value = 0;
                runOnJS(commit)(owed);
              }
            },
          );
        })
        // 被别的手势顶掉、或者压根没认领成的收尾(包括「按下冻住了收尾动画、
        // 结果那一下是纵向滚动/点击」的情况——冻在半路的位移也从这儿放回去)。
        // 没认领成且位移为零时这里是空转,纵向滚动的那条路上一句 JS 都不跑
        .onFinalize((_event, success) => {
          if (success === true || settling.value) return;
          if (drag.value !== 0) {
            settling.value = true;
            drag.value = withSpring(0, SETTLE_SPRING, (finished) => {
              if (finished === true) settling.value = false;
            });
          }
        }),
    [commit, drag, dragBase, geom, notifyTarget, origin, pageSV, pendingCommit, settling],
  );

  const trackStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: -(pageSV.value - 1) * width + drag.value }],
  }));

  // 面板以页号为 key、钉在自己的绝对位置上:换页前后可见那块是**同一个实例**,
  // commit 只是窗口挪一格(远端卸一块、另一端挂一块,都在屏幕外),没有任何复位
  const first = Math.max(1, page - 1);
  const last = Math.min(count, page + 1);
  const panes: ReactNode[] = [];
  for (let p = first; p <= last; p += 1) {
    panes.push(
      <View key={p} style={[styles.pane, { width, left: (p - 1) * width }]}>
        {renderPage(p)}
      </View>,
    );
  }

  return (
    <GestureDetector gesture={gesture}>
      <View style={[styles.viewport, style]}>
        {/* 轨道宽度必须铺满**全部**页位:Android 的原生触摸分发按子 view 的布局
            边界裁剪(transform 会被逆变换回去再比对),面板落在轨道边界外的话,
            里面的 ScrollView 一个 move 都收不到——点击(走 RN 自己的命中测试)
            照常好使,唯独原生纵向滚动全灭,真机上栽过。宽度是整数像素,
            几百页也远在 float 精度之内 */}
        <Reanimated.View style={[styles.track, { width: count * width }, trackStyle]}>
          {panes}
        </Reanimated.View>
      </View>
    </GestureDetector>
  );
}

const styles = StyleSheet.create({
  /** 相邻页就贴在两边,不裁掉的话它们会画到屏幕外面去 */
  viewport: {
    flex: 1,
    overflow: 'hidden',
  },
  track: {
    position: 'absolute',
    left: 0,
    top: 0,
    bottom: 0,
  },
  pane: {
    position: 'absolute',
    top: 0,
    bottom: 0,
  },
});

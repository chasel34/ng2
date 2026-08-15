/**
 * 「手指正落在一个能横向滚的东西上」这一个事实。
 *
 * 详情页的左右滑动翻页(`Gesture.Pan`)是祖先手势:楼层里那张能横向滚的表格
 * 隔着列表与渲染器好几层,子孙层面无论怎么写都抢不回来,拖十几像素就翻页了。
 * gesture-handler 原生的表达方式是 `blocksExternalGesture`,但那要拿到表格那个
 * ScrollView 的 ref(`ui/bbcode/blocks.tsx`),跨这么多层传 ref 反而更脆。
 *
 * 所以反过来,由横向滚的那一方在按下时打个招呼,翻页那边认领手势前看一眼就放行这一次。
 * 用模块级计数而不是 context:两边隔着好几层,而这里要的只是「按下的这一瞬间」的状态,
 * 根本不需要触发重渲染。
 *
 * 计数是 JS 线程的,翻页手势的判定却跑在 UI 线程上,所以另外镜像一份共享值——
 * 判定发生在「手指已经走了 12px」那一刻,JS 线程在按下时写的值早就同步过去了。
 */

import { makeMutable } from 'react-native-reanimated';

let depth = 0;

/**
 * 给 UI 线程读的镜像。只在这里写,外面(翻页手势的 worklet)只读。
 */
export const horizontalDragActive = makeMutable(false);

/** 手指按到了一个横向滚动容器上。 */
export function beginHorizontalDrag(): void {
  depth += 1;
  horizontalDragActive.value = true;
}

/** 手指离开了。多点触摸会成对进出,所以用计数而不是布尔。 */
export function endHorizontalDrag(): void {
  depth = Math.max(0, depth - 1);
  horizontalDragActive.value = depth > 0;
}

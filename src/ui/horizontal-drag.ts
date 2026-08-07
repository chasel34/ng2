/**
 * 「手指正落在一个能横向滚的东西上」这一个事实。
 *
 * 详情页的左右滑动翻页用 PanResponder 在**捕获阶段**认领手势(不这么做的话
 * FlashList 里的 ScrollView 会先把手势抢走,横滑就再也认不到)。代价是祖先永远先手:
 * 楼层里那张能横向滚的表格,子孙层面无论怎么写都抢不回来,拖十几像素就翻页了。
 *
 * 所以反过来,由横向滚的那一方在按下时打个招呼,翻页那边看到就放行这一次手势。
 * 用模块级计数而不是 context:两边隔着 FlashList 和渲染器好几层,
 * 而这里要的只是「按下的这一瞬间」的状态,根本不需要触发重渲染。
 */

let depth = 0;

/** 手指按到了一个横向滚动容器上。 */
export function beginHorizontalDrag(): void {
  depth += 1;
}

/** 手指离开了。多点触摸会成对进出,所以用计数而不是布尔。 */
export function endHorizontalDrag(): void {
  depth = Math.max(0, depth - 1);
}

/** 翻页手势在认领之前问一句:现在该不该让位。 */
export function isHorizontalDragActive(): boolean {
  return depth > 0;
}

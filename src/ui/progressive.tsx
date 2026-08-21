import { Children, useEffect, useState, type ReactNode } from 'react';

/**
 * 分帧揭示(2026-08-15 帧流水线排查第二轮)。
 *
 * push 转场的第 1 帧同步挂载整屏内容,在 120Hz 上一帧要吃 19~35ms(设置屏/版块屏),
 * 动画起步直接掉 2~5 个 vsync——这是「初次进入卡顿」的主因。整屏延后挂载(topic 屏
 * 的 252ms 方案)会让内容晚一拍才出现;这里选另一头:**内容照常从第一帧开始出,
 * 但每帧只挂一小片**,横推动画还没走完时就全部就位,肉眼看不出分片,单帧成本
 * 却被钳在预算附近。
 *
 * 揭示只往前走(`total` 变化时向新值追赶),翻页追加的行反正在视口外,晚几帧
 * 无感;下拉刷新替换数据时长度不变,直接透传。
 */

export interface RevealOptions {
  /** 首帧就挂出来的条数——大致取视口顶上立即可见的那几条 */
  initial: number;
  /** 之后每帧追加的条数,按单条挂载成本控制在 ~8ms 预算内 */
  step: number;
  /**
   * 变化时进度回到 `initial` 重新追赶。给「同一个实例先后装不同数据」的场合用
   * (翻页面板从骨架换成真数据、回收行重绑):不带它时揭示只前进,数据整换后
   * 仍是全量透传,新内容在同一帧里全部挂载——正是 2026-08-21 真机抓到的
   * 翻页 40ms 大帧。选 key 要避开「同一份内容原地刷新」(那时重置会把已挂载的
   * 行收回去再挂一遍,画面闪)。
   */
  resetKey?: unknown;
  /**
   * 为 true 时直接全量透传(scrollToIndex 这类要求任意行都在场的场合)。
   * 内部进度同步拉满:之后回到 false 也不会把已经挂出去的行收回去。
   */
  skip?: boolean;
}

/** 纯推进逻辑,单独抽出来给测试用:一步从 `revealed` 走到哪。 */
export function nextRevealCount(revealed: number, total: number, step: number): number {
  if (revealed >= total) return revealed;
  return Math.min(total, revealed + step);
}

/**
 * 返回当前应当渲染的条数,每帧向 `total` 追赶一步。
 * 调用方用它 slice 数据;追平后请直接透传原数组,保持引用稳定。
 */
export function useProgressiveReveal(
  total: number,
  { initial, step, resetKey, skip }: RevealOptions,
): number {
  const [state, setState] = useState({ key: resetKey, revealed: skip === true ? total : initial });
  // resetKey 变了 = 同一个实例装上了另一份数据:渲染期重置(React 认可的
  // derived-state 写法,这一趟渲染随即被丢弃重来),本帧就按新进度算
  let current = state;
  if (!Object.is(state.key, resetKey)) {
    current = { key: resetKey, revealed: skip === true ? total : initial };
    setState(current);
  } else if (skip === true && current.revealed < total) {
    // skip 期间进度同步拉满(同样是渲染期重置):skip 撤掉后不回退,已挂载的行不收回
    current = { ...current, revealed: total };
    setState(current);
  }
  const revealed = current.revealed;
  const done = revealed >= total;

  useEffect(() => {
    if (done || skip === true) return;
    const id = requestAnimationFrame(() => {
      setState((prev) => ({ ...prev, revealed: nextRevealCount(prev.revealed, total, step) }));
    });
    return () => cancelAnimationFrame(id);
  }, [done, total, step, revealed, skip]);

  return done ? total : revealed;
}

export interface ProgressiveChildrenProps extends RevealOptions {
  children: ReactNode;
}

/**
 * 静态内容版(设置页这类「一列固定行」):子元素按序分帧挂载。
 * 行数固定、无重排,`Children.toArray` 的顺位 key 足够稳定。
 */
export function ProgressiveChildren({ initial, step, children }: ProgressiveChildrenProps) {
  const items = Children.toArray(children);
  const revealed = useProgressiveReveal(items.length, { initial, step });
  return <>{revealed >= items.length ? items : items.slice(0, revealed)}</>;
}

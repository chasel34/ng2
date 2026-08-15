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
export function useProgressiveReveal(total: number, { initial, step }: RevealOptions): number {
  const [revealed, setRevealed] = useState(initial);
  const done = revealed >= total;

  useEffect(() => {
    if (done) return;
    const id = requestAnimationFrame(() => {
      setRevealed((current) => nextRevealCount(current, total, step));
    });
    return () => cancelAnimationFrame(id);
  }, [done, total, step, revealed]);

  return done ? total : revealed;
}

export interface ProgressiveChildrenProps extends RevealOptions {
  children: ReactNode;
}

/**
 * 静态内容版(设置三屏这类「一列固定行」):子元素按序分帧挂载。
 * 行数固定、无重排,`Children.toArray` 的顺位 key 足够稳定。
 */
export function ProgressiveChildren({ initial, step, children }: ProgressiveChildrenProps) {
  const items = Children.toArray(children);
  const revealed = useProgressiveReveal(items.length, { initial, step });
  return <>{revealed >= items.length ? items : items.slice(0, revealed)}</>;
}

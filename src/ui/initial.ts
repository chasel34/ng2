/**
 * 取名字的首字,用于版块图标占位与分组角标。
 *
 * 用 `Array.from` 而不是 `[0]`:名字里可能有 emoji 或生僻字,
 * 按 UTF-16 码元切会劈出半个代理对,渲染成豆腐块。
 */
export function initialOf(name: string): string {
  return Array.from(name.trim())[0] ?? '#';
}

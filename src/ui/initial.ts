/**
 * 取名字的首字,用于版块图标占位与分组角标。
 *
 * 用 `Array.from` 而不是 `[0]`:名字里可能有 emoji 或生僻字,
 * 按 UTF-16 码元切会劈出半个代理对,渲染成豆腐块。
 */
export function initialOf(name: string): string {
  return Array.from(name.trim())[0] ?? '#';
}

/**
 * 账号头像的缩写:拉丁名取前几个字符(设计稿 chasel43 → 抽屉「chas」、
 * 账号管理页「ch」),CJK 名一个字就够宽,只取首字。
 */
export function nameAbbrev(name: string, asciiCount: number): string {
  const chars = Array.from(name.trim());
  if (chars.length === 0) return '#';
  if (!/^[\x21-\x7e]$/.test(chars[0]!)) return chars[0]!;
  const ascii: string[] = [];
  for (const char of chars) {
    if (!/^[\x21-\x7e]$/.test(char) || ascii.length >= asciiCount) break;
    ascii.push(char);
  }
  return ascii.join('');
}

/** NGA 的响应结构全靠手工遍历，这个判定到处要用。 */
export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

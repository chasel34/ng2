import path from 'node:path'
import { defineConfig } from 'vitest/config'

/**
 * 金样本导出器专用配置（票 05a）。
 *
 * `scripts/export-goldens.mts` 会**写文件**，所以刻意不进默认 `vitest.config.ts` 的
 * `include`（`*.test.ts`）——`pnpm test` 不该有副作用。只有 `pnpm goldens:export` 跑它。
 */
export default defineConfig({
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  test: {
    include: ['scripts/export-goldens.mts'],
  },
})

import { storage } from '@/store/storage';

import { attachImageSizePersistence, type ImageSize } from './image-size';

/**
 * `image-size` 的 MMKV 持久层。单独一个文件是为了让 `image-size.ts` 保持
 * 不 import RN 的纯模块(vitest 跑不了 react-native-mmkv)。
 *
 * 存储格式:`[uri, width, height]` 的数组打平成 JSON——一条 entry 两个数,
 * 512 条上限整包几十 KB,MMKV 单 key 全量覆盖即可,不值得做增量。
 */
const KEY = 'bbcode/image-sizes.v1';

export function attachPersistedImageSizes(): void {
  attachImageSizePersistence({
    load() {
      const raw = storage.getString(KEY);
      if (raw === undefined) return [];
      try {
        const parsed: unknown = JSON.parse(raw);
        if (!Array.isArray(parsed)) return [];
        const entries: (readonly [string, ImageSize])[] = [];
        for (const row of parsed) {
          if (
            Array.isArray(row) &&
            typeof row[0] === 'string' &&
            typeof row[1] === 'number' &&
            typeof row[2] === 'number'
          ) {
            entries.push([row[0], { width: row[1], height: row[2] }]);
          }
        }
        return entries;
      } catch {
        return [];
      }
    },
    save(entries) {
      storage.set(KEY, JSON.stringify(entries.map(([uri, s]) => [uri, s.width, s.height])));
    },
  });
}

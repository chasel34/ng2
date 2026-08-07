import { createMMKV, type MMKV } from 'react-native-mmkv';

/**
 * 全局 KV 存储。设置、分类树缓存、公告已读这类小数据都放这里;
 * 帖子缓存与浏览历史走 expo-sqlite(spec §3),不塞 MMKV。
 *
 * MMKV v4 用 `createMMKV()` 工厂,不再是 `new MMKV()`。
 */
export const storage: MMKV = createMMKV({ id: 'ng2' });

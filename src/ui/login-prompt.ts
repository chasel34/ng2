import type { useRouter } from 'expo-router';

import { showSnackbar } from './snackbar';

/** expo-router 没导出 router 对象的类型,从 useRouter 的返回值上取。 */
type AppRouter = ReturnType<typeof useRouter>;

/**
 * 需要登录的入口在游客态下的统一处理:不报错,弹一条带「去登录」的 snack 条。
 *
 * 云端功能(版块收藏 10、收藏夹 11、通知 13…)的接口对游客一律回
 * 「你必须先登录论坛」,把这句服务端错误直接摔给用户既难懂也没出路,
 * 所以入口先自己挡住,并把登录页递到手边。
 */
export function showLoginPrompt(router: AppRouter, message: string): void {
  showSnackbar(message, { label: '去登录', onPress: () => router.push('/login') });
}

import { Alert, Platform, ToastAndroid } from 'react-native';

/**
 * 未实现功能的统一提示文案。
 *
 * 方案(spec §1)把回帖、短消息、发贴条、举报、投票操作排除在 v1 之外,
 * 但入口一律保留——点了要给这句话,不能静默无反应。
 */
export const NOT_AVAILABLE_MESSAGE = '本版本未开放';

export function showToast(message: string): void {
  if (Platform.OS === 'android') {
    ToastAndroid.show(message, ToastAndroid.SHORT);
    return;
  }
  // 只发 Android(spec §2),这条分支是给开发机上的 web 预览兜底的
  Alert.alert(message);
}

export function showNotAvailable(): void {
  showToast(NOT_AVAILABLE_MESSAGE);
}

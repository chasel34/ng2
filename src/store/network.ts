import { NetworkStateType, addNetworkStateListener, getNetworkStateAsync } from 'expo-network';
import { create } from 'zustand';

/**
 * 当前网络是不是计费网络(「仅 Wi-Fi 下加载图片」与「图片加载策略」的智能档要用)。
 *
 * 订阅只建一次:一屏帖子里几十张图,每张自己 `useNetworkState()` 就是几十个原生监听。
 * 这里做成模块级单例,组件只读 store。
 *
 * 只把**移动网络**算计费:`type` 有拿不到的时候(飞行模式刚切回来、某些 ROM 上的 VPN),
 * 那种情况按不限流走——宁可多花一点流量,也别让人在正常 Wi-Fi 下看到满屏
 * 「点击显示图片」还不知道为什么。
 */

interface NetworkState {
  readonly metered: boolean;
}

export const useNetwork = create<NetworkState>()(() => ({ metered: false }));

const apply = (type: NetworkStateType | undefined): void => {
  useNetwork.setState({ metered: type === NetworkStateType.CELLULAR });
};

try {
  // 开机先问一次(监听器只在状态**变化**时才响,不问就要等到第一次切换网络)
  void getNetworkStateAsync().then(
    (state) => apply(state.type),
    () => {
      // 问不到就按不限流走,理由同上
    },
  );
  addNetworkStateListener(({ type }) => apply(type));
} catch {
  // expo-network 是 22 票新加的原生模块。装着旧 dev client 的机器上它不存在,
  // 那时这里会抛——不能让「查一下是不是流量」把整个 app 拦在启动那一步
}

/** 当前是不是移动网络。 */
export const useOnMeteredNetwork = (): boolean => useNetwork((state) => state.metered);

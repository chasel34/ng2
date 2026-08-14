import { Image } from 'expo-image';
import { memo, useMemo, useState } from 'react';
import { Text, View } from 'react-native';

import type { FloorUser } from '@/core/api';

import { useAvatarSize } from './appearance';
import { initialOf } from './initial';
import { createThemedStyles } from './theme';
import { avatarColors } from './tokens';

/**
 * 按用户 key 稳定取一档占位底色。要的只是「同一个人每次都同一个颜色」,
 * 所以一个逐字符累加的弱散列足够——不需要抗碰撞。
 * 账号管理页的账号头像也用它(同一 uid 到处同色)。
 */
export function avatarColorFor(key: string): string {
  let hash = 0;
  for (let i = 0; i < key.length; i += 1) {
    hash = (hash * 31 + key.charCodeAt(i)) % 0xffffff;
  }
  return avatarColors[hash % avatarColors.length]!;
}

/**
 * 楼层头像。没设头像、或者图挂了,一律回落到「纯色圆底 + 名字首字」
 * (设计稿 isArticle 里画的就是这个占位,没有远程头像的 mock)。
 *
 * 边长由「字体和头像大小」设置定(22 票),所以只能写成内联样式。
 */
export const Avatar = memo(function Avatar({ user }: { user: FloorUser | undefined }) {
  const styles = useStyles();
  const [failedUrl, setFailedUrl] = useState<string | undefined>(undefined);
  const size = useAvatarSize();
  // 内联样式对象每次渲染都新建的话,expo-image 那边 style prop 每次都是新的
  const box = useMemo(
    () => ({ width: size, height: size, borderRadius: size / 2 }),
    [size],
  );

  const name = user?.name ?? '?';
  const key = user?.key ?? name;
  // 列表回收时同一个组件实例会换个人接着用,失败标志跟着地址记,不然新的人会顶着
  // 上一个人的「加载失败」显示首字占位
  const failed = failedUrl !== undefined && failedUrl === user?.avatarUrl;

  const placeholderStyle = useMemo(
    () => [box, styles.placeholder, { backgroundColor: avatarColorFor(key) }],
    [box, styles.placeholder, key],
  );

  if (user?.avatarUrl === undefined || failed) {
    return (
      <View style={placeholderStyle}>
        <Text style={styles.initial} allowFontScaling={false}>
          {initialOf(name)}
        </Text>
      </View>
    );
  }

  const avatarUrl = user.avatarUrl;
  return (
    <Image
      source={{ uri: avatarUrl }}
      style={box}
      contentFit="cover"
      // memory-disk 而不是 disk:头像是最值得进内存缓存的一类图——同一页里同一个人
      // 可能出现好多次,disk 档每次上屏都要重新读盘 + 解码
      cachePolicy="memory-disk"
      transition={120}
      // 列表复用时换人就重新加载,不会串图
      recyclingKey={key}
      onError={() => setFailedUrl(avatarUrl)}
      accessibilityIgnoresInvertColors
    />
  );
});

const useStyles = createThemedStyles((theme) => ({
  placeholder: {
    alignItems: 'center',
    justifyContent: 'center',
  },
  initial: {
    ...theme.typography.avatarInitial,
    color: theme.colors.onPrimary,
  },
}));

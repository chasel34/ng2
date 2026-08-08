import { Image } from 'expo-image';
import { useState } from 'react';
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
export function Avatar({ user }: { user: FloorUser | undefined }) {
  const styles = useStyles();
  const [failed, setFailed] = useState(false);
  const size = useAvatarSize();
  const box = { width: size, height: size, borderRadius: size / 2 };

  const name = user?.name ?? '?';
  const key = user?.key ?? name;

  if (user?.avatarUrl === undefined || failed) {
    return (
      <View style={[box, styles.placeholder, { backgroundColor: avatarColorFor(key) }]}>
        <Text style={styles.initial} allowFontScaling={false}>
          {initialOf(name)}
        </Text>
      </View>
    );
  }

  return (
    <Image
      source={{ uri: user.avatarUrl }}
      style={box}
      contentFit="cover"
      cachePolicy="disk"
      transition={120}
      // 列表复用时换人就重新加载,不会串图
      recyclingKey={key}
      onError={() => setFailed(true)}
      accessibilityIgnoresInvertColors
    />
  );
}

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

import { Image } from 'expo-image';
import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';

import { Icon } from '../icon';
import { createThemedStyles, useTheme } from '../theme';

/** 拿到真实尺寸之前的占位比例。取 4:3,比 16:9 更接近论坛里手机截图的常见比例。 */
const INITIAL_ASPECT = 4 / 3;

/** 竖长图(手机长截图)按整屏高展开会把楼层撑成一屏一张,压到这个比例封顶。 */
const MIN_ASPECT = 0.6;

export interface ContentImageProps {
  uri: string;
  onPress?: (uri: string) => void;
}

/**
 * 正文里的 `[img]`。
 *
 * 服务端不给图片尺寸,所以先按 4:3 占位,`onLoad` 拿到真实尺寸再改比例——
 * 一次性给个固定高度会让长截图糊成一条,而不给高度 expo-image 干脆不显示。
 */
export function ContentImage({ uri, onPress }: ContentImageProps) {
  const styles = useStyles();
  const theme = useTheme();
  const [aspect, setAspect] = useState(INITIAL_ASPECT);
  const [failed, setFailed] = useState(false);

  if (failed) {
    return (
      <View style={styles.failed}>
        <Icon name="cloud_off" size={18} color={theme.colors.meta} />
        <Text style={styles.failedText}>图片加载失败</Text>
      </View>
    );
  }

  return (
    <Pressable onPress={onPress === undefined ? undefined : () => onPress(uri)}>
      <Image
        source={{ uri }}
        style={[styles.image, { aspectRatio: aspect }]}
        contentFit="cover"
        cachePolicy="disk"
        transition={120}
        recyclingKey={uri}
        onLoad={({ source }) => {
          if (source.height > 0) {
            setAspect(Math.max(MIN_ASPECT, source.width / source.height));
          }
        }}
        onError={() => setFailed(true)}
        accessibilityIgnoresInvertColors
      />
    </Pressable>
  );
}

const useStyles = createThemedStyles((theme) => ({
  image: {
    width: '100%',
    borderRadius: theme.radius.md,
    backgroundColor: theme.colors.surface2,
  },
  failed: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: theme.spacing.sm,
    height: 42,
    borderRadius: theme.radius.md,
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: theme.colors.track,
    backgroundColor: theme.colors.surface2,
  },
  failedText: {
    ...theme.typography.notice,
    color: theme.colors.meta,
  },
}));

import { Image } from 'expo-image';
import { useState } from 'react';
import { Pressable, Text, View, type StyleProp, type ViewStyle } from 'react-native';

import { Icon } from '../icon';
import { useImagesUnlocked, usePreferThumbnail } from '../network';
import { createThemedStyles, useTheme } from '../theme';
import { imageSizeOf, rememberImageSize, type ImageSize } from './image-size';

/** 拿到真实尺寸之前的占位比例。取 4:3,比 16:9 更接近论坛里手机截图的常见比例。 */
const INITIAL_ASPECT = 4 / 3;

/** 竖长图(手机长截图)按整屏高展开会把楼层撑成一屏一张,压到这个比例封顶。 */
const MIN_ASPECT = 0.6;

/**
 * 不足这个宽度(原始像素)的算小图:按原尺寸摆,不铺满卡宽——
 * 签名里 16px 的站标拉到整卡宽会糊成一片色块(M4 验收缺陷 E7)。
 */
const SMALL_IMAGE_WIDTH = 200;

export interface ContentImageProps {
  uri: string;
  /** 同一张图的缩略图地址(「图片加载策略」省流量那两档用);站外图没有就不给 */
  thumbnailUri?: string;
  onPress?: (uri: string) => void;
  /**
   * 外距等由调用方给。三种形态(正常/移动网络折叠/加载失败)的根节点都吃这份样式,
   * 这样 `render.tsx` 不用再在外面套一层只为了加 marginTop 的 `View`——
   * 图多的楼层里那一层 View 的量算与绘制不便宜。
   */
  style?: StyleProp<ViewStyle>;
}

/**
 * 正文里的 `[img]`。
 *
 * 服务端不给图片尺寸,所以先按 4:3 占位,`onLoad` 拿到真实尺寸再改比例——
 * 一次性给个固定高度会让长截图糊成一条,而不给高度 expo-image 干脆不显示。
 * 量到的尺寸进 `./image-size` 的模块级缓存:同一张图再次上屏(列表回收、翻页回来)
 * 首帧就是对的比例,不再「先 4:3 再跳一下」把 FlashList 的量算带崩。
 *
 * 「仅 Wi-Fi 下加载图片」(22 票)在移动网络下把图收成一条占位,点一下照样展开;
 * 展开后拉哪一档清晰度由「图片加载策略」决定。
 */
export function ContentImage({ uri, thumbnailUri, onPress, style }: ContentImageProps) {
  const styles = useStyles();
  const theme = useTheme();
  // 状态跟着地址一起记:FlashList 回收时组件实例会被换一张图接着用,
  // 只存尺寸/失败标志的话,新的那张会顶着上一张的比例(或者上一张的「加载失败」)画
  const [loaded, setLoaded] = useState<{ uri: string; size: ImageSize } | undefined>(undefined);
  const [failedUri, setFailedUri] = useState<string | undefined>(undefined);
  const [revealed, setRevealed] = useState(false);
  const unlocked = useImagesUnlocked();
  const preferThumbnail = usePreferThumbnail();

  const source = preferThumbnail ? (thumbnailUri ?? uri) : uri;

  if (!unlocked && !revealed) {
    return (
      <Pressable style={[styles.locked, style]} onPress={() => setRevealed(true)}>
        <Icon name="signal_cellular_alt" size={18} color={theme.colors.fg2} />
        <Text style={styles.lockedText}>移动网络 · 点击显示图片</Text>
      </Pressable>
    );
  }

  if (failedUri === source) {
    return (
      <View style={[styles.failed, style]}>
        <Icon name="cloud_off" size={18} color={theme.colors.meta} />
        <Text style={styles.failedText}>图片加载失败</Text>
      </View>
    );
  }

  // 本次挂载量到的优先,其次是以前量过的——第一次见这张图才回落到占位比例
  const natural = loaded?.uri === source ? loaded.size : imageSizeOf(source);

  // 小图按原尺寸(px 当 dp)靠左摆;大图照旧铺满卡宽、按真实比例给高,
  // 竖长图压 MIN_ASPECT 封顶。小图不套这个封顶——16×64 的竖条原样放着就好
  const small = natural !== undefined && natural.width <= SMALL_IMAGE_WIDTH;
  const sizeStyle = small
    ? {
        width: natural.width,
        aspectRatio: natural.width / Math.max(1, natural.height),
        alignSelf: 'flex-start' as const,
      }
    : {
        aspectRatio:
          natural === undefined
            ? INITIAL_ASPECT
            : Math.max(MIN_ASPECT, natural.width / Math.max(1, natural.height)),
      };

  return (
    <Pressable style={style} onPress={onPress === undefined ? undefined : () => onPress(uri)}>
      <Image
        source={{ uri: source }}
        style={[styles.image, sizeStyle]}
        contentFit="cover"
        // memory-disk 而不是 disk:disk 档没有内存缓存,列表回收后同一张图重新上屏
        // 要再从磁盘读一遍、再解码一遍,来回滚就是反复付解码钱
        cachePolicy="memory-disk"
        transition={120}
        recyclingKey={source}
        onLoad={(event) => {
          const { width, height } = event.source;
          if (height <= 0 || width <= 0) return;
          rememberImageSize(source, { width, height });
          // 缓存命中时首帧比例已经是对的,再 setState 只是白多一次渲染
          if (natural?.width === width && natural.height === height) return;
          setLoaded({ uri: source, size: { width, height } });
        }}
        onError={() => setFailedUri(source)}
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
  // 折叠态与「加载失败」同一个形状,只是文案与图标不同
  locked: {
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
  lockedText: {
    ...theme.typography.notice,
    fontWeight: '600',
    color: theme.colors.fg2,
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

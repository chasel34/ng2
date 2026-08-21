import { Image } from 'expo-image';
import { useState } from 'react';
import { Pressable, Text, View, type StyleProp, type ViewStyle } from 'react-native';

import { Icon } from '../icon';
import { useImagesUnlocked, usePreferThumbnail } from '../network';
import { createThemedStyles, useTheme } from '../theme';
import {
  CONTENT_IMAGE_MIN_ASPECT,
  imageSizeOf,
  isLongImage,
  rememberImageSize,
  type ImageSize,
} from './image-size';

/** 拿到真实尺寸之前的占位比例。取 4:3,比 16:9 更接近论坛里手机截图的常见比例。 */
const INITIAL_ASPECT = 4 / 3;

/**
 * 渐隐蒙层的高度与层数。项目没装渐变库,照 `board-icon.tsx` 的做法用多层 View 手搓:
 * 96 高分 12 层,每层 8 —— 层高小于 8 的话层数上去了、每层透明度差反而不够,
 * 还是会看出台阶;96 够放下角标又不至于吃掉半张图。
 */
const FADE_HEIGHT = 96;
const FADE_BANDS = 12;

/**
 * 第 index 层(0 = 最上面那层)的不透明度。用平方而不是线性:线性叠出来上半段
 * 一上来就发白、下半段又迟迟不到底,平方更接近真渐变的观感。最底一层实心,
 * 图就是「化」进页面背景里的。
 */
const fadeOpacity = (index: number): number => ((index + 1) / FADE_BANDS) ** 2;

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
 * 服务端不给图片尺寸,所以先按 4:3 占位,`onLoad` 拿到真实尺寸再改比例。
 * 一次性给个固定高度会让长截图糊成一条,而不给高度 expo-image 干脆不显示。
 * 量到的尺寸进 `./image-size` 的缓存(会话内 + MMKV 持久化):同一张图再次上屏
 * (列表回收、翻页回来、下次启动)首帧就是对的比例,不再「先 4:3 再跳一下」。
 * 首见图片的比例修正是**一帧内的整体重排**——之前给图片框单独挂
 * `LinearTransition` 布局动画,结果框自己滑 180ms、下方兄弟内容却瞬移,
 * 实测(2026-08-15 录屏逐帧)就是「进详情闪一下」的主体,拆掉后全卡片一次到位。
 *
 * 「仅 Wi-Fi 下加载图片」(22 票)在移动网络下把图收成一条占位,点一下照样展开;
 * 展开后拉哪一档清晰度由「图片加载策略」决定。
 *
 * 聊天记录、账单这类瘦长图会被比例封顶裁掉一截(不裁的话一楼能撑出好几屏,
 * LegendList 的行高估算也跟着抖)。裁可以,但不能不吭声:被裁的图底部加一段
 * 渐隐 + 一枚「点击查看完整」角标(`LongImageHint`),点开还是走大图查看器。
 */
export function ContentImage({ uri, thumbnailUri, onPress, style }: ContentImageProps) {
  const styles = useStyles();
  const theme = useTheme();
  // 状态跟着地址一起记:列表回收时组件实例会被换一张图接着用,
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
  // 竖长图压 CONTENT_IMAGE_MIN_ASPECT 封顶。小图不套这个封顶——16×64 的竖条原样放着就好
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
            : Math.max(CONTENT_IMAGE_MIN_ASPECT, natural.width / Math.max(1, natural.height)),
      };

  // 只有走封顶那条路的大图才会被裁;小图按原尺寸摆,一个像素都没少
  const long = !small && natural !== undefined && isLongImage(natural);

  return (
    <Pressable style={style} onPress={onPress === undefined ? undefined : () => onPress(uri)}>
      <View style={[styles.imageFrame, sizeStyle]}>
        <Image
          source={{ uri: source }}
          style={styles.image}
          contentFit="cover"
          // cover 默认居中裁,长图会上下各切一半:蒙层说「下面还有」,顶上却也少了
          // 一截,对不上。长图改成贴顶,裁掉的部分全在下面,和提示是一回事
          contentPosition={long ? 'top' : 'center'}
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
        {long && <LongImageHint openable={onPress !== undefined} />}
      </View>
    </Pressable>
  );
}

/**
 * 长图底部的「下面还有」提示:一段自下而上的渐隐 + 一枚胶囊角标。
 *
 * 只是提示,不吃点击(`pointerEvents:'none'`)——点哪儿都还是打开大图查看器,
 * 在那儿能完整上下滚。渐隐取正文背景色,图看着是化进页面而不是被切了一刀。
 */
function LongImageHint({ openable }: { openable: boolean }) {
  const styles = useStyles();
  const theme = useTheme();

  return (
    <View style={styles.fade} pointerEvents="none">
      {Array.from({ length: FADE_BANDS }, (_, index) => (
        <View
          key={index}
          style={{
            flex: 1,
            backgroundColor: theme.colors.bg,
            opacity: fadeOpacity(index),
          }}
        />
      ))}
      <View style={styles.badgeRow}>
        <View style={styles.badge}>
          <Icon name="expand_more" size={13} color={theme.colors.primary} />
          <Text style={styles.badgeText}>
            {openable ? '长图 · 点击查看完整' : '长图 · 已截断'}
          </Text>
        </View>
      </View>
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  imageFrame: {
    width: '100%',
    borderRadius: theme.radius.md,
    backgroundColor: theme.colors.surface2,
  },
  image: {
    width: '100%',
    height: '100%',
    borderRadius: theme.radius.md,
  },
  // 底部圆角要自己裁一遍:蒙层是方的,不裁会从图片的圆角外面探出两只角
  fade: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    height: FADE_HEIGHT,
    borderBottomLeftRadius: theme.radius.md,
    borderBottomRightRadius: theme.radius.md,
    overflow: 'hidden',
  },
  // 角标单独一行居中:绝对定位的子节点不靠 alignSelf 摆,免得各版本 Yoga 行为不一
  badgeRow: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 10,
    alignItems: 'center',
  },
  badge: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 2,
    height: 24,
    paddingLeft: theme.spacing.sm,
    paddingRight: theme.spacing.md,
    borderRadius: theme.radius.full,
    backgroundColor: theme.colors.primaryContainer,
  },
  badgeText: {
    ...theme.typography.cardMeta,
    fontWeight: '600',
    color: theme.colors.primary,
    includeFontPadding: false,
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

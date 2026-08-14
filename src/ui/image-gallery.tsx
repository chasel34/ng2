import { Image } from 'expo-image';
import { useEffect, useState } from 'react';
import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import Animated, {
  runOnJS,
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';

import { useTheme } from './theme';

/**
 * 大图查看器的画布(25 票):左右滑动翻页 + 双指缩放 + 双击放大/还原。
 *
 * 手势分工让「缩放与翻页不冲突」是结构性的:同一个 Pan 手势按当前缩放拆两条路——
 * 原始大小时拖动的是**页**(松手按位移/速度决定翻不翻),放大后拖动的是**图**
 * (钳在图的边界内)。要翻页得先双击/捏合回到原始大小,这也是系统相册的行为。
 *
 * 边界回弹:页在第一张/最后一张继续拖、图被拖出边界时,位移都乘一个阻尼(0.55),
 * 松手 220ms 回到合法位置——「拉得动但拉不走」。
 */

export interface GallerySource {
  /** 展示用地址(按图片加载策略选好的档) */
  readonly uri: string;
  /** 渐进加载的占位缩略图;通常已有磁盘缓存,原图到位前先糊着看 */
  readonly placeholderUri?: string;
}

export interface ImageGalleryProps {
  sources: readonly GallerySource[];
  /** 当前页下标。翻页手势通过 onIndexChange 汇报,由调用方改回来 */
  index: number;
  onIndexChange: (index: number) => void;
}

/** 双击放大到的倍数(约等于系统相册那一档)。 */
const DOUBLE_TAP_SCALE = 2.5;

/** 捏合的上限;过程里允许暂时超出,松手弹回。 */
const MAX_SCALE = 4;

/** 认定翻页的位移阈值(页宽的比例)与甩动速度阈值。 */
const PAGE_TURN_RATIO = 0.35;
const PAGE_TURN_VELOCITY = 600;

/** 拖过边界后的阻尼系数与回弹时长。 */
const EDGE_RESISTANCE = 0.55;
const BOUNCE_MS = 220;

/** 缩放判定的容差:浮点回到 1 附近就算「原始大小」。 */
const ZOOM_EPSILON = 1.01;

export function ImageGallery({ sources, index, onIndexChange }: ImageGalleryProps) {
  const theme = useTheme();
  const [size, setSize] = useState({ width: 0, height: 0 });
  // 每张图加载后的宽高比,钳图的拖动边界要按「画出来的尺寸」算而不是容器
  const [aspects, setAspects] = useState<Readonly<Record<number, number>>>({});
  const { width, height } = size;
  const count = sources.length;

  // 页位移(整行左移)与当前页的缩放状态
  const translateX = useSharedValue(0);
  const indexValue = useSharedValue(index);
  const scale = useSharedValue(1);
  const imageX = useSharedValue(0);
  const imageY = useSharedValue(0);
  // 手势起点(Pan/Pinch 的回调之间只能靠共享值传状态)
  const startPageX = useSharedValue(0);
  const startImageX = useSharedValue(0);
  const startImageY = useSharedValue(0);
  const pinchStartScale = useSharedValue(1);
  const pinchFocalX = useSharedValue(0);
  const pinchFocalY = useSharedValue(0);
  const aspectValue = useSharedValue(0);

  // 量到容器尺寸后把整行对齐到当前页;转屏不做(spec §2 竖屏锁定),宽度只会量到一次
  useEffect(() => {
    if (width === 0) return;
    translateX.value = -indexValue.value * width;
  }, [width, translateX, indexValue]);

  // 换页兜底重置缩放(翻页只能在原始大小下发生,这里是防御而不是路径)
  useEffect(() => {
    scale.value = 1;
    imageX.value = 0;
    imageY.value = 0;
  }, [index, scale, imageX, imageY]);

  useEffect(() => {
    aspectValue.value = aspects[index] ?? 0;
  }, [aspects, index, aspectValue]);

  /** 当前缩放下图片能被拖多远(中心系)。contain 画出来的尺寸由宽高比定。 */
  const boundsFor = (value: number): { x: number; y: number } => {
    'worklet';
    const aspect = aspectValue.value;
    let drawnWidth = width;
    let drawnHeight = height;
    if (aspect > 0 && height > 0) {
      drawnWidth = Math.min(width, height * aspect);
      drawnHeight = drawnWidth / aspect;
    }
    return {
      x: Math.max(0, (drawnWidth * value - width) / 2),
      y: Math.max(0, (drawnHeight * value - height) / 2),
    };
  };

  const rubber = (value: number, min: number, max: number): number => {
    'worklet';
    if (value < min) return min + (value - min) * EDGE_RESISTANCE;
    if (value > max) return max + (value - max) * EDGE_RESISTANCE;
    return value;
  };

  const clamp = (value: number, min: number, max: number): number => {
    'worklet';
    return Math.min(Math.max(value, min), max);
  };

  const settleZoom = (nextScale: number): void => {
    'worklet';
    const bounds = boundsFor(nextScale);
    scale.value = withTiming(nextScale, { duration: BOUNCE_MS });
    imageX.value = withTiming(clamp(imageX.value, -bounds.x, bounds.x), { duration: BOUNCE_MS });
    imageY.value = withTiming(clamp(imageY.value, -bounds.y, bounds.y), { duration: BOUNCE_MS });
  };

  const pan = Gesture.Pan()
    .maxPointers(1)
    .minDistance(8)
    .onStart(() => {
      startPageX.value = translateX.value;
      startImageX.value = imageX.value;
      startImageY.value = imageY.value;
    })
    .onUpdate((event) => {
      if (scale.value > ZOOM_EPSILON) {
        // 放大态:拖的是图,出界给阻尼
        const bounds = boundsFor(scale.value);
        imageX.value = rubber(startImageX.value + event.translationX, -bounds.x, bounds.x);
        imageY.value = rubber(startImageY.value + event.translationY, -bounds.y, bounds.y);
        return;
      }
      // 原始大小:拖的是页,第一张/最后一张再拖给阻尼
      translateX.value = rubber(startPageX.value + event.translationX, -width * (count - 1), 0);
    })
    .onEnd((event) => {
      if (scale.value > ZOOM_EPSILON) {
        settleZoom(scale.value);
        return;
      }
      const current = indexValue.value;
      let target = current;
      const dx = event.translationX;
      if (dx < -width * PAGE_TURN_RATIO || (dx < -20 && event.velocityX < -PAGE_TURN_VELOCITY)) {
        target = current + 1;
      } else if (dx > width * PAGE_TURN_RATIO || (dx > 20 && event.velocityX > PAGE_TURN_VELOCITY)) {
        target = current - 1;
      }
      target = clamp(target, 0, count - 1);
      indexValue.value = target;
      translateX.value = withTiming(-target * width, { duration: BOUNCE_MS });
      if (target !== current) runOnJS(onIndexChange)(target);
    });

  const pinch = Gesture.Pinch()
    .onStart((event) => {
      pinchStartScale.value = scale.value;
      startImageX.value = imageX.value;
      startImageY.value = imageY.value;
      // 焦点换到中心系,缩放期间钉住它(两指中点跟手)
      pinchFocalX.value = event.focalX - width / 2;
      pinchFocalY.value = event.focalY - height / 2;
    })
    .onUpdate((event) => {
      // 过程里允许越界(掐小到 0.6、放大到上限的 1.4 倍),松手弹回;完全掐死会显得僵
      const next = clamp(pinchStartScale.value * event.scale, 0.6, MAX_SCALE * 1.4);
      const growth = next / pinchStartScale.value;
      scale.value = next;
      imageX.value = pinchFocalX.value - (pinchFocalX.value - startImageX.value) * growth;
      imageY.value = pinchFocalY.value - (pinchFocalY.value - startImageY.value) * growth;
    })
    .onEnd(() => {
      if (scale.value <= ZOOM_EPSILON) {
        scale.value = withTiming(1, { duration: BOUNCE_MS });
        imageX.value = withTiming(0, { duration: BOUNCE_MS });
        imageY.value = withTiming(0, { duration: BOUNCE_MS });
        return;
      }
      settleZoom(Math.min(scale.value, MAX_SCALE));
    });

  const doubleTap = Gesture.Tap()
    .numberOfTaps(2)
    .maxDelay(240)
    .onEnd((event) => {
      if (scale.value > ZOOM_EPSILON) {
        scale.value = withTiming(1, { duration: BOUNCE_MS });
        imageX.value = withTiming(0, { duration: BOUNCE_MS });
        imageY.value = withTiming(0, { duration: BOUNCE_MS });
        return;
      }
      // 放大到点按处:缩放围绕中心,所以把点按点平移到中心附近,再钳进边界
      const bounds = boundsFor(DOUBLE_TAP_SCALE);
      const focalX = event.x - width / 2;
      const focalY = event.y - height / 2;
      scale.value = withTiming(DOUBLE_TAP_SCALE, { duration: BOUNCE_MS });
      imageX.value = withTiming(clamp(focalX * (1 - DOUBLE_TAP_SCALE), -bounds.x, bounds.x), {
        duration: BOUNCE_MS,
      });
      imageY.value = withTiming(clamp(focalY * (1 - DOUBLE_TAP_SCALE), -bounds.y, bounds.y), {
        duration: BOUNCE_MS,
      });
    });

  const gesture = Gesture.Simultaneous(pan, pinch, doubleTap);

  const rowStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: translateX.value }],
  }));

  const zoomStyle = useAnimatedStyle(() => ({
    transform: [
      { translateX: imageX.value },
      { translateY: imageY.value },
      { scale: scale.value },
    ],
  }));

  return (
    <View
      style={styles.root}
      onLayout={(event) => {
        const { width: w, height: h } = event.nativeEvent.layout;
        setSize({ width: w, height: h });
      }}
    >
      {width > 0 && (
        <GestureDetector gesture={gesture}>
          <Animated.View style={[styles.row, { width: width * count }, rowStyle]}>
            {/* 设计稿 isViewer 给图四周留 16 白边。内距落在页上而不是根容器上:
                分页位移吃的是 onLayout 量到的整宽,根容器加内距就对不上了 */}
            {sources.map((source, i) => (
              <View key={i} style={[styles.page, { width, height }]}>
                {/* 只挂当前页与两侧邻页,几十张的楼不至于一进来全拉原图 */}
                {Math.abs(i - index) <= 1 && (
                  <GalleryPage
                    source={source}
                    zoomStyle={i === index ? zoomStyle : undefined}
                    spinnerColor={theme.colors.primary}
                    onAspect={(aspect) =>
                      setAspects((prev) => (prev[i] === aspect ? prev : { ...prev, [i]: aspect }))
                    }
                  />
                )}
              </View>
            ))}
          </Animated.View>
        </GestureDetector>
      )}
    </View>
  );
}

function GalleryPage({
  source,
  zoomStyle,
  spinnerColor,
  onAspect,
}: {
  source: GallerySource;
  zoomStyle: React.ComponentProps<typeof Animated.View>['style'] | undefined;
  spinnerColor: string;
  onAspect: (aspect: number) => void;
}) {
  const [loading, setLoading] = useState(true);

  return (
    <Animated.View style={[styles.page, zoomStyle]}>
      <Image
        source={{ uri: source.uri }}
        {...(source.placeholderUri === undefined || source.placeholderUri === source.uri
          ? {}
          : {
              placeholder: { uri: source.placeholderUri },
              placeholderContentFit: 'contain' as const,
            })}
        style={styles.image}
        contentFit="contain"
        /*
         * 这里**故意留 disk**,和头像/正文图/版块图标那几处不一样。
         *
         * expo-image 在 Android 上把 cachePolicy 直接翻译成 Glide 的
         * `skipMemoryCache`(`ExpoImageViewWrapper.kt:440`),而 Glide 的内存缓存是
         * **整个进程共用的一个 LruResourceCache**,容量按「解码后位图的字节数」算
         * (MemorySizeCalculator,约两屏像素)。
         *
         * 查看器画的是整屏原图:contain 到全屏后一张解码位图就是屏幕像素级
         * (1080×2400×4B ≈ 10MB)。放进去几张就能把那个共用池挤空——被挤掉的正是
         * 头像和缩略图,也就是我们刚决定要留在内存里的东西。
         *
         * 换来的好处又很小:查看器同时只挂当前页与两侧邻页(见上面的 ±1 判断),
         * 活着的三张本来就被视图持有;真正靠内存缓存省的只有「翻出 ±1 窗口再翻回来」
         * 那一次,而那一次已经有 placeholderUri(缩略图,它是走内存缓存的)先糊着看,
         * 底下只是一次本地磁盘读。
         *
         * 再加上走查里 P2 记的「看完 20 帖 PSS 174→289MB 不回落」,更不该往这个池子里
         * 塞整屏位图。
         */
        cachePolicy="disk"
        transition={120}
        onLoad={({ source: loaded }) => {
          setLoading(false);
          if (loaded.height > 0) onAspect(loaded.width / loaded.height);
        }}
        onError={() => setLoading(false)}
        accessibilityIgnoresInvertColors
      />
      {/* 原图在路上时的小转圈;缩略图占位在底下先糊着看 */}
      {loading && (
        <View style={styles.spinner} pointerEvents="none">
          <ActivityIndicator color={spinnerColor} />
        </View>
      )}
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    overflow: 'hidden',
  },
  row: {
    flex: 1,
    flexDirection: 'row',
  },
  page: {
    // flex:1 不能少:GalleryPage 的根节点也用这份样式,它外面那层只给了宽高,
    // 只有 padding 的话内容自适应高度会坍缩成 0,flex:1 的 Image 就永远量不出尺寸
    flex: 1,
    padding: 16,
  },
  image: {
    flex: 1,
  },
  spinner: {
    position: 'absolute',
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
    alignItems: 'center',
    justifyContent: 'center',
  },
});

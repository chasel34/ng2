import { useRef, useState } from 'react';
import { PanResponder, Pressable, Text, View } from 'react-native';

import { Icon } from './icon';
import { createThemedStyles, useTheme } from './theme';

/**
 * 字号调节屏的滑杆(设计稿 `T.fontSliders`:取值气泡 + 3px 轨道 + 18 圆钮 + 两端 ±)。
 *
 * 自己画而不是拉社区滑杆库:要的形状很具体(气泡跟着钮走、两端带步进钮),
 * 而且这是全 app 唯一一处滑杆——为它多背一个原生依赖不划算。
 *
 * 布局照设计稿的绝对定位来:一块 66 高的相对容器,气泡贴顶(top 4)、轨道贴底
 * (bottom 20)、± 压在轨道下方的两端(bottom −6)。轨道是**整行通宽**的,
 * ± 不占它的宽度——所以 0 和 1 两端的钮正好落在行的左右边缘上。
 */

/** 设计稿:轨道 3 高、圆钮 18、整块滑杆区 66 高。 */
const AREA_HEIGHT = 66;
const TRACK_HEIGHT = 3;
const TRACK_BOTTOM = 20;
const KNOB_SIZE = 18;
/** 轨道本身只有 3px 拖不住,给它套一条 34 高的可拖带,上下各让出一截。 */
const TOUCH_HEIGHT = 34;
const TOUCH_BOTTOM = TRACK_BOTTOM - (TOUCH_HEIGHT - TRACK_HEIGHT) / 2;
/** 步进钮:20 见方的图标,设计稿把它压在轨道下方、左右各外挑 2。 */
const STEP_SIZE = 20;
const STEP_BOTTOM = -6;
const STEP_INSET = -2;

export interface SliderProps {
  label: string;
  /** 气泡里显示的文本(单位由调用方拼好) */
  text: string;
  /** 当前值在量程里的位置,0–1 */
  ratio: number;
  /** 拖动/点击轨道:参数是新的 0–1 位置 */
  onSlide: (ratio: number) => void;
  /** 两端的 − / + ,走一个步长 */
  onStep: (direction: -1 | 1) => void;
}

export function Slider({ label, text, ratio, onSlide, onStep }: SliderProps) {
  const styles = useStyles();
  const theme = useTheme();
  const [width, setWidth] = useState(0);
  const [bubbleWidth, setBubbleWidth] = useState(0);

  // PanResponder 建一次就不能换(换了正在进行的手势会断),所以轨道宽与回调
  // 都从 ref 里读,不进闭包
  const state = useRef({ width: 0, start: 0, onSlide });
  state.current.width = width;
  state.current.onSlide = onSlide;

  const pan = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      // 手势一落下先跳到按住的位置(设计稿点轨道即跳),之后按位移接着拖
      onPanResponderGrant: (event) => {
        const { width: trackWidth } = state.current;
        if (trackWidth <= 0) return;
        const next = clamp01(event.nativeEvent.locationX / trackWidth);
        state.current.start = next;
        state.current.onSlide(next);
      },
      onPanResponderMove: (_event, gesture) => {
        const { width: trackWidth, start } = state.current;
        if (trackWidth <= 0) return;
        state.current.onSlide(clamp01(start + gesture.dx / trackWidth));
      },
    }),
  ).current;

  const filled = width * ratio;

  return (
    <View style={styles.row}>
      <Text style={styles.label}>{label}</Text>
      <View style={styles.area} onLayout={(event) => setWidth(event.nativeEvent.layout.width)}>
        {/* 气泡以钮为中心。宽度随文字变(「1.70」比「17」宽),所以量出来再抵掉一半,
            而不是写死一个偏移 */}
        <View
          style={[styles.bubble, { left: filled - bubbleWidth / 2 }]}
          onLayout={(event) => setBubbleWidth(event.nativeEvent.layout.width)}
        >
          <Text style={styles.bubbleText} allowFontScaling={false}>
            {text}
          </Text>
        </View>

        <View style={styles.touch} {...pan.panHandlers}>
          <View style={styles.track}>
            <View style={[styles.trackFill, { width: filled }]} />
          </View>
          <View style={[styles.knob, { left: filled - KNOB_SIZE / 2 }]} />
        </View>

        {/* ± 排在可拖带之后,压在它上面——两者在设计稿里本来就有一小段重叠 */}
        <Pressable
          style={styles.stepMinus}
          onPress={() => onStep(-1)}
          accessibilityLabel={`调小${label}`}
        >
          <Icon name="remove" size={STEP_SIZE} color={theme.colors.meta} />
        </Pressable>
        <Pressable
          style={styles.stepPlus}
          onPress={() => onStep(1)}
          accessibilityLabel={`调大${label}`}
        >
          <Icon name="add" size={STEP_SIZE} color={theme.colors.meta} />
        </Pressable>
      </View>
    </View>
  );
}

const clamp01 = (value: number): number => Math.min(1, Math.max(0, value));

const useStyles = createThemedStyles((theme) => ({
  /** 设计稿:16 上 / 16 左右 / 4 下 */
  row: {
    paddingTop: theme.spacing.lg,
    paddingHorizontal: theme.spacing.lg,
    paddingBottom: theme.spacing.xs,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  label: {
    ...theme.typography.sliderLabel,
    color: theme.colors.fg,
  },
  area: {
    height: AREA_HEIGHT,
    marginTop: 2,
  },
  bubble: {
    position: 'absolute',
    top: theme.spacing.xs,
    paddingVertical: 6,
    paddingHorizontal: theme.spacing.row,
    borderRadius: 3,
    backgroundColor: theme.colors.primary,
  },
  bubbleText: {
    ...theme.typography.sliderValue,
    color: theme.colors.onPrimary,
  },
  touch: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: TOUCH_BOTTOM,
    height: TOUCH_HEIGHT,
    justifyContent: 'center',
  },
  track: {
    height: TRACK_HEIGHT,
    borderRadius: 2,
    backgroundColor: theme.colors.track,
  },
  trackFill: {
    height: TRACK_HEIGHT,
    borderRadius: 2,
    backgroundColor: theme.colors.primary,
  },
  knob: {
    position: 'absolute',
    // 写死 top 而不是靠父级的 justifyContent 居中:绝对定位子元素在两端平台上
    // 对 auto 边距的处理不完全一致,轨道只有 3px,差一点就看得出来
    top: (TOUCH_HEIGHT - KNOB_SIZE) / 2,
    width: KNOB_SIZE,
    height: KNOB_SIZE,
    borderRadius: KNOB_SIZE / 2,
    backgroundColor: theme.colors.primary,
  },
  stepMinus: {
    position: 'absolute',
    left: STEP_INSET,
    bottom: STEP_BOTTOM,
    width: STEP_SIZE,
    height: STEP_SIZE,
    alignItems: 'center',
    justifyContent: 'center',
  },
  stepPlus: {
    position: 'absolute',
    right: STEP_INSET,
    bottom: STEP_BOTTOM,
    width: STEP_SIZE,
    height: STEP_SIZE,
    alignItems: 'center',
    justifyContent: 'center',
  },
}));

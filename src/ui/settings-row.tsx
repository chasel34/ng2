import { useEffect } from 'react';
import { Pressable, Text, View } from 'react-native';
import Reanimated, {
  interpolate,
  interpolateColor,
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';

import { Icon } from './icon';
import { duration, easeStandardWorklet } from './motion';
import { createThemedStyles, useTheme } from './theme';

/**
 * 设置三屏的行(设计稿 `T.setRows` 的三种形态:分组标题 / 开关行 / 带箭头的跳转行)。
 *
 * 三屏长得完全一样,只有行的内容不同——所以行本身抽在这儿,屏里只写数据。
 */

/** 设计稿:轨道 46×26 圆角 13、内距 3,滑块 20 见方,开时右移 20。 */
const TRACK_WIDTH = 46;
const TRACK_HEIGHT = 26;
const TRACK_PADDING = 3;
const KNOB_SIZE = 20;
const KNOB_TRAVEL = TRACK_WIDTH - TRACK_PADDING * 2 - KNOB_SIZE;

/** 设计稿开关的 `transition:.18s`(动效 token 的 quick 档)。 */
const TOGGLE_MS = duration.quick;

/** 分组标题(设计稿:12.5/700 的主题色小标题)。 */
export function SettingsSection({ children }: { children: string }) {
  const styles = useStyles();
  return <Text style={styles.section}>{children}</Text>;
}

export interface SettingsSwitchRowProps {
  label: string;
  /** 第二行灰字,设计稿里不是每行都有 */
  sub?: string;
  value: boolean;
  onChange: (next: boolean) => void;
}

export function SettingsSwitchRow({ label, sub, value, onChange }: SettingsSwitchRowProps) {
  const styles = useStyles();
  return (
    <Pressable
      style={styles.row}
      onPress={() => onChange(!value)}
      accessibilityRole="switch"
      accessibilityState={{ checked: value }}
      accessibilityLabel={label}
    >
      <RowText label={label} sub={sub} />
      <SettingsSwitch value={value} />
    </Pressable>
  );
}

export interface SettingsNavRowProps {
  label: string;
  sub?: string;
  onPress: () => void;
}

/** 点进二级页或弹对话框的行,右侧是 chevron。 */
export function SettingsNavRow({ label, sub, onPress }: SettingsNavRowProps) {
  const styles = useStyles();
  const theme = useTheme();
  return (
    <Pressable
      style={styles.row}
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={label}
      android_ripple={{ color: theme.colors.divider }}
    >
      <RowText label={label} sub={sub} />
      <Icon name="chevron_right" size={20} color={theme.colors.meta} />
    </Pressable>
  );
}

function RowText({ label, sub }: { label: string; sub?: string }) {
  const styles = useStyles();
  return (
    <View style={styles.rowText}>
      <Text style={styles.label}>{label}</Text>
      {sub !== undefined && sub !== '' && (
        <Text style={styles.sub} numberOfLines={2}>
          {sub}
        </Text>
      )}
    </View>
  );
}

/**
 * 开关本体。用自己画的而不是 RN 的 `Switch`:后者在 Android 上是平台控件,
 * 尺寸与圆角都改不动,和设计稿差得远。
 */
export function SettingsSwitch({ value }: { value: boolean }) {
  const styles = useStyles();
  const theme = useTheme();
  const progress = useSharedValue(value ? 1 : 0);

  useEffect(() => {
    progress.value = withTiming(value ? 1 : 0, {
      duration: TOGGLE_MS,
      easing: easeStandardWorklet,
    });
  }, [value, progress]);

  // 轨道底色的颜色插值在 RN Animated 里走不了原生驱动,Reanimated 没这个限制
  const trackStyle = useAnimatedStyle(() => ({
    backgroundColor: interpolateColor(
      progress.value,
      [0, 1],
      [theme.colors.track, theme.colors.primary],
    ),
  }));
  const knobStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: interpolate(progress.value, [0, 1], [0, KNOB_TRAVEL]) }],
  }));

  return (
    <Reanimated.View style={[styles.track, trackStyle]}>
      <Reanimated.View
        style={[
          styles.knob,
          { backgroundColor: value ? theme.colors.onPrimary : theme.colors.surface },
          knobStyle,
        ]}
      />
    </Reanimated.View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  // 设计稿设置屏的分组标题不带字间距(caption 那 0.4 是抽屉分区小标题的)
  section: {
    ...theme.typography.caption,
    letterSpacing: 0,
    color: theme.colors.primary,
    paddingTop: theme.spacing.page,
    paddingHorizontal: theme.spacing.page,
    paddingBottom: theme.spacing.sm,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.row,
    paddingVertical: theme.spacing.row,
    paddingHorizontal: theme.spacing.page,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  rowText: {
    flex: 1,
    minWidth: 0,
  },
  // 设计稿行标题 15/400,与抽屉条目同一档
  label: {
    ...theme.typography.drawerItem,
    color: theme.colors.fg,
  },
  sub: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
    marginTop: theme.spacing.xs,
  },
  track: {
    width: TRACK_WIDTH,
    height: TRACK_HEIGHT,
    borderRadius: TRACK_HEIGHT / 2,
    padding: TRACK_PADDING,
  },
  knob: {
    width: KNOB_SIZE,
    height: KNOB_SIZE,
    borderRadius: KNOB_SIZE / 2,
    boxShadow: '0px 1px 3px rgba(0, 0, 0, 0.3)',
  },
}));

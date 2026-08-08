import { useRouter } from 'expo-router';
import { ScrollView, Text, View } from 'react-native';

import {
  APPEARANCE_SLIDERS,
  DEFAULT_SETTINGS,
  avatarSizeOf,
  formatSliderValue,
  sliderRatio,
  sliderValueAt,
} from '@/core/local';
import { useSettings } from '@/store/settings';
import { Slider } from '@/ui/slider';
import { createThemedStyles } from '@/ui/theme';
import { showToast } from '@/ui/toast';
import { TopBar, TopBarButton, TopBarTitle, topBarSpacer } from '@/ui/top-bar';
import { avatarColors } from '@/ui/tokens';

/** 预览卡片里那位「楼主」的头像底色,取占位色板的第一档(设计稿 `#3E6B7E`)。 */
const PREVIEW_AVATAR_COLOR = avatarColors[0];

const PREVIEW_TEXT =
  '体感消费不一直这样吗?楼主 22 年大学毕业直接进厂了,没怎么在社会上摸爬滚打。从哪个时间段开始的?';

/**
 * 字体和头像大小(设计稿 `isFontSize` 屏)。
 *
 * 五根滑杆改的都是同一份 `appearance`,改完立刻落 MMKV——所以上面那张预览卡片
 * 和详情页的真楼层看到的是同一份值,不需要「保存」这一步。
 */
export default function FontSizeScreen() {
  const styles = useStyles();
  const router = useRouter();

  const appearance = useSettings((state) => state.settings.appearance);
  const setAppearance = useSettings((state) => state.setAppearance);
  const setSetting = useSettings((state) => state.set);

  const avatarSize = avatarSizeOf(appearance.avatarScale);

  return (
    <View style={styles.root}>
      <TopBar paddingHorizontal={4}>
        <TopBarButton
          icon="arrow_back"
          size={24}
          onPress={() => router.back()}
          accessibilityLabel="返回"
        />
        <TopBarTitle variant="sub">字体和头像大小</TopBarTitle>
        <Text
          style={[styles.reset, topBarSpacer]}
          onPress={() => {
            setSetting('appearance', DEFAULT_SETTINGS.appearance);
            showToast('已恢复默认字号');
          }}
        >
          重置
        </Text>
      </TopBar>

      <ScrollView style={styles.body} contentContainerStyle={styles.content}>
        <Text style={styles.section}>实时预览</Text>
        <View style={styles.card}>
          <View style={styles.cardHeader}>
            <View
              style={[
                styles.avatar,
                {
                  width: avatarSize,
                  height: avatarSize,
                  borderRadius: avatarSize / 2,
                  backgroundColor: PREVIEW_AVATAR_COLOR,
                },
              ]}
            >
              <Text style={styles.avatarText} allowFontScaling={false}>
                阴
              </Text>
            </View>
            <View style={styles.cardHeaderText}>
              <Text style={styles.name}>阴阳师妄想(楼主)</Text>
              <Text style={styles.meta}>级别: 学徒　威望: 1.0　发帖: 5075　[0 楼]</Text>
            </View>
          </View>
          <Text
            style={[
              styles.preview,
              {
                fontSize: appearance.bodyFontSize,
                lineHeight: appearance.bodyFontSize * appearance.bodyLineHeight,
              },
            ]}
          >
            {PREVIEW_TEXT}
          </Text>
        </View>

        {APPEARANCE_SLIDERS.map((spec) => {
          const value = appearance[spec.key];
          return (
            <Slider
              key={spec.key}
              label={spec.label}
              text={formatSliderValue(spec, value)}
              ratio={sliderRatio(spec, value)}
              onSlide={(ratio) => setAppearance(spec, sliderValueAt(spec, ratio))}
              onStep={(direction) => setAppearance(spec, value + direction * spec.step)}
            />
          );
        })}
      </ScrollView>
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  root: {
    flex: 1,
    backgroundColor: theme.colors.bg,
  },
  reset: {
    ...theme.typography.notice,
    fontWeight: '600',
    color: theme.colors.onTopbar,
    paddingHorizontal: theme.spacing.row,
  },
  body: {
    flex: 1,
  },
  content: {
    paddingBottom: theme.spacing.xl,
  },
  section: {
    ...theme.typography.caption,
    color: theme.colors.primary,
    paddingTop: theme.spacing.lg,
    paddingHorizontal: theme.spacing.page,
    paddingBottom: 6,
  },
  card: {
    marginHorizontal: theme.spacing.md,
    padding: theme.spacing.row,
    borderRadius: theme.radius.lg,
    backgroundColor: theme.colors.surface,
    borderWidth: 1,
    borderColor: theme.colors.divider,
  },
  cardHeader: {
    flexDirection: 'row',
    gap: 11,
  },
  avatar: {
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarText: {
    ...theme.typography.avatarInitial,
    color: theme.colors.onPrimary,
  },
  cardHeaderText: {
    flex: 1,
    minWidth: 0,
    justifyContent: 'center',
  },
  name: {
    ...theme.typography.floorName,
    color: theme.colors.primary,
  },
  meta: {
    ...theme.typography.meta,
    color: theme.colors.meta,
    marginTop: theme.spacing.xs,
  },
  preview: {
    color: theme.colors.fg,
    marginTop: 11,
  },
}));

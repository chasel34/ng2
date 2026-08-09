import { useMemo } from 'react';
import type { TextStyle } from 'react-native';

import { avatarSizeOf, smileyHeightOf, type AppearanceSettings } from '@/core/local';
import { useSettings } from '@/store/settings';

import { typography } from './tokens';

/**
 * 「字体和头像大小」(22 票)的消费口。
 *
 * 这五档是 tokens 里几个字号档位的**用户覆盖**——所以不写进 `tokens.ts`
 * (那里只放设计稿的固定值),而是在这儿把「token 默认 + 用户设置」合成出来。
 * 改了设置这些 hook 立刻返回新值,页面不用重挂。
 */

export const useAppearance = (): AppearanceSettings =>
  useSettings((state) => state.settings.appearance);

/** 楼层正文的字号与行高(设计稿 token 里的 `body` 那一档)。 */
export function useBodyTextStyle(): TextStyle {
  const { bodyFontSize, bodyLineHeight } = useAppearance();
  return useMemo(
    () => ({ fontSize: bodyFontSize, lineHeight: bodyFontSize * bodyLineHeight }),
    [bodyFontSize, bodyLineHeight],
  );
}

/** 主题列表里标题那一行(token 的 `topicTitle`)。行高按设计稿的 1.45 倍跟着走。 */
export function useListTitleStyle(): TextStyle {
  const { listFontSize } = useAppearance();
  return useMemo(
    () => ({ fontSize: listFontSize, lineHeight: listFontSize * LIST_TITLE_LINE_RATIO }),
    [listFontSize],
  );
}

/** 楼层头像的边长。 */
export function useAvatarSize(): number {
  return avatarSizeOf(useAppearance().avatarScale);
}

/** 正文里表情的显示高度。 */
export function useSmileyHeight(): number {
  return smileyHeightOf(useAppearance().smileyScale);
}

/**
 * 「左手模式」(22 票)。开着时把够不着的浮层控件整体镜像到左边:
 * FAB 与它展开的动作列、顶栏右上角弹出的菜单。
 *
 * 只镜像**浮在内容上的**那几样。顶栏按钮、列表行、对话框按钮不动——
 * 它们在页面流里,镜像了就是换一套排版而不是换一只手。
 */
export function useLeftHanded(): boolean {
  return useSettings((state) => state.settings.leftHanded);
}

/** 设计稿主题列表标题的行高倍数(token 表里的 17 / 24.65)。 */
const LIST_TITLE_LINE_RATIO = typography.topicTitle.lineHeight / typography.topicTitle.fontSize;

import * as Clipboard from 'expo-clipboard';
import { useRouter } from 'expo-router';
import { useRef, useState } from 'react';
import { Linking, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { ImageGallery, type GallerySource } from '@/ui/image-gallery';
import {
  MediaPermissionError,
  saveImageToAlbum,
  saveImagesToAlbum,
  shareImage,
} from '@/ui/image-files';
import { stagedImageViewer } from '@/ui/image-viewer-request';
import { OverflowMenu, type MenuItem } from '@/ui/menu';
import { EmptyState } from '@/ui/state-view';
import { usePreferThumbnail } from '@/ui/network';
import { createThemedStyles } from '@/ui/theme';
import { showToast } from '@/ui/toast';
import { TopBar, TopBarButton, TopBarTitle, topBarSpacer } from '@/ui/top-bar';

/**
 * 大图查看器(25 票,设计稿 isViewer 屏)。
 *
 * 顶栏照设计稿:返回箭头、「2 / 3」计数、保存、分享、菜单;
 * 菜单四条(保存到相册/复制图片地址/查看原图/在浏览器中打开)+ 票面要求的
 * 「下载全部」一条(设计稿没画,按菜单分组语言放在分隔空隙后)。
 *
 * 进场参数走 `stageImageViewer` 暂存(本楼全部图片 + 起始下标),
 * 路由本身不带参数;深链直开拿不到暂存时给一句兜底。
 */
export default function ImageViewerScreen() {
  const styles = useStyles();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const [request] = useState(stagedImageViewer);
  const [index, setIndex] = useState(() => request?.index ?? 0);
  const [menuOpen, setMenuOpen] = useState(false);
  // 「查看原图」点过的页(图片加载策略在省流量档时,查看器默认也只拉缩略图)
  const [forcedOriginal, setForcedOriginal] = useState<readonly number[]>([]);
  const preferThumbnail = usePreferThumbnail();
  // 批量下载一次只跑一趟;跑着的时候再点只提示
  const batchRunning = useRef(false);

  const images = request?.images ?? [];
  const current = images[index];

  if (request === undefined || images.length === 0 || current === undefined) {
    return (
      <View style={styles.root}>
        <TopBar paddingHorizontal={4}>
          <TopBarButton
            icon="arrow_back"
            box={46}
            size={24}
            onPress={() => router.back()}
            accessibilityLabel="返回"
          />
          <TopBarTitle variant="sub">图片</TopBarTitle>
        </TopBar>
        <EmptyState icon="image" text="没有可查看的图片" />
      </View>
    );
  }

  const sources: GallerySource[] = images.map((image, i) => {
    // 省流量档没点「查看原图」之前先看缩略图;其余情况直接拉原图、缩略图当渐进占位
    const wantThumbnail =
      preferThumbnail && !forcedOriginal.includes(i) && image.thumbnailUrl !== undefined;
    if (wantThumbnail) return { uri: image.thumbnailUrl! };
    return {
      uri: image.url,
      ...(image.thumbnailUrl === undefined ? {} : { placeholderUri: image.thumbnailUrl }),
    };
  });

  const reportError = (cause: unknown) => {
    if (cause instanceof MediaPermissionError) {
      showToast('需要相册权限,请在系统设置里允许');
      return;
    }
    showToast(cause instanceof Error && cause.message !== '' ? cause.message : '操作失败,稍后再试');
  };

  /** 顶栏保存钮与菜单「保存到相册」共用(设计稿 doDownload 的 toast 文案)。 */
  const saveCurrent = () => {
    showToast('正在保存…');
    saveImageToAlbum(current.url)
      .then(() => showToast('已保存到 相册/NGA'))
      .catch(reportError);
  };

  /** 系统分享分享图片文件本体;失败(下载不动)退回分享地址,总不能什么都不给。 */
  const shareCurrent = () => {
    shareImage(current.url).catch(() => {
      void Clipboard.setStringAsync(current.url);
      showToast('图片没下载下来,已改为复制图片地址');
    });
  };

  /** 菜单「下载全部」:本楼全部图片顺序保存进相册。 */
  const saveAll = () => {
    if (batchRunning.current) {
      showToast('已经在下载了,等这一批跑完');
      return;
    }
    batchRunning.current = true;
    showToast(`开始下载 ${images.length} 张图片…`);
    saveImagesToAlbum(images.map((image) => image.url))
      .then(({ saved, failed }) => {
        showToast(failed === 0 ? `已保存 ${saved} 张到 相册/NGA` : `已保存 ${saved} 张,${failed} 张失败`);
      })
      .catch(reportError)
      .finally(() => {
        batchRunning.current = false;
      });
  };

  /** 菜单条目与顺序照设计稿 `MENUS.viewer`;「下载全部」是票面要求的加项。 */
  const menuItems = (): readonly MenuItem[] => {
    const pick = (run: () => void) => () => {
      setMenuOpen(false);
      run();
    };
    return [
      { key: 'save', label: '保存到相册', onPress: pick(saveCurrent) },
      {
        key: 'copy',
        label: '复制图片地址',
        onPress: pick(() => {
          void Clipboard.setStringAsync(current.url);
          showToast('图片地址已复制');
        }),
      },
      {
        key: 'original',
        label: '查看原图',
        onPress: pick(() => {
          if (sources[index]?.uri === current.url) {
            showToast('当前已是原图');
            return;
          }
          showToast('正在加载原图…');
          setForcedOriginal((list) => [...list, index]);
        }),
      },
      {
        key: 'browser',
        label: '在浏览器中打开',
        onPress: pick(() => {
          void Linking.openURL(current.url);
        }),
      },
      {
        key: 'save-all',
        label: `下载全部(${images.length} 张)`,
        gapBefore: true,
        onPress: pick(saveAll),
      },
    ];
  };

  return (
    <View style={styles.root}>
      {/* 顶栏照设计稿 isViewer:返回 24 / 计数 18·500 / 保存 23 / 分享 23 / 更多 22 */}
      <TopBar paddingHorizontal={4}>
        <TopBarButton
          icon="arrow_back"
          box={46}
          size={24}
          onPress={() => router.back()}
          accessibilityLabel="返回"
        />
        <Text style={styles.counter}>
          {index + 1} / {images.length}
        </Text>
        {/* 这一屏的保存/分享是设计稿里少见的 46 档,只有「更多」维持 44 */}
        <TopBarButton
          icon="save"
          box={46}
          size={23}
          onPress={saveCurrent}
          accessibilityLabel="保存到相册"
          style={topBarSpacer}
        />
        <TopBarButton
          icon="share"
          box={46}
          size={23}
          onPress={shareCurrent}
          accessibilityLabel="分享"
        />
        <TopBarButton
          icon="more_vert"
          size={22}
          onPress={() => setMenuOpen(true)}
          accessibilityLabel="更多"
        />
      </TopBar>

      <ImageGallery sources={sources} index={index} onIndexChange={setIndex} />

      <OverflowMenu
        open={menuOpen}
        onClose={() => setMenuOpen(false)}
        items={menuItems()}
        top={insets.top + 6}
      />
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  root: {
    flex: 1,
    backgroundColor: theme.colors.bg,
  },
  /** 设计稿:计数 18/500、左距 8、字距 .5,顶栏前景色 */
  counter: {
    fontSize: 18,
    fontWeight: '500',
    letterSpacing: 0.5,
    marginLeft: 8,
    color: theme.colors.onTopbar,
  },
}));

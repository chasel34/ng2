import { useRouter } from 'expo-router';
import { useState } from 'react';
import {
  Pressable,
  RefreshControl,
  ScrollView,
  Text,
  View,
} from 'react-native';

import { blockWordError, type BlockWordList } from '@/core/api';
import { FILTER_KIND_LABELS, type FilterRule, type FilterRuleInput } from '@/core/local';
import { useAccounts } from '@/store/accounts';
import { useBlockWordMutations, useBlockWords, useLocalFilters } from '@/store/filters';
import { useLeftHanded } from '@/ui/appearance';
import { LoadFailedNotice } from '@/ui/error-screen';
import { EmptyState, LoadingState } from '@/ui/state-view';
import { FilterRuleDialog } from '@/ui/filter-rule-dialog';
import { Icon, type IconName } from '@/ui/icon';
import { InputDialog } from '@/ui/input-dialog';
import { showLoginPrompt } from '@/ui/login-prompt';
import { showSnackbar } from '@/ui/snackbar';
import { createThemedStyles, useTheme } from '@/ui/theme';
import { dateText } from '@/ui/time-text';
import { TopBar, TopBarButton, TopBarTitle } from '@/ui/top-bar';

/** 设计稿 isFilters 屏:tab 高 42、未选中透明度 .6、指示条 3px。 */
const TAB_HEIGHT = 42;

type FilterTab = 'local' | 'officialUsers' | 'officialWords';

const TABS: readonly { key: FilterTab; label: string; hint: string }[] = [
  {
    key: 'local',
    label: '本地规则',
    hint: '仅存在本机,卸载即丢失;命中的主题在列表里隐藏,命中的楼层折叠成一行。',
  },
  { key: 'officialUsers', label: '官方用户屏蔽', hint: '与 NGA 账号云端同步,可在这里解除。' },
  {
    key: 'officialWords',
    label: '官方关键词',
    hint: '与 NGA 账号云端同步,和网页版「控制面板 → 屏蔽」是同一份数据。',
  },
];

const KIND_ICONS: Readonly<Record<FilterRule['kind'], IconName>> = {
  // 设计稿标的 person_off / text_fields / tag,前两个里只有 text_fields 在打包的
  // 静态图标字体里(见 icons.generated.ts),另外两个取同一套里最接近的
  user: 'person',
  keyword: 'text_fields',
  category: 'bookmark',
};

/**
 * 屏蔽规则页(设计稿 isFilters,CONTEXT.md「屏蔽规则」)。
 *
 * 三个 tab 对应两种数据源:本地规则在 MMKV(游客也能用),另外两个 tab 是同一张
 * 云端表(API 文档 §11.5 的 `get/set_block_word`)的两半,写回去也是整表覆盖。
 *
 * 入口在设置页(22 票的 `settings` 屏最后一行「屏蔽规则」),本票只把路由立起来。
 */
export default function FiltersScreen() {
  const styles = useStyles();
  const theme = useTheme();
  const router = useRouter();
  const leftHanded = useLeftHanded();

  const [tab, setTab] = useState<FilterTab>('local');
  const [addRuleOpen, setAddRuleOpen] = useState(false);
  const [addWordOpen, setAddWordOpen] = useState(false);
  const [wordError, setWordError] = useState<string | undefined>(undefined);

  const signedIn = useAccounts((state) => state.currentUid) !== null;
  const rules = useLocalFilters((state) => state.rules);
  const addRule = useLocalFilters((state) => state.add);
  const removeRule = useLocalFilters((state) => state.remove);
  const restoreRule = useLocalFilters((state) => state.restore);

  const blockWords = useBlockWords();
  const { addWord, removeWord, removeUser, replace } = useBlockWordMutations();

  /** 云端写操作统一的失败话术:接口是整表覆盖,失败时表还是原来那张。 */
  const cloudFailed = (error: unknown) => {
    showSnackbar(error instanceof Error ? error.message : '官方屏蔽词没能同步到云端');
  };

  /** 云端删除:成功后给一手「撤销」——把改动前那张表原样写回去。 */
  const undoable = (message: string, run: () => Promise<void>) => {
    const before = blockWords.data;
    run().then(() => {
      showSnackbar(
        message,
        before === undefined
          ? undefined
          : { label: '撤销', onPress: () => void replace(before).catch(cloudFailed) },
      );
    }, cloudFailed);
  };

  const onAddRule = (input: FilterRuleInput) => {
    setAddRuleOpen(false);
    const rule = addRule(input);
    showSnackbar(`已添加${FILTER_KIND_LABELS[rule.kind]}规则:${rule.value}`, {
      label: '撤销',
      onPress: () => removeRule(rule.id),
    });
  };

  const localRows = () =>
    rules.map((rule) => (
      <FilterRow
        key={rule.id}
        icon={KIND_ICONS[rule.kind]}
        text={`${FILTER_KIND_LABELS[rule.kind]}:${rule.value}`}
        sub={localRuleSub(rule)}
        onDelete={() => {
          removeRule(rule.id);
          showSnackbar(`已删除规则:${rule.value}`, {
            label: '撤销',
            onPress: () => restoreRule(rule),
          });
        }}
      />
    ));

  const officialRows = (list: BlockWordList) =>
    tab === 'officialUsers'
      ? list.users.map((user) => (
          <FilterRow
            key={user.uid ?? user.name}
            icon={KIND_ICONS.user}
            text={`用户:${user.name}`}
            sub={user.uid === undefined ? '云端' : `云端 · uid ${user.uid}`}
            onDelete={() =>
              undoable(`已解除对 ${user.name} 的屏蔽`, () => removeUser(user))
            }
          />
        ))
      : list.words.map((word) => (
          <FilterRow
            key={word}
            icon={KIND_ICONS.keyword}
            text={`关键词:${word}`}
            sub="云端 · 命中标题或正文"
            onDelete={() => undoable(`已删除官方关键词:${word}`, () => removeWord(word))}
          />
        ));

  /** 云端两个 tab 共用一份取数状态:游客、加载中、失败、空表各有各的话。 */
  const officialBody = () => {
    if (!signedIn) {
      return <EmptyState variant="inline" icon="person" text="登录后才能读写官方屏蔽词" />;
    }
    if (blockWords.isPending) return <LoadingState variant="inline" />;
    const list = blockWords.data;
    if (list === undefined) {
      return (
        <LoadFailedNotice error={blockWords.error} onRetry={() => void blockWords.refetch()} />
      );
    }
    const rows = officialRows(list);
    if (rows.length === 0) {
      return (
        <EmptyState
          variant="inline"
          icon="block"
          text={tab === 'officialUsers' ? '云端还没有屏蔽的用户' : '云端还没有屏蔽关键词'}
        />
      );
    }
    return rows;
  };

  // 官方用户屏蔽只做「读 + 解除」(票面),加人要 uid,输入框拿不到,所以那个 tab 不给 FAB
  const showFab = tab !== 'officialUsers';

  return (
    <View style={styles.root}>
      <TopBar
        paddingHorizontal={4}
        below={
          <View style={styles.tabRow}>
            {TABS.map((item) => (
              <Pressable
                key={item.key}
                style={styles.tab}
                onPress={() => setTab(item.key)}
                accessibilityLabel={item.label}
              >
                <Text style={[styles.tabLabel, tab !== item.key && styles.tabLabelInactive]}>
                  {item.label}
                </Text>
                {tab === item.key && <View style={styles.tabIndicator} />}
              </Pressable>
            ))}
          </View>
        }
      >
        <TopBarButton
          icon="arrow_back"
          box={46}
          size={24}
          onPress={() => router.back()}
          accessibilityLabel="返回"
        />
        <TopBarTitle variant="sub">屏蔽规则</TopBarTitle>
      </TopBar>

      <ScrollView
        style={styles.body}
        // 官方那两个 tab 是云端数据,而且用户可能刚在网页版改过——留一个下拉重读的口子。
        // 本地规则没有「刷新」这回事,改了立刻就在屏上
        refreshControl={
          tab === 'local' || !signedIn ? undefined : (
            <RefreshControl
              refreshing={blockWords.isFetching}
              onRefresh={() => void blockWords.refetch()}
              colors={[theme.colors.primary]}
              tintColor={theme.colors.primary}
            />
          )
        }
      >
        <Text style={styles.hint}>{TABS.find((item) => item.key === tab)?.hint}</Text>
        {tab === 'local' ? (
          rules.length === 0 ? (
            <EmptyState variant="inline" icon="block" text="还没有本地屏蔽规则" />
          ) : (
            localRows()
          )
        ) : (
          officialBody()
        )}
        <View style={styles.bottomSpacer} />
      </ScrollView>

      {showFab && (
        <Pressable
          style={[styles.fab, leftHanded ? styles.fabLeft : styles.fabRight]}
          onPress={() => {
            if (tab === 'local') {
              setAddRuleOpen(true);
              return;
            }
            if (!signedIn) {
              showLoginPrompt(router, '登录后才能写官方屏蔽词');
              return;
            }
            setWordError(undefined);
            setAddWordOpen(true);
          }}
          accessibilityLabel="新增规则"
        >
          <Icon name="add" size={22} color={theme.colors.onFab} />
          <Text style={styles.fabLabel}>新增规则</Text>
        </Pressable>
      )}

      <FilterRuleDialog
        open={addRuleOpen}
        onCancel={() => setAddRuleOpen(false)}
        onConfirm={onAddRule}
      />

      {/* 官方关键词只有「一个词」要填,用通用输入框即可;空格是云端表的分隔符,拦在这里 */}
      <InputDialog
        open={addWordOpen}
        title="新增官方关键词"
        hint="写入 NGA 账号云端,与网页版互通;中间不能有空格"
        {...(wordError === undefined ? {} : { error: wordError })}
        confirmLabel="保存"
        onCancel={() => setAddWordOpen(false)}
        onConfirm={(value) => {
          const invalid = blockWordError(value);
          if (invalid !== undefined) {
            setWordError(invalid);
            return;
          }
          setAddWordOpen(false);
          const word = value.trim();
          addWord(word).then(() => showSnackbar(`已添加官方关键词:${word}`), cloudFailed);
        }}
      />
    </View>
  );
}

/** 本地规则行的第二行灰字。设计稿在这行放添加时间与生效范围。 */
function localRuleSub(rule: FilterRule): string {
  const added = rule.createdAt === undefined ? undefined : `${dateText(rule.createdAt)} 添加`;
  const scope =
    rule.kind === 'keyword'
      ? rule.regex
        ? '正则 · 命中标题或正文'
        : '命中标题或正文'
      : rule.kind === 'user'
        ? rule.uid === undefined
          ? '按用户名匹配'
          : `uid ${rule.uid}`
        : '全部版块';
  return added === undefined ? scope : `${added} · ${scope}`;
}

interface FilterRowProps {
  icon: IconName;
  text: string;
  sub: string;
  onDelete: () => void;
}

/** 设计稿 isFilters 的一行:图标 + 两行文字 + 右侧红色 close。 */
function FilterRow({ icon, text, sub, onDelete }: FilterRowProps) {
  const styles = useStyles();
  const theme = useTheme();
  return (
    <View style={styles.row}>
      <Icon name={icon} size={20} color={theme.colors.fg2} />
      <View style={styles.rowText}>
        <Text style={styles.rowTitle} numberOfLines={2}>
          {text}
        </Text>
        <Text style={styles.rowSub}>{sub}</Text>
      </View>
      <Pressable onPress={onDelete} hitSlop={10} accessibilityLabel={`删除${text}`}>
        <Icon name="close" size={19} color={theme.colors.danger} />
      </Pressable>
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  root: {
    flex: 1,
    backgroundColor: theme.colors.bg,
  },
  body: {
    flex: 1,
  },
  // 设计稿:tab 在顶栏色块里,选中项不透明 + 底部 3px 指示条(currentColor)
  tabRow: {
    flexDirection: 'row',
    paddingHorizontal: 6,
  },
  tab: {
    height: TAB_HEIGHT,
    paddingHorizontal: 15,
    alignItems: 'center',
    justifyContent: 'center',
  },
  tabIndicator: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    height: 3,
    backgroundColor: theme.colors.onTopbar,
  },
  tabLabel: {
    fontSize: 14,
    fontWeight: '600',
    color: theme.colors.onTopbar,
  },
  tabLabelInactive: {
    opacity: 0.6,
  },
  hint: {
    ...theme.typography.listSubtitle,
    color: theme.colors.meta,
    paddingVertical: theme.spacing.md,
    paddingHorizontal: theme.spacing.lg,
    backgroundColor: theme.colors.surface2,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.md,
    paddingVertical: theme.spacing.row,
    paddingHorizontal: theme.spacing.lg,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.divider,
  },
  rowText: {
    flex: 1,
    minWidth: 0,
  },
  rowTitle: {
    ...theme.typography.dialogListItem,
    color: theme.colors.fg,
  },
  rowSub: {
    ...theme.typography.meta,
    color: theme.colors.meta,
    marginTop: 4,
  },
  bottomSpacer: {
    height: 80,
  },
  // 设计稿:扩展 FAB,高 50、左右 20、圆角 16、距右 20 距底 24
  fab: {
    position: 'absolute',
    bottom: 24,
    height: 50,
    paddingHorizontal: theme.spacing.xl,
    borderRadius: 16,
    backgroundColor: theme.colors.fab,
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.sm,
    boxShadow: theme.shadows.elevation2,
  },
  fabLabel: {
    ...theme.typography.accountAction,
    color: theme.colors.onFab,
  },
  // 左手模式(22 票):FAB 镜像到左下角
  fabRight: {
    right: theme.spacing.xl,
  },
  fabLeft: {
    left: theme.spacing.xl,
  },
}));

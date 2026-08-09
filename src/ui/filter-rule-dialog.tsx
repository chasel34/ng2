import { useEffect, useState } from 'react';
import {
  Animated,
  Keyboard,
  Pressable,
  Text,
  TextInput,
  View,
} from 'react-native';

import {
  FILTER_KIND_LABELS,
  validateFilterRule,
  type FilterRuleInput,
  type FilterRuleKind,
} from '@/core/local';

import { Icon } from './icon';
import { useOverlayAnimation, OverlayScrim, popStyle } from './overlay';
import { createThemedStyles, useTheme } from './theme';

/** 三类规则的顺序照设计稿对话框提示语「支持 用户 / 关键词 / 分类」。 */
const KINDS: readonly FilterRuleKind[] = ['user', 'keyword', 'category'];

const PLACEHOLDERS: Readonly<Record<FilterRuleKind, string>> = {
  user: '例如 xtl150ok',
  keyword: '例如 内部消息',
  category: '例如 转帖(标题里 [] 括起来的那个词)',
};

export interface FilterRuleDialogProps {
  open: boolean;
  onCancel: () => void;
  onConfirm: (input: FilterRuleInput) => void;
}

/**
 * 「新增屏蔽规则」对话框(设计稿 `DLG.addFilter`)。
 *
 * 设计稿画的是通用的单输入框对话框,提示语写「支持 用户 / 关键词 / 分类,可用正则」——
 * 那是让用户自己在一行里表达类型。这里改成显式的类型分段 + 正则开关:
 * 票面要求「类型选择 + 输入」,而且非法正则要就地提示,靠猜输入格式做不到这件事。
 * 外壳(标题 / 下划线输入 / 一行 hint / 取消·保存)与 `InputDialog` 保持一致。
 */
export function FilterRuleDialog({ open, onCancel, onConfirm }: FilterRuleDialogProps) {
  const styles = useStyles();
  const theme = useTheme();
  const [kind, setKind] = useState<FilterRuleKind>('keyword');
  const [value, setValue] = useState('');
  const [regex, setRegex] = useState(false);
  // 提交过一次才显示错误:一进来就红着说「请输入关键词」太凶
  const [submitted, setSubmitted] = useState(false);
  const { scrim, panel } = useOverlayAnimation(open);

  // 每次打开都回到空白表单,不留上一次的残留
  useEffect(() => {
    if (!open) return;
    setKind('keyword');
    setValue('');
    setRegex(false);
    setSubmitted(false);
  }, [open]);

  if (!open) return null;

  // 正则只对关键词有意义(用户名与分类是精确比对),换类型时开关一并收起来
  const regexOn = regex && kind === 'keyword';
  const error = validateFilterRule({ kind, value, regex: regexOn });

  const confirm = () => {
    setSubmitted(true);
    if (error !== undefined) return;
    Keyboard.dismiss();
    onConfirm({ kind, value, ...(regexOn ? { regex: true } : {}) });
  };

  return (
    <View style={styles.root}>
      <OverlayScrim progress={scrim} onPress={onCancel} />
      <Animated.View style={[styles.panel, popStyle(panel)]}>
        <Text style={styles.title}>新增屏蔽规则</Text>

        <View style={styles.kindRow}>
          {KINDS.map((item) => (
            <Pressable
              key={item}
              style={[styles.kind, kind === item && styles.kindOn]}
              onPress={() => setKind(item)}
              accessibilityLabel={`按${FILTER_KIND_LABELS[item]}屏蔽`}
            >
              <Text style={[styles.kindLabel, kind === item && styles.kindLabelOn]}>
                {FILTER_KIND_LABELS[item]}
              </Text>
            </Pressable>
          ))}
        </View>

        <View style={styles.field}>
          <TextInput
            value={value}
            onChangeText={setValue}
            placeholder={PLACEHOLDERS[kind]}
            placeholderTextColor={theme.colors.meta}
            autoFocus
            autoCapitalize="none"
            autoCorrect={false}
            returnKeyType="done"
            onSubmitEditing={confirm}
            style={styles.input}
            cursorColor={theme.colors.primary}
            selectionColor={theme.colors.primary}
          />
          {submitted && error !== undefined ? (
            <Text style={[styles.hint, styles.error]}>{error}</Text>
          ) : (
            <Text style={styles.hint}>
              {kind === 'keyword'
                ? '命中标题或正文即屏蔽'
                : kind === 'user'
                  ? '按用户名精确匹配,大小写不敏感'
                  : '匹配标题里方括号括起来的分类,如 [转帖]'}
            </Text>
          )}
        </View>

        {/* 正则开关只在关键词下出现:用户名与分类走精确比对,给了开关反而误导 */}
        {kind === 'keyword' && (
          <Pressable
            style={styles.regexRow}
            onPress={() => setRegex((on) => !on)}
            accessibilityLabel={regexOn ? '关闭正则匹配' : '按正则匹配'}
            hitSlop={6}
          >
            <Icon
              name={regexOn ? 'check_box' : 'check_box_outline_blank'}
              size={22}
              color={regexOn ? theme.colors.primary : theme.colors.fg2}
            />
            <Text style={styles.regexLabel}>按正则匹配</Text>
          </Pressable>
        )}

        <View style={styles.actions}>
          <Pressable style={styles.cancel} onPress={onCancel}>
            <Text style={styles.cancelLabel}>取消</Text>
          </Pressable>
          <Pressable style={styles.confirm} onPress={confirm}>
            <Text style={styles.confirmLabel}>保存</Text>
          </Pressable>
        </View>
      </Animated.View>
    </View>
  );
}

const useStyles = createThemedStyles((theme) => ({
  root: {
    position: 'absolute',
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
  },
  panel: {
    width: '100%',
    borderRadius: theme.radius.dialog,
    backgroundColor: theme.colors.menu,
    paddingTop: 22,
    paddingHorizontal: 22,
    paddingBottom: theme.spacing.row,
    boxShadow: theme.shadows.elevation2,
  },
  title: {
    ...theme.typography.dialogTitle,
    color: theme.colors.fg,
  },
  // 类型分段:照子版块横条那套 tag 样式(圆角 sm + 分隔线描边),选中填主题色
  kindRow: {
    flexDirection: 'row',
    gap: theme.spacing.sm,
    marginTop: theme.spacing.lg,
  },
  kind: {
    paddingVertical: 6,
    paddingHorizontal: 14,
    borderRadius: theme.radius.sm,
    borderWidth: 1,
    borderColor: theme.colors.divider,
    backgroundColor: theme.colors.surface2,
  },
  kindOn: {
    backgroundColor: theme.colors.primary,
    borderColor: theme.colors.primary,
  },
  kindLabel: {
    ...theme.typography.listMeta,
    color: theme.colors.fg2,
  },
  kindLabelOn: {
    color: theme.colors.onPrimary,
    fontWeight: '600',
  },
  field: {
    marginTop: theme.spacing.lg,
  },
  input: {
    ...theme.typography.drawerItem,
    color: theme.colors.fg,
    borderBottomWidth: 2,
    borderBottomColor: theme.colors.primary,
    paddingHorizontal: 2,
    paddingBottom: 7,
    paddingTop: 0,
  },
  hint: {
    ...theme.typography.meta,
    color: theme.colors.meta,
    marginTop: 7,
  },
  error: {
    color: theme.colors.danger,
  },
  regexRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    marginTop: theme.spacing.md,
  },
  regexLabel: {
    ...theme.typography.dialogListItem,
    color: theme.colors.fg2,
  },
  actions: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 6,
    marginTop: theme.spacing.row,
  },
  cancel: {
    height: 40,
    paddingHorizontal: theme.spacing.lg,
    borderRadius: theme.radius.full,
    alignItems: 'center',
    justifyContent: 'center',
  },
  cancelLabel: {
    ...theme.typography.dialogAction,
    color: theme.colors.fg2,
  },
  confirm: {
    height: 40,
    paddingHorizontal: theme.spacing.xl,
    borderRadius: theme.radius.full,
    backgroundColor: theme.colors.primary,
    alignItems: 'center',
    justifyContent: 'center',
  },
  confirmLabel: {
    ...theme.typography.dialogAction,
    color: theme.colors.onPrimary,
  },
}));

import { Pressable, Text, View } from 'react-native';

import { isVoteClosed, voteSharePercent, type Vote, type VoteOption } from '@/core/local';

import { Icon } from './icon';
import { createThemedStyles, useTheme } from './theme';
import { showNotAvailable } from './toast';

/**
 * 楼层里的投票(spec §1:v1 只读渲染,点投票按钮给「本版本未开放」)。
 *
 * 数据来自楼层的 `vote` 字段,不是 BBCode——所以这块画在楼层卡片上而不是渲染器里。
 * 每一项的百分比按**组内**票数算,和网页版一致(分组见 `core/local/vote`)。
 */

const KIND_LABELS: Record<Vote['kind'], string> = {
  vote: '投票',
  bet: '投注',
  score: '评分',
  scoreEntry: '评分',
  qa: '问答',
};

export function VoteBlock({ vote }: { vote: Vote }) {
  const styles = useStyles();
  const closed = isVoteClosed(vote, Date.now() / 1000);
  const label = KIND_LABELS[vote.kind];

  return (
    <View style={styles.root}>
      <View style={styles.header}>
        <Text style={styles.title}>{label}</Text>
        {closed && <Text style={styles.closed}>已结束</Text>}
      </View>

      {vote.groups.map((group, groupIndex) => (
        <View key={groupIndex} style={styles.group}>
          {group.title !== undefined && (
            <Text style={styles.groupTitle}>
              {group.title} · 共 {group.votes} 票
            </Text>
          )}
          {group.options.map((option) => (
            <VoteRow
              key={option.id}
              option={option}
              groupVotes={group.votes}
              multiple={vote.multiple}
            />
          ))}
        </View>
      ))}

      <Text style={styles.info}>{summaryOf(vote, label)}</Text>

      {/* 投票操作是 v1 排除项;按钮照旧摆着,点了给 toast */}
      <Pressable
        style={[styles.button, closed && styles.buttonDisabled]}
        onPress={showNotAvailable}
        accessibilityRole="button"
      >
        <Text style={styles.buttonLabel}>{closed ? `${label}已结束` : label}</Text>
      </Pressable>
    </View>
  );
}

function VoteRow({
  option,
  groupVotes,
  multiple,
}: {
  option: VoteOption;
  groupVotes: number;
  multiple: boolean;
}) {
  const styles = useStyles();
  const theme = useTheme();
  const percent = voteSharePercent(option.votes, groupVotes);

  return (
    <View style={styles.row}>
      <View style={styles.rowTop}>
        <Icon
          name={
            multiple
              ? option.chosen
                ? 'check_box'
                : 'check_box_outline_blank'
              : option.chosen
                ? 'radio_button_checked'
                : 'radio_button_unchecked'
          }
          size={17}
          color={option.chosen ? theme.colors.primary : theme.colors.meta}
        />
        <Text style={styles.optionTitle}>{option.title}</Text>
        <Text style={styles.optionCount}>
          {option.votes} 票 · {percent}%
        </Text>
      </View>
      <View style={styles.bar}>
        <View style={[styles.barFill, { width: `${percent}%` }]} />
      </View>
    </View>
  );
}

/** 底下那行说明,项目与顺序照网页版的 `voteBasicInfoString`。 */
function summaryOf(vote: Vote, label: string): string {
  const parts = [`共计 ${vote.voters} 人${label}`, `共计 ${vote.totalVotes} 票`];
  parts.push(`最多选择 ${vote.maxSelect} 项`);
  if (vote.requirement !== undefined) parts.push(vote.requirement);
  if (vote.endAt !== undefined) parts.push(`结束时间 ${formatEnd(vote.endAt)}`);
  if (vote.resultAfterVote) parts.push('提交后可查看结果');
  if (vote.resultAfterEnd) parts.push('结束后可查看结果');
  return parts.join(' · ');
}

/** NGA 的时间一律按论坛所在时区(UTC+8)显示,不跟设备时区走。 */
function formatEnd(endAt: number): string {
  const shifted = new Date(endAt * 1000 + 8 * 60 * 60 * 1000);
  const pad = (value: number) => String(value).padStart(2, '0');
  return (
    `${shifted.getUTCFullYear()}-${pad(shifted.getUTCMonth() + 1)}-${pad(shifted.getUTCDate())} ` +
    `${pad(shifted.getUTCHours())}:${pad(shifted.getUTCMinutes())}`
  );
}

const useStyles = createThemedStyles((theme) => ({
  root: {
    marginTop: 11,
    padding: theme.spacing.md,
    borderRadius: theme.radius.md,
    backgroundColor: theme.colors.surface2,
    gap: theme.spacing.sm,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.spacing.sm,
  },
  title: {
    ...theme.typography.notice,
    fontWeight: '600',
    color: theme.colors.fg,
    flex: 1,
  },
  closed: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
  },
  group: {
    gap: 7,
  },
  groupTitle: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
    marginTop: theme.spacing.xs,
  },
  row: {
    gap: 3,
  },
  rowTop: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  optionTitle: {
    ...theme.typography.notice,
    color: theme.colors.fg,
    flex: 1,
  },
  optionCount: {
    ...theme.typography.listMeta,
    color: theme.colors.fg2,
  },
  bar: {
    height: 6,
    borderRadius: 3,
    backgroundColor: theme.colors.track,
    overflow: 'hidden',
  },
  barFill: {
    height: '100%',
    borderRadius: 3,
    backgroundColor: theme.colors.primary,
  },
  info: {
    ...theme.typography.listMeta,
    color: theme.colors.meta,
  },
  button: {
    height: 38,
    borderRadius: theme.radius.md,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: theme.colors.primary,
  },
  buttonDisabled: {
    backgroundColor: theme.colors.track,
  },
  buttonLabel: {
    ...theme.typography.dialogAction,
    color: theme.colors.onPrimary,
  },
}));

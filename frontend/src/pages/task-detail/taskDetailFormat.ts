import type {
  ExecutionPhase,
  ExecutionStatus,
  HealthStatus,
  RuntimeState,
  TaskExecution,
  TaskKind,
} from '../../api';
import { phaseLabels } from '../../lib/format';

export type StatusTone = 'neutral' | 'positive' | 'warning' | 'negative' | 'active';

export function executionTone(status: ExecutionStatus): StatusTone {
  if (status === 'RUNNING' || status === 'QUEUED') return 'active';
  if (status === 'RETRY_WAIT') return 'warning';
  if (status === 'FAILED' || status === 'CANCELLED') return 'negative';
  return 'positive';
}

export function healthTone(status: HealthStatus): Exclude<StatusTone, 'active'> {
  if (status === 'HEALTHY') return 'positive';
  if (status === 'DEGRADED') return 'warning';
  if (status === 'ERROR') return 'negative';
  return 'neutral';
}

export function runtimeTone(status: RuntimeState): StatusTone {
  if (status === 'RUNNING' || status === 'QUEUED') return 'active';
  if (status === 'RETRY_WAIT') return 'warning';
  if (status === 'ERROR') return 'negative';
  if (status === 'COMPLETED') return 'positive';
  return 'neutral';
}

export function triggerLabel(trigger: TaskExecution['trigger']): string {
  if (trigger === 'MANUAL') return '手动触发';
  if (trigger === 'DISCOVERED') return '内容发现触发';
  return '定时调度';
}

export function executionMetricLabels(kind: TaskKind) {
  if (kind === 'CREATOR_WATCH') {
    return {
      pages: '建立失败',
      discovered: '发现内容',
      inserted: '新建自动采集',
      duplicates: '关联已有',
    } as const;
  }
  return {
    pages: '已抓取页数',
    discovered: '发现评论',
    inserted: '新增评论',
    duplicates: '跳过重复',
  } as const;
}

export function creatorDiscoveryFailures(execution: Pick<
  TaskExecution,
  'commentsDiscovered' | 'commentsInserted' | 'duplicatesSkipped'
>): number {
  return Math.max(
    0,
    execution.commentsDiscovered
      - execution.commentsInserted
      - execution.duplicatesSkipped,
  );
}

export function taskPhaseLabel(kind: TaskKind, phase: ExecutionPhase): string {
  if (!phase) return '等待开始';
  if (kind !== 'CREATOR_WATCH') return phaseLabels[phase];
  const creatorLabels: Record<NonNullable<ExecutionPhase>, string> = {
    RESOLVING_SOURCE: '解析 UP 主',
    FETCHING_COMMENTS: '发现内容',
    PERSISTING_COMMENTS: '建立采集任务',
    WAITING_RATE_LIMIT: '等待限流恢复',
    SCHEDULING_NEXT: '安排下次监控',
  };
  return creatorLabels[phase];
}

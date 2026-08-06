import type {
  CollectionMode,
  DesiredState,
  ExecutionPhase,
  ExecutionStatus,
  HealthStatus,
  RuntimeState,
  SourceType,
} from '../api';

const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

const fullDateFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

export function formatDate(value: string | null): string {
  return value ? dateFormatter.format(new Date(value)) : '—';
}

export function formatDateTime(value: string | null): string {
  return value ? fullDateFormatter.format(new Date(value)) : '—';
}

export function formatDuration(start: string | null, end: string | null): string {
  if (!start) return '—';
  const milliseconds = new Date(end ?? Date.now()).getTime() - new Date(start).getTime();
  if (milliseconds < 60_000) {
    return `${String(Math.max(1, Math.round(milliseconds / 1_000)))} 秒`;
  }
  return `${String(Math.round(milliseconds / 60_000))} 分钟`;
}

export const sourceLabels: Record<SourceType, string> = {
  VIDEO: '视频',
  DYNAMIC: '动态',
  CREATOR: 'UP 主',
};

export const modeLabels: Record<CollectionMode, string> = {
  FOLLOW_ONLY: '持续增量采集',
  BACKFILL_ONLY: '单次历史回填',
};

export const desiredLabels: Record<DesiredState, string> = {
  ACTIVE: '已启用',
  PAUSED: '已暂停',
};

export const runtimeLabels: Record<RuntimeState, string> = {
  IDLE: '空闲',
  QUEUED: '排队中',
  RUNNING: '运行中',
  RETRY_WAIT: '等待重试',
  ERROR: '异常',
  COMPLETED: '已完成',
};

export const healthLabels: Record<HealthStatus, string> = {
  UNKNOWN: '未知',
  HEALTHY: '正常',
  DEGRADED: '需关注',
  ERROR: '异常',
};

export const executionLabels: Record<ExecutionStatus, string> = {
  QUEUED: '排队中',
  RUNNING: '运行中',
  RETRY_WAIT: '等待重试',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
};

export const phaseLabels: Record<NonNullable<ExecutionPhase>, string> = {
  RESOLVING_SOURCE: '解析来源',
  FETCHING_COMMENTS: '获取评论',
  PERSISTING_COMMENTS: '保存评论',
  WAITING_RATE_LIMIT: '等待限流恢复',
  SCHEDULING_NEXT: '安排下次执行',
};

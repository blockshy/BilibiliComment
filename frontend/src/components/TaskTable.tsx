import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from '@tanstack/react-table';
import { Eye, Pause, Play, RotateCw } from 'lucide-react';
import type { HealthStatus, RuntimeState, TaskSummary } from '../api';
import {
  desiredLabels,
  formatDate,
  healthLabels,
  modeLabels,
  runtimeLabels,
  sourceLabels,
} from '../lib/format';
import { EmptyState } from './PageState';
import { StatusText } from './StatusText';

interface TaskTableProps {
  tasks: TaskSummary[];
  recentlyUpdatedTaskId: string | null;
  pendingTaskId: string | null;
  onSelect: (taskId: string) => void;
  onPause: (taskId: string) => void;
  onResume: (taskId: string) => void;
  onRun: (taskId: string) => void;
}

function runtimeTone(status: RuntimeState): 'neutral' | 'positive' | 'warning' | 'negative' | 'active' {
  if (status === 'RUNNING' || status === 'QUEUED') return 'active';
  if (status === 'RETRY_WAIT') return 'warning';
  if (status === 'ERROR') return 'negative';
  if (status === 'COMPLETED') return 'positive';
  return 'neutral';
}

function healthTone(status: HealthStatus): 'neutral' | 'positive' | 'warning' | 'negative' {
  if (status === 'HEALTHY') return 'positive';
  if (status === 'DEGRADED') return 'warning';
  if (status === 'ERROR') return 'negative';
  return 'neutral';
}

const columnHelper = createColumnHelper<TaskSummary>();

function taskCategoryLabel(task: TaskSummary): string {
  if (task.kind === 'CREATOR_WATCH') return 'UP 主监控';
  return task.origin === 'DISCOVERED' ? '自动采集' : '独立采集';
}

function taskRelationText(task: TaskSummary): string | null {
  if (!task.parentTask) return null;
  return task.relationMode === 'REFERENCED'
    ? `关联至 ${task.parentTask.name}`
    : `来自 ${task.parentTask.name}`;
}

export function TaskTable({
  tasks,
  recentlyUpdatedTaskId,
  pendingTaskId,
  onSelect,
  onPause,
  onResume,
  onRun,
}: TaskTableProps) {
  const columns = [
    columnHelper.accessor('name', {
      header: '任务',
      cell: ({ row }) => (
        <div className="task-name-cell">
          <div className="task-name-heading">
            <strong>{row.original.name}</strong>
            <span className="task-kind-label">{taskCategoryLabel(row.original)}</span>
          </div>
          <span>{sourceLabels[row.original.source.type]} · {row.original.source.id}</span>
          {taskRelationText(row.original) ? (
            <span className="task-parent-context">{taskRelationText(row.original)}</span>
          ) : null}
        </div>
      ),
    }),
    columnHelper.accessor('collectionMode', {
      header: '模式',
      cell: ({ getValue }) => modeLabels[getValue()],
    }),
    columnHelper.accessor('desiredState', {
      header: '调度',
      cell: ({ row, getValue }) => (
        <div className="stacked-value">
          <span>{desiredLabels[getValue()]}</span>
          <span>{row.original.scheduleLabel}</span>
        </div>
      ),
    }),
    columnHelper.accessor('runtimeState', {
      header: '当前状态',
      cell: ({ getValue }) => (
        <StatusText tone={runtimeTone(getValue())}>{runtimeLabels[getValue()]}</StatusText>
      ),
    }),
    columnHelper.accessor('lastSuccessAt', {
      header: '最近成功',
      cell: ({ getValue }) => formatDate(getValue()),
    }),
    columnHelper.accessor('nextRunAt', {
      header: '下次执行',
      cell: ({ getValue }) => formatDate(getValue()),
    }),
    columnHelper.display({
      id: 'activity',
      header: '采集概况',
      cell: ({ row }) => {
        const task = row.original;
        if (task.kind !== 'CREATOR_WATCH') {
          return (
            <div className="task-activity-cell">
              <strong>上次新增 {task.latestInsertedCount.toLocaleString('zh-CN')}</strong>
              <span>条评论</span>
            </div>
          );
        }
        const summary = task.discoveredSummary;
        if (!summary) return <span className="muted-value">暂无发现记录</span>;
        return (
          <div className="task-activity-cell">
            <strong>已发现 {summary.total.toLocaleString('zh-CN')} 个内容</strong>
            <span>
              运行 {summary.queuedOrRunning.toLocaleString('zh-CN')}
              {' · '}重试 {summary.retryWaiting.toLocaleString('zh-CN')}
              {' · '}<span className={summary.errors > 0 ? 'negative-value' : undefined}>
                异常 {summary.errors.toLocaleString('zh-CN')}
              </span>
            </span>
            <span>24 小时新增评论 {summary.commentsInserted24h.toLocaleString('zh-CN')}</span>
          </div>
        );
      },
    }),
    columnHelper.accessor('health', {
      header: '健康状态',
      cell: ({ getValue }) => (
        <StatusText tone={healthTone(getValue())}>{healthLabels[getValue()]}</StatusText>
      ),
    }),
    columnHelper.display({
      id: 'actions',
      header: '',
      cell: ({ row }) => {
        const task = row.original;
        const pending = pendingTaskId === task.id;
        const activeExecution = task.runtimeState === 'RUNNING'
          || task.runtimeState === 'QUEUED'
          || task.runtimeState === 'RETRY_WAIT';
        return (
          <div className="row-actions" onClick={(event) => event.stopPropagation()}>
            <button
              type="button"
              className="icon-button"
              aria-label={`查看 ${task.name}`}
              onClick={() => onSelect(task.id)}
            >
              <Eye size={16} aria-hidden="true" />
            </button>
            <button
              type="button"
              className="icon-button"
              aria-label={task.desiredState === 'ACTIVE' ? `暂停 ${task.name}` : `恢复 ${task.name}`}
              disabled={pending}
              onClick={() => task.desiredState === 'ACTIVE' ? onPause(task.id) : onResume(task.id)}
            >
              {task.desiredState === 'ACTIVE'
                ? <Pause size={16} aria-hidden="true" />
                : <Play size={16} aria-hidden="true" />}
            </button>
            <button
              type="button"
              className="icon-button"
              aria-label={`立即执行 ${task.name}`}
              disabled={pending || activeExecution}
              onClick={() => onRun(task.id)}
            >
              <RotateCw size={16} aria-hidden="true" />
            </button>
          </div>
        );
      },
    }),
  ];
  const table = useReactTable({ data: tasks, columns, getCoreRowModel: getCoreRowModel() });

  if (tasks.length === 0) {
    return <EmptyState title="没有匹配的任务" detail="调整筛选条件，或新建一个评论采集任务。" />;
  }

  return (
    <div className="table-scroll">
      <table className="data-table task-table">
        <thead>
          {table.getHeaderGroups().map((headerGroup) => (
            <tr key={headerGroup.id}>
              {headerGroup.headers.map((header) => (
                <th key={header.id} data-column={header.column.id}>
                  {header.isPlaceholder ? null : flexRender(header.column.columnDef.header, header.getContext())}
                </th>
              ))}
            </tr>
          ))}
        </thead>
        <tbody>
          {table.getRowModel().rows.map((row) => (
            <tr
              key={row.id}
              tabIndex={0}
              className={recentlyUpdatedTaskId === row.original.id ? 'is-live-updated' : undefined}
              onClick={() => onSelect(row.original.id)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') onSelect(row.original.id);
              }}
            >
              {row.getVisibleCells().map((cell) => (
                <td key={cell.id} data-column={cell.column.id}>
                  {flexRender(cell.column.columnDef.cell, cell.getContext())}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

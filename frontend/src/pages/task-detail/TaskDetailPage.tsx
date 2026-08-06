import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, ChevronRight, Pause, Play, RotateCw } from 'lucide-react';
import { useState } from 'react';
import {
  Link,
  NavLink,
  Outlet,
  useLocation,
  useParams,
} from 'react-router-dom';
import { api } from '../../api';
import { ErrorState, LoadingState } from '../../components/PageState';
import { StatusText } from '../../components/StatusText';
import {
  desiredLabels,
  healthLabels,
  modeLabels,
  runtimeLabels,
  sourceLabels,
} from '../../lib/format';
import { useLiveEvents } from '../../live/LiveEventsContext';
import { useTaskActions } from '../../tasks/useTaskActions';
import { healthTone, runtimeTone } from './taskDetailFormat';

interface DetailLocationState {
  from?: unknown;
}

function safeBackTarget(state: DetailLocationState | null): string {
  const from = state?.from;
  return typeof from === 'string' && (
    from === '/tasks'
    || from.startsWith('/tasks?')
    || /^\/tasks\/[1-9][0-9]*\/discovered(?:\?.*)?$/.test(from)
  )
    ? from
    : '/tasks';
}

export function TaskDetailPage() {
  const { taskId = '' } = useParams<{ taskId: string }>();
  return <TaskDetailContent key={taskId} taskId={taskId} />;
}

function TaskDetailContent({ taskId }: { taskId: string }) {
  const location = useLocation();
  const { connectionState } = useLiveEvents();
  const taskActions = useTaskActions();
  const taskQuery = useQuery({
    queryKey: ['task', taskId],
    queryFn: () => api.getTask(taskId),
    enabled: Boolean(taskId),
    refetchInterval: connectionState === 'OPEN' ? false : 10_000,
  });
  const [backTarget] = useState(() => safeBackTarget(location.state as DetailLocationState | null));
  const backLabel = backTarget.includes('/discovered') ? '返回发现内容' : '返回任务';

  if (taskQuery.isPending) {
    return (
      <div className="page-container task-detail-page">
        <LoadingState label="正在加载任务详情" />
      </div>
    );
  }

  if (taskQuery.isError) {
    return (
      <div className="page-container task-detail-page">
        <Link className="back-link" to={backTarget}>
          <ArrowLeft size={16} aria-hidden="true" />
          {backLabel}
        </Link>
        <ErrorState message="任务详情加载失败" onRetry={() => void taskQuery.refetch()} />
      </div>
    );
  }

  const task = taskQuery.data;
  const activeExecution = task.runtimeState === 'RUNNING'
    || task.runtimeState === 'QUEUED'
    || task.runtimeState === 'RETRY_WAIT';
  const actionPending = taskActions.pendingTaskId === task.id;
  const basePath = `/tasks/${encodeURIComponent(task.id)}`;

  return (
    <div className="page-container task-detail-page">
      <Link className="back-link" to={backTarget}>
        <ArrowLeft size={16} aria-hidden="true" />
        {backLabel}
      </Link>

      {task.parentTask ? (
        <nav className="task-parent-breadcrumb" aria-label="任务层级">
          <Link to={`/tasks/${encodeURIComponent(task.parentTask.taskId)}/discovered`}>
            {task.parentTask.name}
          </Link>
          <ChevronRight size={14} aria-hidden="true" />
          <span>{task.name}</span>
        </nav>
      ) : null}

      <header className="task-detail-header">
        <div className="task-detail-heading">
          <h1>{task.name}</h1>
          <p>
            <span className="task-kind-label">
              {task.kind === 'CREATOR_WATCH'
                ? 'UP 主监控'
                : task.origin === 'DISCOVERED' ? '自动采集' : '独立采集'}
            </span>
            <span aria-hidden="true"> · </span>
            {sourceLabels[task.source.type]} · {task.source.id}
          </p>
          <div className="task-detail-status" aria-label="任务状态">
            <StatusText tone={runtimeTone(task.runtimeState)}>
              {runtimeLabels[task.runtimeState]}
            </StatusText>
            <span aria-hidden="true">·</span>
            <StatusText tone={healthTone(task.health)}>
              {healthLabels[task.health]}
            </StatusText>
            <span aria-hidden="true">·</span>
            <span>{desiredLabels[task.desiredState]}</span>
            <span aria-hidden="true">·</span>
            <span>{modeLabels[task.collectionMode]}</span>
          </div>
        </div>
        <div className="task-detail-actions">
          <button
            type="button"
            className="button button--secondary"
            disabled={actionPending}
            onClick={() => taskActions.perform(
              task.id,
              task.desiredState === 'ACTIVE' ? 'pause' : 'resume',
            )}
          >
            {task.desiredState === 'ACTIVE'
              ? <Pause size={16} aria-hidden="true" />
              : <Play size={16} aria-hidden="true" />}
            {task.desiredState === 'ACTIVE' ? '暂停任务' : '恢复任务'}
          </button>
          <button
            type="button"
            className="button button--primary"
            disabled={actionPending || activeExecution}
            title={activeExecution ? '任务已有活动执行' : undefined}
            onClick={() => taskActions.perform(task.id, 'run')}
          >
            <RotateCw size={16} aria-hidden="true" />
            立即执行
          </button>
        </div>
      </header>

      {taskActions.error ? <div className="inline-error" role="alert">{taskActions.error}</div> : null}
      {activeExecution ? (
        <p className="task-action-note">暂停任务只会阻止后续调度，不会中断当前执行。</p>
      ) : null}

      <nav className="task-detail-nav" aria-label="任务详情导航">
        <NavLink to={basePath} end>概览</NavLink>
        {task.kind === 'CREATOR_WATCH' ? <NavLink to={`${basePath}/discovered`}>发现内容</NavLink> : null}
        {task.kind === 'CONTENT_COMMENTS' ? <NavLink to={`${basePath}/comments`}>评论</NavLink> : null}
        <NavLink to={`${basePath}/executions`}>执行记录</NavLink>
        <NavLink to={`${basePath}/configuration`}>配置</NavLink>
      </nav>

      <div key={task.id} className="task-detail-content">
        <Outlet context={{ taskId: task.id, task }} />
      </div>
    </div>
  );
}

import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { Filter, Plus, Search, X } from 'lucide-react';
import { useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import {
  api,
  mocksEnabled,
  type CollectionMode,
  type HealthStatus,
  type RuntimeState,
  type SourceType,
  type TaskScope,
} from '../api';
import { CreateTaskDrawer } from '../components/CreateTaskDrawer';
import { ErrorState, LoadingState } from '../components/PageState';
import { TaskTable } from '../components/TaskTable';
import { useLiveEvents } from '../live/LiveEventsContext';
import { useTaskActions } from '../tasks/useTaskActions';

function enumParam<T extends string>(value: string | null, values: readonly T[]): T | '' {
  return value && values.includes(value as T) ? value as T : '';
}

export function TasksPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();
  const { connectionState, recentlyUpdatedTaskId } = useLiveEvents();
  const taskActions = useTaskActions();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const query = searchParams.get('query') ?? '';
  const sourceType = enumParam<SourceType>(searchParams.get('sourceType'), ['VIDEO', 'DYNAMIC', 'CREATOR']);
  const collectionMode = enumParam<CollectionMode>(searchParams.get('collectionMode'), ['FOLLOW_ONLY', 'BACKFILL_ONLY']);
  const runtimeState = enumParam<RuntimeState>(searchParams.get('runtimeState'), ['IDLE', 'QUEUED', 'RUNNING', 'RETRY_WAIT', 'ERROR', 'COMPLETED']);
  const health = enumParam<HealthStatus>(searchParams.get('health'), ['HEALTHY', 'DEGRADED', 'ERROR', 'UNKNOWN']);
  const scope = enumParam<TaskScope>(searchParams.get('scope'), ['PRIMARY', 'MANAGED', 'ALL']) || 'PRIMARY';

  const setFilter = (name: string, value: string): void => {
    const next = new URLSearchParams(searchParams);
    if (value) next.set(name, value);
    else next.delete(name);
    next.delete('cursor');
    setSearchParams(next, { replace: true });
  };

  const tasksQuery = useInfiniteQuery({
    queryKey: ['tasks', { query, sourceType, collectionMode, runtimeState, health, scope }],
    queryFn: ({ pageParam }) => api.getTasks({
      query,
      sourceType,
      collectionMode,
      runtimeState,
      health,
      scope,
      cursor: pageParam,
      limit: 50,
    }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    refetchInterval: connectionState === 'OPEN' ? false : 5_000,
  });
  const summaryQuery = useQuery({
    queryKey: ['tasks', 'summary'],
    queryFn: api.getDashboardSummary,
    refetchInterval: connectionState === 'OPEN' ? false : 5_000,
  });
  const clearFilters = (): void => {
    const next = new URLSearchParams(searchParams);
    ['query', 'sourceType', 'collectionMode', 'runtimeState', 'health'].forEach(
      (name) => next.delete(name),
    );
    next.delete('cursor');
    setSearchParams(next, { replace: true });
  };

  const setScope = (nextScope: TaskScope): void => {
    const next = new URLSearchParams(searchParams);
    if (nextScope === 'PRIMARY') next.delete('scope');
    else next.set('scope', nextScope);
    next.delete('cursor');
    setSearchParams(next, { replace: true });
  };

  const showErrorTasks = (): void => {
    const next = new URLSearchParams(searchParams);
    next.set('scope', 'ALL');
    next.set('health', 'ERROR');
    next.delete('cursor');
    setSearchParams(next, { replace: true });
  };

  const hasFilters = Boolean(query || sourceType || collectionMode || runtimeState || health);
  const tasks = tasksQuery.data?.pages.flatMap((page) => page.items) ?? [];
  const taskTotal = tasksQuery.data?.pages[0]?.total ?? 0;

  return (
    <div className="task-workspace">
      <section className="task-list-pane">
        <header className="page-header">
          <div>
            <h1>任务</h1>
            <p>创建采集任务，查看调度状态和最近执行。</p>
          </div>
          <button type="button" className="button button--primary" onClick={() => setDrawerOpen(true)}>
            <Plus size={17} aria-hidden="true" />
            新建任务
          </button>
        </header>

        {mocksEnabled ? (
          <div className="mock-notice" role="status">
            当前使用本地模拟数据；普通开发和生产构建不会自动启用。
          </div>
        ) : null}

        <dl className="summary-strip" aria-label="任务摘要">
          <div><dt>主要任务</dt><dd>{summaryQuery.data?.primaryActiveTasks ?? '—'}</dd></div>
          <div><dt>自动采集</dt><dd>{summaryQuery.data?.managedActiveTasks ?? '—'}</dd></div>
          <div><dt>运行中</dt><dd>{summaryQuery.data?.runningTasks ?? '—'}</dd></div>
          <div><dt>等待重试</dt><dd>{summaryQuery.data?.retryWaitingTasks ?? '—'}</dd></div>
          <div><dt>24 小时新增</dt><dd>{summaryQuery.data?.commentsInserted24h.toLocaleString('zh-CN') ?? '—'}</dd></div>
          <div><dt>最近失败</dt><dd>{summaryQuery.data?.recentFailures ?? '—'}</dd></div>
        </dl>

        <nav className="task-scope-switch" aria-label="任务范围">
          {([
            ['PRIMARY', '主要任务'],
            ['MANAGED', '自动采集'],
            ['ALL', '全部'],
          ] as const).map(([value, label]) => (
            <button
              key={value}
              type="button"
              aria-pressed={scope === value}
              onClick={() => setScope(value)}
            >
              {label}
            </button>
          ))}
        </nav>

        <div className="task-toolbar">
          <label className="search-field">
            <Search size={16} aria-hidden="true" />
            <span className="sr-only">搜索任务</span>
            <input
              value={query}
              onChange={(event) => setFilter('query', event.target.value)}
              placeholder="搜索名称或来源 ID"
            />
          </label>
          <div className="filter-controls">
            <Filter size={16} aria-hidden="true" />
            <label>
              <span className="sr-only">来源类型</span>
              <select value={sourceType} onChange={(event) => setFilter('sourceType', event.target.value)}>
                <option value="">全部来源</option>
                <option value="VIDEO">视频</option>
                <option value="DYNAMIC">动态</option>
                <option value="CREATOR">UP 主</option>
              </select>
            </label>
            <label>
              <span className="sr-only">采集模式</span>
              <select
                value={collectionMode}
                onChange={(event) => setFilter('collectionMode', event.target.value)}
              >
                <option value="">全部模式</option>
                <option value="FOLLOW_ONLY">持续增量采集</option>
                <option value="BACKFILL_ONLY">单次历史回填</option>
              </select>
            </label>
            <label>
              <span className="sr-only">运行状态</span>
              <select value={runtimeState} onChange={(event) => setFilter('runtimeState', event.target.value)}>
                <option value="">全部状态</option>
                <option value="IDLE">空闲</option>
                <option value="QUEUED">排队中</option>
                <option value="RUNNING">运行中</option>
                <option value="RETRY_WAIT">等待重试</option>
                <option value="ERROR">异常</option>
                <option value="COMPLETED">已完成</option>
              </select>
            </label>
            <label>
              <span className="sr-only">健康状态</span>
              <select value={health} onChange={(event) => setFilter('health', event.target.value)}>
                <option value="">全部健康状态</option>
                <option value="HEALTHY">正常</option>
                <option value="DEGRADED">需关注</option>
                <option value="ERROR">异常</option>
                <option value="UNKNOWN">未知</option>
              </select>
            </label>
            <button type="button" className="text-button" onClick={showErrorTasks}>
              查看异常任务
            </button>
            {hasFilters ? (
              <button type="button" className="text-button" onClick={clearFilters}>
                <X size={14} aria-hidden="true" />
                清除
              </button>
            ) : null}
          </div>
        </div>

        {taskActions.error ? <div className="inline-error" role="alert">{taskActions.error}</div> : null}
        {tasksQuery.isPending ? <LoadingState label="正在加载任务" /> : null}
        {tasksQuery.isError ? <ErrorState message="任务列表加载失败" onRetry={() => void tasksQuery.refetch()} /> : null}
        {tasksQuery.data ? (
          <>
            <TaskTable
              tasks={tasks}
              recentlyUpdatedTaskId={recentlyUpdatedTaskId}
              pendingTaskId={taskActions.pendingTaskId}
              onSelect={(selectedId) => {
                void navigate(`/tasks/${selectedId}`, {
                  state: { from: `${location.pathname}${location.search}` },
                });
              }}
              onPause={(selectedId) => taskActions.perform(selectedId, 'pause')}
              onResume={(selectedId) => taskActions.perform(selectedId, 'resume')}
              onRun={(selectedId) => taskActions.perform(selectedId, 'run')}
            />
            <div className="task-pagination">
              <span>已显示 {tasks.length} / {taskTotal}</span>
              {tasksQuery.hasNextPage ? (
                <button
                  type="button"
                  className="button button--secondary"
                  disabled={tasksQuery.isFetchingNextPage}
                  onClick={() => void tasksQuery.fetchNextPage()}
                >
                  {tasksQuery.isFetchingNextPage ? '正在加载…' : '加载更多'}
                </button>
              ) : null}
            </div>
          </>
        ) : null}
      </section>

      <CreateTaskDrawer
        open={drawerOpen}
        onOpenChange={setDrawerOpen}
        onCreated={(task) => {
          void navigate(`/tasks/${task.id}`, {
            state: { from: `${location.pathname}${location.search}` },
          });
        }}
        onExistingTask={(existingTaskId) => {
          setDrawerOpen(false);
          void navigate(`/tasks/${existingTaskId}`, {
            state: { from: `${location.pathname}${location.search}` },
          });
        }}
      />
    </div>
  );
}

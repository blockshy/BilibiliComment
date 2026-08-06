import { useInfiniteQuery } from '@tanstack/react-query';
import { Eye, Search, X } from 'lucide-react';
import { Navigate, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import {
  api,
  type HealthStatus,
  type RuntimeState,
  type SourceType,
} from '../../api';
import { EmptyState, ErrorState, LoadingState } from '../../components/PageState';
import { StatusText } from '../../components/StatusText';
import {
  desiredLabels,
  formatDate,
  healthLabels,
  runtimeLabels,
  sourceLabels,
} from '../../lib/format';
import { useTaskDetail } from './TaskDetailContext';
import { healthTone, runtimeTone } from './taskDetailFormat';

function enumParam<T extends string>(value: string | null, values: readonly T[]): T | '' {
  return value && values.includes(value as T) ? value as T : '';
}

export function TaskDiscoveredPage() {
  const { taskId, task } = useTaskDetail();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();
  const query = searchParams.get('query') ?? '';
  const sourceType = enumParam<Exclude<SourceType, 'CREATOR'>>(
    searchParams.get('sourceType'),
    ['VIDEO', 'DYNAMIC'],
  );
  const runtimeState = enumParam<RuntimeState>(
    searchParams.get('runtimeState'),
    ['IDLE', 'QUEUED', 'RUNNING', 'RETRY_WAIT', 'ERROR', 'COMPLETED'],
  );
  const health = enumParam<HealthStatus>(
    searchParams.get('health'),
    ['HEALTHY', 'DEGRADED', 'ERROR', 'UNKNOWN'],
  );
  const hasFilters = Boolean(query || sourceType || runtimeState || health);

  const setFilter = (name: string, value: string): void => {
    const next = new URLSearchParams(searchParams);
    if (value) next.set(name, value);
    else next.delete(name);
    setSearchParams(next, { replace: true });
  };

  const discoveredQuery = useInfiniteQuery({
    queryKey: ['discovered-tasks', taskId, { query, sourceType, runtimeState, health }],
    queryFn: ({ pageParam }) => api.getDiscoveredTasks(taskId, {
      query,
      sourceType,
      runtimeState,
      health,
      cursor: pageParam,
      limit: 50,
    }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: task.kind === 'CREATOR_WATCH',
  });
  const items = discoveredQuery.data?.pages.flatMap((page) => page.items) ?? [];
  const total = discoveredQuery.data?.pages[0]?.total ?? 0;
  const summary = task.discoveredSummary;

  if (task.kind !== 'CREATOR_WATCH') {
    return <Navigate to={`/tasks/${encodeURIComponent(taskId)}`} replace />;
  }

  return (
    <section className="discovered-workspace" aria-labelledby="discovered-title">
      <header className="section-heading discovered-heading">
        <div>
          <h2 id="discovered-title">发现内容</h2>
          <p>查看该 UP 主发现的内容及其评论采集状态。</p>
        </div>
        <span>{total.toLocaleString('zh-CN')} 个内容</span>
      </header>

      <dl className="discovered-summary" aria-label="发现内容状态摘要">
        <div><dt>内容总数</dt><dd>{summary?.total.toLocaleString('zh-CN') ?? '—'}</dd></div>
        <div><dt>尚未执行</dt><dd>{summary?.neverStarted.toLocaleString('zh-CN') ?? '—'}</dd></div>
        <div><dt>排队或运行</dt><dd>{summary?.queuedOrRunning.toLocaleString('zh-CN') ?? '—'}</dd></div>
        <div><dt>等待重试</dt><dd>{summary?.retryWaiting.toLocaleString('zh-CN') ?? '—'}</dd></div>
        <div className={summary && summary.errors > 0 ? 'has-error' : undefined}>
          <dt>异常</dt><dd>{summary?.errors.toLocaleString('zh-CN') ?? '—'}</dd>
        </div>
        <div><dt>24 小时新增评论</dt><dd>{summary?.commentsInserted24h.toLocaleString('zh-CN') ?? '—'}</dd></div>
      </dl>

      <div className="task-toolbar discovered-toolbar">
        <label className="search-field">
          <Search size={16} aria-hidden="true" />
          <span className="sr-only">搜索发现内容</span>
          <input
            value={query}
            onChange={(event) => setFilter('query', event.target.value)}
            placeholder="搜索内容名称或来源 ID"
          />
        </label>
        <div className="filter-controls">
          <label>
            <span className="sr-only">内容类型</span>
            <select value={sourceType} onChange={(event) => setFilter('sourceType', event.target.value)}>
              <option value="">全部内容</option>
              <option value="VIDEO">视频</option>
              <option value="DYNAMIC">动态</option>
            </select>
          </label>
          <label>
            <span className="sr-only">运行状态</span>
            <select value={runtimeState} onChange={(event) => setFilter('runtimeState', event.target.value)}>
              <option value="">全部状态</option>
              <option value="IDLE">尚未执行</option>
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
          {hasFilters ? (
            <button
              type="button"
              className="text-button"
              onClick={() => setSearchParams({}, { replace: true })}
            >
              <X size={14} aria-hidden="true" />
              清除
            </button>
          ) : null}
        </div>
      </div>

      {discoveredQuery.isPending ? <LoadingState label="正在加载发现内容" /> : null}
      {discoveredQuery.isError ? (
        <ErrorState message="发现内容加载失败" onRetry={() => void discoveredQuery.refetch()} />
      ) : null}
      {!discoveredQuery.isPending && !discoveredQuery.isError && items.length === 0 ? (
        <EmptyState
          title={hasFilters ? '没有匹配的发现内容' : '尚未发现内容'}
          detail={hasFilters ? '调整筛选条件后重试。' : '监控任务发现视频或动态后会显示在这里。'}
        />
      ) : null}
      {items.length > 0 ? (
        <div className="table-scroll discovered-table-scroll">
          <table className="data-table discovered-table">
            <thead>
              <tr>
                <th>内容</th>
                <th>采集方式</th>
                <th>运行状态</th>
                <th>评论总量</th>
                <th>24 小时新增</th>
                <th>健康状态</th>
                <th aria-label="操作" />
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr
                  key={`${item.task.id}-${item.firstDiscoveredAt}`}
                  tabIndex={0}
                  onClick={() => void navigate(`/tasks/${item.task.id}`, {
                    state: { from: `${location.pathname}${location.search}` },
                  })}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter') {
                      void navigate(`/tasks/${item.task.id}`, {
                        state: { from: `${location.pathname}${location.search}` },
                      });
                    }
                  }}
                >
                  <td data-label="内容" className="discovered-content-cell">
                    <strong>{item.task.name}</strong>
                    <span>{sourceLabels[item.task.source.type]} · {item.task.source.id}</span>
                  </td>
                  <td data-label="采集方式" className="discovered-relation-cell">
                    <strong>{item.task.relationMode === 'REFERENCED' ? '关联已有' : '自动管理'}</strong>
                    <span>最近发现 {formatDate(item.lastDiscoveredAt)}</span>
                  </td>
                  <td data-label="运行状态">
                    <div className="stacked-value">
                      <StatusText tone={runtimeTone(item.task.runtimeState)}>
                        {runtimeLabels[item.task.runtimeState]}
                      </StatusText>
                      <span>{desiredLabels[item.task.desiredState]}</span>
                    </div>
                  </td>
                  <td data-label="评论总量" className="numeric-value">
                    {item.commentsTotal.toLocaleString('zh-CN')}
                  </td>
                  <td data-label="24 小时新增" className="numeric-value">
                    {item.commentsInserted24h.toLocaleString('zh-CN')}
                  </td>
                  <td data-label="健康状态">
                    <StatusText tone={healthTone(item.task.health)}>
                      {healthLabels[item.task.health]}
                    </StatusText>
                  </td>
                  <td className="discovered-row-action" onClick={(event) => event.stopPropagation()}>
                    <button
                      type="button"
                      className="icon-button"
                      aria-label={`查看 ${item.task.name}`}
                      onClick={() => void navigate(`/tasks/${item.task.id}`, {
                        state: { from: `${location.pathname}${location.search}` },
                      })}
                    >
                      <Eye size={16} aria-hidden="true" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      {discoveredQuery.hasNextPage ? (
        <button
          type="button"
          className="button button--secondary load-more"
          disabled={discoveredQuery.isFetchingNextPage}
          onClick={() => void discoveredQuery.fetchNextPage()}
        >
          {discoveredQuery.isFetchingNextPage ? '正在加载…' : '加载更多发现内容'}
        </button>
      ) : null}
    </section>
  );
}

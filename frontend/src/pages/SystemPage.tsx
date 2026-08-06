import { useQuery } from '@tanstack/react-query';
import { RefreshCw } from 'lucide-react';
import { api } from '../api';
import { ErrorState, LoadingState } from '../components/PageState';
import { StatusText } from '../components/StatusText';
import { formatDate } from '../lib/format';
import { useLiveEvents } from '../live/LiveEventsContext';

function healthTone(status: 'UP' | 'DEGRADED' | 'DOWN'): 'positive' | 'warning' | 'negative' {
  if (status === 'UP') return 'positive';
  if (status === 'DEGRADED') return 'warning';
  return 'negative';
}

const healthLabel = { UP: '正常', DEGRADED: '需关注', DOWN: '不可用' } as const;

export function SystemPage() {
  const { connectionState } = useLiveEvents();
  const summary = useQuery({
    queryKey: ['system', 'summary'],
    queryFn: api.getSystemSummary,
    refetchInterval: connectionState === 'OPEN' ? 15_000 : 5_000,
  });

  return (
    <div className="page-container page-container--narrow">
      <header className="page-header">
        <div>
          <h1>系统状态</h1>
          <p>查看服务、调度器和上游连接的当前状态。</p>
        </div>
        <button type="button" className="button button--secondary" onClick={() => void summary.refetch()} disabled={summary.isFetching}>
          <RefreshCw size={16} aria-hidden="true" />
          刷新
        </button>
      </header>

      {summary.isPending ? <LoadingState label="正在加载系统状态" /> : null}
      {summary.isError ? <ErrorState message="系统状态加载失败" onRetry={() => void summary.refetch()} /> : null}
      {summary.data ? (
        <div className="system-sections">
          <section>
            <h2>依赖服务</h2>
            <dl className="status-list">
              <div>
                <dt>API</dt>
                <dd><StatusText tone={healthTone(summary.data.api)}>{healthLabel[summary.data.api]}</StatusText></dd>
              </div>
              <div>
                <dt>PostgreSQL</dt>
                <dd><StatusText tone={summary.data.database === 'UP' ? 'positive' : 'negative'}>{healthLabel[summary.data.database]}</StatusText></dd>
              </div>
              <div>
                <dt>Bilibili 上游</dt>
                <dd><StatusText tone={healthTone(summary.data.bilibili)}>{healthLabel[summary.data.bilibili]}</StatusText></dd>
              </div>
              <div>
                <dt>实时事件</dt>
                <dd><StatusText tone={connectionState === 'OPEN' ? 'positive' : 'warning'}>{connectionState === 'OPEN' ? '已连接' : '恢复中'}</StatusText></dd>
              </div>
            </dl>
          </section>

          <section>
            <h2>任务执行资源</h2>
            <dl className="status-list status-list--numbers">
              <div>
                <dt>自动调度</dt>
                <dd>
                  <StatusText tone={summary.data.schedulerEnabled ? 'positive' : 'negative'}>
                    {summary.data.schedulerEnabled ? '已启用' : '未启用'}
                  </StatusText>
                </dd>
              </div>
              <div><dt>已注册调度</dt><dd>{summary.data.schedulerActive}</dd></div>
              <div><dt>Worker 活动</dt><dd>{summary.data.workerActive} / {summary.data.workerPoolSize}</dd></div>
              <div><dt>Worker 队列</dt><dd>{summary.data.workerQueueSize}</dd></div>
              <div><dt>SSE 连接</dt><dd>{summary.data.sseConnections}</dd></div>
            </dl>
          </section>

          <section>
            <h2>最近异常</h2>
            <dl className="status-list status-list--numbers">
              <div><dt>失败执行</dt><dd>{summary.data.recentFailureCount}</dd></div>
              <div><dt>限流事件</dt><dd>{summary.data.rateLimitedCount}</dd></div>
            </dl>
            <p className="data-freshness">采样时间：{formatDate(summary.data.measuredAt)}</p>
          </section>
        </div>
      ) : null}
    </div>
  );
}

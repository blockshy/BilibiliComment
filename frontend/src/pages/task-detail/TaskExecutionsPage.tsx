import { EmptyState, ErrorState, LoadingState } from '../../components/PageState';
import { StatusText } from '../../components/StatusText';
import {
  executionLabels,
  formatDate,
  formatDuration,
} from '../../lib/format';
import { useTaskDetail } from './TaskDetailContext';
import {
  creatorDiscoveryFailures,
  executionMetricLabels,
  executionTone,
  taskPhaseLabel,
  triggerLabel,
} from './taskDetailFormat';
import { useTaskExecutions } from './useTaskExecutions';

export function TaskExecutionsPage() {
  const { taskId, task } = useTaskDetail();
  const executionsQuery = useTaskExecutions(taskId);
  const executions = executionsQuery.data?.pages.flatMap((page) => page.items) ?? [];
  const metrics = executionMetricLabels(task.kind);

  return (
    <section className="detail-section" aria-labelledby="execution-history-title">
      <header className="section-heading">
        <div>
          <h2 id="execution-history-title">执行记录</h2>
          <p>按开始时间倒序显示任务的调度与采集结果。</p>
        </div>
        <span>{executionsQuery.data?.pages[0]?.total ?? 0} 条</span>
      </header>
      {executionsQuery.isPending ? <LoadingState label="正在加载执行记录" /> : null}
      {executionsQuery.isError ? (
        <ErrorState message="执行记录加载失败" onRetry={() => void executionsQuery.refetch()} />
      ) : null}
      {executions.length === 0 && !executionsQuery.isPending && !executionsQuery.isError ? (
        <EmptyState title="暂无执行记录" detail="任务开始后会在这里显示每次执行。" />
      ) : null}
      {executions.length > 0 ? (
        <ol className="execution-timeline execution-timeline--wide">
          {executions.map((execution) => (
            <li key={execution.id}>
              <div className="timeline-marker" aria-hidden="true" />
              <div className="timeline-content">
                <div className="timeline-heading">
                  <StatusText tone={executionTone(execution.status)}>
                    {executionLabels[execution.status]}
                  </StatusText>
                  <span>{triggerLabel(execution.trigger)}</span>
                  <time>{formatDate(execution.startedAt)}</time>
                </div>
                <div className="timeline-meta">
                  <span>{taskPhaseLabel(task.kind, execution.phase)}</span>
                  <span>耗时 {formatDuration(execution.startedAt, execution.finishedAt)}</span>
                  <span>{metrics.discovered} {execution.commentsDiscovered}</span>
                  <span>{metrics.inserted} {execution.commentsInserted}</span>
                  <span>{metrics.duplicates} {execution.duplicatesSkipped}</span>
                  {task.kind === 'CREATOR_WATCH' ? (
                    <span>建立失败 {creatorDiscoveryFailures(execution)}</span>
                  ) : null}
                  <span>重试 {execution.retryCount}</span>
                </div>
                {execution.errorSummary ? <p className="execution-error">{execution.errorSummary}</p> : null}
              </div>
            </li>
          ))}
        </ol>
      ) : null}
      {executionsQuery.hasNextPage ? (
        <button
          type="button"
          className="button button--secondary load-more"
          disabled={executionsQuery.isFetchingNextPage}
          onClick={() => void executionsQuery.fetchNextPage()}
        >
          {executionsQuery.isFetchingNextPage ? '正在加载…' : '加载更多执行记录'}
        </button>
      ) : null}
    </section>
  );
}

import { useQuery } from '@tanstack/react-query';
import { AlertTriangle } from 'lucide-react';
import { api } from '../../api';
import { EmptyState, ErrorState, LoadingState } from '../../components/PageState';
import { StatusText } from '../../components/StatusText';
import {
  desiredLabels,
  executionLabels,
  formatDate,
  healthLabels,
  modeLabels,
  runtimeLabels,
  sourceLabels,
} from '../../lib/format';
import { useTaskDetail } from './TaskDetailContext';
import {
  creatorDiscoveryFailures,
  executionMetricLabels,
  executionTone,
  healthTone,
  runtimeTone,
  taskPhaseLabel,
  triggerLabel,
} from './taskDetailFormat';
import { useTaskExecutions } from './useTaskExecutions';

export function TaskOverviewPage() {
  const { taskId, task } = useTaskDetail();
  const executionsQuery = useTaskExecutions(taskId);
  const systemQuery = useQuery({
    queryKey: ['system', 'summary'],
    queryFn: api.getSystemSummary,
    staleTime: 15_000,
  });
  const currentExecution = executionsQuery.data?.pages[0]?.items[0];
  const metrics = executionMetricLabels(task.kind);
  const schedulerDisabled = task.desiredState === 'ACTIVE'
    && task.nextRunAt !== null
    && systemQuery.data?.schedulerEnabled === false;

  return (
    <>
      {schedulerDisabled ? (
        <div className="scheduler-warning" role="status">
          <AlertTriangle size={16} aria-hidden="true" />
          <span><strong>自动调度当前未启用。</strong> 任务不会按计划启动，仍可使用“立即执行”。</span>
        </div>
      ) : null}
      <div className="task-overview-layout">
      <section className="detail-section" aria-labelledby="task-summary-title">
        <h2 id="task-summary-title">任务信息</h2>
        <div className="detail-status-line">
          <StatusText tone={runtimeTone(task.runtimeState)}>{runtimeLabels[task.runtimeState]}</StatusText>
          <StatusText tone={healthTone(task.health)}>{healthLabels[task.health]}</StatusText>
        </div>
        <dl className="detail-list">
          <div><dt>来源</dt><dd>{sourceLabels[task.source.type]} · {task.source.id}</dd></div>
          <div><dt>采集方式</dt><dd>{modeLabels[task.collectionMode]}</dd></div>
          <div><dt>调度状态</dt><dd>{desiredLabels[task.desiredState]}</dd></div>
          <div><dt>调度方式</dt><dd>{task.scheduleLabel}</dd></div>
          <div><dt>最近成功</dt><dd>{formatDate(task.lastSuccessAt)}</dd></div>
          <div><dt>下次执行</dt><dd>{formatDate(task.nextRunAt)}</dd></div>
          <div>
            <dt>{task.kind === 'CREATOR_WATCH' ? '上次新建自动采集' : '上次新增评论'}</dt>
            <dd>{task.latestInsertedCount.toLocaleString('zh-CN')} {task.kind === 'CREATOR_WATCH' ? '个' : '条'}</dd>
          </div>
          {task.kind === 'CREATOR_WATCH' ? (
            <>
              <div>
                <dt>监控内容</dt>
                <dd>{task.watchedContentTypes.map((type) => sourceLabels[type]).join('、') || '—'}</dd>
              </div>
              <div><dt>已发现内容</dt><dd>{task.discoveredSummary?.total.toLocaleString('zh-CN') ?? '—'} 个</dd></div>
              <div><dt>自动采集异常</dt><dd>{task.discoveredSummary?.errors.toLocaleString('zh-CN') ?? '—'} 个</dd></div>
              <div><dt>24 小时新增评论</dt><dd>{task.discoveredSummary?.commentsInserted24h.toLocaleString('zh-CN') ?? '—'} 条</dd></div>
            </>
          ) : null}
        </dl>
      </section>

      <section className="detail-section detail-section--execution" aria-labelledby="current-execution-title">
        <h2 id="current-execution-title">最近一次执行</h2>
        {executionsQuery.isPending ? <LoadingState label="正在加载执行记录" /> : null}
        {executionsQuery.isError ? (
          <ErrorState message="执行记录加载失败" onRetry={() => void executionsQuery.refetch()} />
        ) : null}
        {currentExecution ? (
          <div className="current-execution current-execution--plain">
            <div>
              <StatusText tone={executionTone(currentExecution.status)}>
                {executionLabels[currentExecution.status]}
              </StatusText>
              <span>{taskPhaseLabel(task.kind, currentExecution.phase)}</span>
            </div>
            <p className="execution-context">
              {triggerLabel(currentExecution.trigger)} · {formatDate(currentExecution.startedAt)}
            </p>
            <dl className="execution-counts">
              <div>
                <dt>{metrics.pages}</dt>
                <dd>
                  {task.kind === 'CREATOR_WATCH'
                    ? creatorDiscoveryFailures(currentExecution)
                    : currentExecution.pagesFetched}
                </dd>
              </div>
              <div><dt>{metrics.discovered}</dt><dd>{currentExecution.commentsDiscovered}</dd></div>
              <div><dt>{metrics.inserted}</dt><dd>{currentExecution.commentsInserted}</dd></div>
              <div><dt>{metrics.duplicates}</dt><dd>{currentExecution.duplicatesSkipped}</dd></div>
            </dl>
            {currentExecution.status === 'RUNNING' ? (
              <div className="indeterminate-progress" role="progressbar" aria-label="执行进度未知，任务正在运行">
                <span />
              </div>
            ) : null}
            {currentExecution.errorSummary ? (
              <p className="execution-error">{currentExecution.errorSummary}</p>
            ) : null}
          </div>
        ) : null}
        {!currentExecution && !executionsQuery.isPending && !executionsQuery.isError ? (
          <EmptyState title="暂无执行记录" detail="立即执行或等待下一次调度。" />
        ) : null}
      </section>
      </div>
    </>
  );
}

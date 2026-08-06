import { Clock3 } from 'lucide-react';
import { formatDate } from '../../lib/format';
import { useTaskDetail } from './TaskDetailContext';

export function TaskConfigurationPage() {
  const { task } = useTaskDetail();

  return (
    <section className="detail-section detail-section--configuration" aria-labelledby="task-configuration-title">
      <h2 id="task-configuration-title">任务配置</h2>
      <dl className="detail-list">
        <div><dt>任务 ID</dt><dd>{task.id}</dd></div>
        <div><dt>来源 ID</dt><dd>{task.source.id}</dd></div>
        <div><dt>访问凭据</dt><dd>{task.credentialName}</dd></div>
        <div><dt>调度方式</dt><dd>{task.scheduleLabel}</dd></div>
        <div><dt>创建时间</dt><dd>{formatDate(task.createdAt)}</dd></div>
        <div><dt>更新时间</dt><dd>{formatDate(task.updatedAt)}</dd></div>
      </dl>
      {task.remark ? (
        <section className="configuration-note">
          <h3>备注</h3>
          <p className="plain-copy">{task.remark}</p>
        </section>
      ) : null}
      <div className="safety-note">
        <Clock3 size={16} aria-hidden="true" />
        暂停任务只会阻止后续调度，不会中断当前执行。
      </div>
    </section>
  );
}

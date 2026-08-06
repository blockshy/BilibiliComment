import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { vi } from 'vitest';
import type { TaskSummary } from '../api';
import { TaskTable } from './TaskTable';

const runningTask: TaskSummary = {
  id: '101',
  version: 1,
  name: '测试视频评论',
  kind: 'CONTENT_COMMENTS',
  source: { type: 'VIDEO', id: 'BV1xx411c7mD', title: '测试视频' },
  collectionMode: 'FOLLOW_ONLY',
  desiredState: 'ACTIVE',
  runtimeState: 'RUNNING',
  health: 'HEALTHY',
  scheduleLabel: '自适应频率',
  credentialName: '主账号',
  lastSuccessAt: '2026-07-14T03:00:00Z',
  nextRunAt: '2026-07-14T03:05:00Z',
  latestInsertedCount: 12,
  updatedAt: '2026-07-14T03:00:00Z',
  origin: 'MANUAL',
  relationMode: null,
  parentTask: null,
  discoveredSummary: null,
};

describe('TaskTable', () => {
  it('显示任务状态，并阻止运行中的任务重复执行', async () => {
    const user = userEvent.setup();
    const onPause = vi.fn();
    const onRun = vi.fn();

    render(
      <TaskTable
        tasks={[runningTask]}
        recentlyUpdatedTaskId={null}
        pendingTaskId={null}
        onSelect={vi.fn()}
        onPause={onPause}
        onResume={vi.fn()}
        onRun={onRun}
      />,
    );

    expect(screen.getByText('运行中')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '立即执行 测试视频评论' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: '暂停 测试视频评论' }));
    expect(onPause).toHaveBeenCalledWith('101');
    expect(onRun).not.toHaveBeenCalled();
  });

  it('等待重试仍视为活动执行并禁止重复运行', () => {
    const retryWaitingTask: TaskSummary = {
      ...runningTask,
      id: '102',
      name: '等待重试任务',
      runtimeState: 'RETRY_WAIT',
      health: 'DEGRADED',
    };

    render(
      <TaskTable
        tasks={[retryWaitingTask]}
        recentlyUpdatedTaskId={null}
        pendingTaskId={null}
        onSelect={vi.fn()}
        onPause={vi.fn()}
        onResume={vi.fn()}
        onRun={vi.fn()}
      />,
    );

    expect(screen.getByText('等待重试')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '立即执行 等待重试任务' })).toBeDisabled();
  });

  it('区分监控、独立采集和自动采集，并展示监控发现摘要', () => {
    const monitor: TaskSummary = {
      ...runningTask,
      id: '103',
      name: '示例 UP 主监控',
      kind: 'CREATOR_WATCH',
      source: { type: 'CREATOR', id: '349327152', title: '示例 UP 主' },
      discoveredSummary: {
        total: 8,
        neverStarted: 1,
        queuedOrRunning: 2,
        retryWaiting: 1,
        errors: 1,
        commentsInserted24h: 36,
        newestDiscoveredAt: '2026-07-14T03:00:00Z',
      },
    };
    const automated: TaskSummary = {
      ...runningTask,
      id: '104',
      name: '自动发现视频',
      origin: 'DISCOVERED',
      relationMode: 'MANAGED',
      parentTask: { taskId: '103', name: '示例 UP 主监控' },
    };

    render(
      <TaskTable
        tasks={[monitor, runningTask, automated]}
        recentlyUpdatedTaskId={null}
        pendingTaskId={null}
        onSelect={vi.fn()}
        onPause={vi.fn()}
        onResume={vi.fn()}
        onRun={vi.fn()}
      />,
    );

    expect(screen.getByText('UP 主监控')).toBeInTheDocument();
    expect(screen.getByText('独立采集')).toBeInTheDocument();
    expect(screen.getByText('自动采集')).toBeInTheDocument();
    expect(screen.getByText('已发现 8 个内容')).toBeInTheDocument();
    expect(screen.getByText('24 小时新增评论 36')).toBeInTheDocument();
    expect(screen.getByText('来自 示例 UP 主监控')).toBeInTheDocument();
  });
});

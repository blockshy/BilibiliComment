import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { act, render, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, vi } from 'vitest';
import { api, type LiveEvent } from '../api';
import { LiveEventsProvider } from './LiveEventsContext';

function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: Number.POSITIVE_INFINITY },
    },
  });
}

function DiscoveredQueries({
  loadFirst,
  loadSecond,
}: {
  loadFirst: () => Promise<string>;
  loadSecond: () => Promise<string>;
}) {
  useQuery({
    queryKey: ['discovered-tasks', 'parent-1', { health: '' }],
    queryFn: loadFirst,
  });
  useQuery({
    queryKey: ['discovered-tasks', 'parent-2', { health: 'ERROR' }],
    queryFn: loadSecond,
  });
  return null;
}

function ChildProgressQueries({
  loadTask,
  loadExecutions,
}: {
  loadTask: () => Promise<string>;
  loadExecutions: () => Promise<string>;
}) {
  useQuery({ queryKey: ['task', 'child-1'], queryFn: loadTask });
  useQuery({ queryKey: ['executions', 'child-1'], queryFn: loadExecutions });
  return null;
}

function renderLive(children: ReactNode): (event: LiveEvent) => void {
  let emit: ((event: LiveEvent) => void) | undefined;
  vi.spyOn(api, 'subscribeToEvents').mockImplementation((onEvent) => {
    emit = onEvent;
    return { close: vi.fn() };
  });

  render(
    <QueryClientProvider client={createQueryClient()}>
      <LiveEventsProvider>{children}</LiveEventsProvider>
    </QueryClientProvider>,
  );

  return (event) => emit?.(event);
}

function liveEvent(type: LiveEvent['type']): LiveEvent {
  return {
    eventId: `event-${type}`,
    type,
    occurredAt: '2026-07-14T03:00:00Z',
    sequence: 1,
    taskId: 'child-1',
    executionId: 'execution-1',
    data: {},
  };
}

describe('LiveEventsProvider', () => {
  afterEach(() => vi.restoreAllMocks());

  it.each(['task.updated', 'execution.updated', 'comments.appended'] as const)(
    '%s 会刷新所有活跃的发现内容查询',
    async (type) => {
      const loadFirst = vi.fn().mockResolvedValue('first');
      const loadSecond = vi.fn().mockResolvedValue('second');
      const emit = renderLive(
        <DiscoveredQueries loadFirst={loadFirst} loadSecond={loadSecond} />,
      );

      await waitFor(() => {
        expect(loadFirst).toHaveBeenCalledTimes(1);
        expect(loadSecond).toHaveBeenCalledTimes(1);
      });

      act(() => emit(liveEvent(type)));

      await waitFor(() => {
        expect(loadFirst).toHaveBeenCalledTimes(2);
        expect(loadSecond).toHaveBeenCalledTimes(2);
      });
    },
  );

  it('新评论事件会刷新子任务详情和执行进度', async () => {
    const loadTask = vi.fn().mockResolvedValue('task');
    const loadExecutions = vi.fn().mockResolvedValue('executions');
    const emit = renderLive(
      <ChildProgressQueries loadTask={loadTask} loadExecutions={loadExecutions} />,
    );

    await waitFor(() => {
      expect(loadTask).toHaveBeenCalledTimes(1);
      expect(loadExecutions).toHaveBeenCalledTimes(1);
    });

    act(() => emit(liveEvent('comments.appended')));

    await waitFor(() => {
      expect(loadTask).toHaveBeenCalledTimes(2);
      expect(loadExecutions).toHaveBeenCalledTimes(2);
    });
  });
});

import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, vi } from 'vitest';
import { api, type TaskExecution } from '../api';
import { useTaskActions } from './useTaskActions';

function ActionProbe({
  loadFirst,
  loadSecond,
}: {
  loadFirst: () => Promise<string>;
  loadSecond: () => Promise<string>;
}) {
  useQuery({
    queryKey: ['discovered-tasks', 'parent-1', { runtimeState: '' }],
    queryFn: loadFirst,
  });
  useQuery({
    queryKey: ['discovered-tasks', 'parent-2', { runtimeState: 'RUNNING' }],
    queryFn: loadSecond,
  });
  const actions = useTaskActions();

  return (
    <button type="button" onClick={() => actions.perform('child-1', 'run')}>
      执行子任务
    </button>
  );
}

describe('useTaskActions', () => {
  afterEach(() => vi.restoreAllMocks());

  it('子任务操作成功后刷新发现内容查询', async () => {
    const user = userEvent.setup();
    const loadFirst = vi.fn().mockResolvedValue('first');
    const loadSecond = vi.fn().mockResolvedValue('second');
    vi.spyOn(api, 'runTask').mockResolvedValue({} as TaskExecution);
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false, staleTime: Number.POSITIVE_INFINITY },
        mutations: { retry: false },
      },
    });

    render(
      <QueryClientProvider client={queryClient}>
        <ActionProbe loadFirst={loadFirst} loadSecond={loadSecond} />
      </QueryClientProvider>,
    );

    await waitFor(() => {
      expect(loadFirst).toHaveBeenCalledTimes(1);
      expect(loadSecond).toHaveBeenCalledTimes(1);
    });

    await user.click(screen.getByRole('button', { name: '执行子任务' }));

    await waitFor(() => {
      expect(api.runTask).toHaveBeenCalledWith('child-1');
      expect(loadFirst).toHaveBeenCalledTimes(2);
      expect(loadSecond).toHaveBeenCalledTimes(2);
    });
  });
});

import { useInfiniteQuery } from '@tanstack/react-query';
import { api } from '../../api';
import { useLiveEvents } from '../../live/LiveEventsContext';

export function useTaskExecutions(taskId: string) {
  const { connectionState } = useLiveEvents();
  return useInfiniteQuery({
    queryKey: ['executions', taskId],
    queryFn: ({ pageParam }) => api.getExecutions(taskId, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    refetchInterval: connectionState === 'OPEN' ? false : 10_000,
  });
}

import { useQueryClient } from '@tanstack/react-query';
import {
  createContext,
  type ReactNode,
  useContext,
  useEffect,
  useRef,
  useState,
} from 'react';
import { api, type ConnectionState, type LiveEvent } from '../api';

interface LiveContextValue {
  connectionState: ConnectionState;
  recentlyUpdatedTaskId: string | null;
  commentRevisions: Readonly<Record<string, number>>;
}

const LiveContext = createContext<LiveContextValue>({
  connectionState: 'CONNECTING',
  recentlyUpdatedTaskId: null,
  commentRevisions: {},
});

export function LiveEventsProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [connectionState, setConnectionState] = useState<ConnectionState>('CONNECTING');
  const [recentlyUpdatedTaskId, setRecentlyUpdatedTaskId] = useState<string | null>(null);
  const [commentRevisions, setCommentRevisions] = useState<Record<string, number>>({});
  const lastSequence = useRef(0);
  const highlightTimer = useRef<number | undefined>(undefined);

  useEffect(() => {
    const invalidateActiveDiscoveredTasks = (): void => {
      void queryClient.invalidateQueries({
        queryKey: ['discovered-tasks'],
        type: 'active',
      });
    };

    const handleEvent = (event: LiveEvent): void => {
      const previousSequence = lastSequence.current;
      if (event.sequence <= previousSequence) return;
      lastSequence.current = event.sequence;

      if (event.taskId) {
        setRecentlyUpdatedTaskId(event.taskId);
        if (highlightTimer.current !== undefined) window.clearTimeout(highlightTimer.current);
        highlightTimer.current = window.setTimeout(() => setRecentlyUpdatedTaskId(null), 900);
      }

      if (event.sequence > previousSequence + 1 && previousSequence !== 0) {
        void queryClient.invalidateQueries({ type: 'active' });
      }

      switch (event.type) {
        case 'task.updated':
          void queryClient.invalidateQueries({ queryKey: ['tasks'] });
          if (event.taskId) {
            void queryClient.invalidateQueries({ queryKey: ['task', event.taskId] });
          }
          invalidateActiveDiscoveredTasks();
          break;
        case 'execution.updated':
          if (event.taskId) {
            void queryClient.invalidateQueries({ queryKey: ['executions', event.taskId] });
            void queryClient.invalidateQueries({ queryKey: ['task', event.taskId] });
          }
          void queryClient.invalidateQueries({ queryKey: ['tasks'] });
          invalidateActiveDiscoveredTasks();
          break;
        case 'comments.appended':
          if (event.taskId) {
            setCommentRevisions((current) => ({
              ...current,
              [event.taskId as string]: (current[event.taskId as string] ?? 0) + 1,
            }));
            void queryClient.invalidateQueries({ queryKey: ['executions', event.taskId] });
            void queryClient.invalidateQueries({ queryKey: ['task', event.taskId] });
          }
          invalidateActiveDiscoveredTasks();
          break;
        case 'system.updated':
          void queryClient.invalidateQueries({ queryKey: ['system'] });
          break;
        case 'heartbeat':
          break;
      }
    };

    const subscription = api.subscribeToEvents(handleEvent, setConnectionState);
    return () => {
      subscription.close();
      if (highlightTimer.current !== undefined) window.clearTimeout(highlightTimer.current);
    };
  }, [queryClient]);

  useEffect(() => {
    if (connectionState === 'OPEN') {
      void queryClient.invalidateQueries({ type: 'active' });
    }
  }, [connectionState, queryClient]);

  return (
    <LiveContext.Provider value={{ connectionState, recentlyUpdatedTaskId, commentRevisions }}>
      {children}
    </LiveContext.Provider>
  );
}

export function useLiveEvents(): LiveContextValue {
  return useContext(LiveContext);
}

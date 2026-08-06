import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  api,
  ApiError,
  type TaskDetail,
  type TaskExecution,
} from '../api';

export type TaskAction = 'pause' | 'resume' | 'run';

interface TaskActionVariables {
  taskId: string;
  action: TaskAction;
}

export function useTaskActions() {
  const queryClient = useQueryClient();
  const mutation = useMutation<TaskDetail | TaskExecution, Error, TaskActionVariables>({
    mutationFn: ({ taskId, action }) => {
      if (action === 'pause') return api.pauseTask(taskId);
      if (action === 'resume') return api.resumeTask(taskId);
      return api.runTask(taskId);
    },
    onSuccess: (_result, variables) => {
      void queryClient.invalidateQueries({ queryKey: ['tasks'] });
      void queryClient.invalidateQueries({ queryKey: ['task', variables.taskId] });
      void queryClient.invalidateQueries({ queryKey: ['executions', variables.taskId] });
      void queryClient.invalidateQueries({ queryKey: ['discovered-tasks'] });
    },
  });

  const error = mutation.error instanceof ApiError
    ? mutation.error.problem.detail
    : mutation.error
      ? '任务操作失败，请稍后重试'
      : null;

  return {
    mutation,
    error,
    pendingTaskId: mutation.isPending ? mutation.variables.taskId : null,
    perform: (taskId: string, action: TaskAction): void => {
      mutation.mutate({ taskId, action });
    },
  };
}

import { useOutletContext } from 'react-router-dom';
import type { TaskDetail } from '../../api';

export interface TaskDetailContextValue {
  taskId: string;
  task: TaskDetail;
}

export function useTaskDetail(): TaskDetailContextValue {
  return useOutletContext<TaskDetailContextValue>();
}

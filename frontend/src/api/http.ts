import { ApiError, type ApiService, type LiveSubscription } from './service';
import type {
  AdminUser,
  ApiProblem,
  CommentRecord,
  CommentExportJob,
  CommentSearchPage,
  CommentSearchRequest,
  ConnectionState,
  CredentialProfile,
  CreateCommentExportInput,
  DashboardSummary,
  DiscoveredTaskFilters,
  DiscoveredTaskPage,
  LiveEvent,
  PageResponse,
  SourceResolution,
  SystemSummary,
  TaskCapability,
  TaskDetail,
  TaskExecution,
  TaskSummary,
} from './types';

const API_BASE = '/api/v1';

function readCookie(name: string): string | undefined {
  const prefix = `${encodeURIComponent(name)}=`;
  const cookie = document.cookie
    .split(';')
    .map((value) => value.trim())
    .find((value) => value.startsWith(prefix));
  return cookie ? decodeURIComponent(cookie.slice(prefix.length)) : undefined;
}

async function send(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  if (!headers.has('Accept')) headers.set('Accept', 'application/json');
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const csrfToken = readCookie('XSRF-TOKEN');
  if (csrfToken && init.method && init.method !== 'GET') {
    headers.set('X-XSRF-TOKEN', csrfToken);
  }

  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    credentials: 'include',
    headers,
  });

  if (!response.ok) {
    let problem: ApiProblem = {
      status: response.status,
      code: 'HTTP_ERROR',
      detail: response.statusText || '请求失败，请稍后重试',
    };
    try {
      problem = { ...problem, ...(await response.json() as Partial<ApiProblem>) };
    } catch {
      // Non-JSON proxy errors retain the safe fallback message.
    }
    if (response.status === 401) {
      window.dispatchEvent(new Event('bilibili-comment:unauthorized'));
    }
    throw new ApiError(problem);
  }

  return response;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await send(path, init);

  if (response.status === 204) {
    return undefined as T;
  }
  return await response.json() as T;
}

function queryString(filters: object): string {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && value !== '') params.set(key, String(value));
  });
  const query = params.toString();
  return query ? `?${query}` : '';
}

async function bootstrapCsrfCookie(): Promise<void> {
  if (readCookie('XSRF-TOKEN')) return;
  try {
    await request<AdminUser>('/auth/me');
  } catch (error: unknown) {
    if (!(error instanceof ApiError) || error.problem.status !== 401) throw error;
  }
}

function eventSubscription(
  onEvent: (event: LiveEvent) => void,
  onConnectionChange: (state: ConnectionState) => void,
): LiveSubscription {
  let closed = false;
  let source: EventSource | undefined;
  let reconnectTimer: number | undefined;
  let attempt = 0;
  let lastSequence: number | undefined;
  let recoveryPending = false;

  const scheduleReconnect = (): void => {
    if (closed) return;
    const wait = Math.min(1_000 * 2 ** Math.min(attempt, 4), 15_000);
    reconnectTimer = window.setTimeout(connect, wait);
  };

  const connect = (): void => {
    if (closed) return;
    onConnectionChange(attempt === 0 ? 'CONNECTING' : 'RECONNECTING');
    const cursor = lastSequence === undefined ? '' : `?afterSequence=${String(lastSequence)}`;
    source = new EventSource(`${API_BASE}/events${cursor}`, { withCredentials: true });

    source.onopen = () => {
      attempt = 0;
      onConnectionChange('OPEN');
    };

    const eventNames: LiveEvent['type'][] = [
      'task.updated',
      'execution.updated',
      'comments.appended',
      'system.updated',
      'heartbeat',
    ];
    eventNames.forEach((eventName) => {
      source?.addEventListener(eventName, (message) => {
        try {
          const event = JSON.parse((message as MessageEvent<string>).data) as LiveEvent;
          lastSequence = Math.max(lastSequence ?? 0, event.sequence);
          onEvent(event);
        } catch {
          // Ignore malformed events. The next REST refresh restores canonical state.
        }
      });
    });

    source.onerror = () => {
      source?.close();
      if (closed || recoveryPending) return;
      recoveryPending = true;
      attempt += 1;
      onConnectionChange('RECONNECTING');
      void request<AdminUser>('/auth/me')
        .then(() => {
          recoveryPending = false;
          scheduleReconnect();
        })
        .catch((error: unknown) => {
          recoveryPending = false;
          if (error instanceof ApiError && error.problem.status === 401) {
            closed = true;
            onConnectionChange('CLOSED');
            return;
          }
          scheduleReconnect();
        });
    };
  };

  connect();
  return {
    close: () => {
      closed = true;
      source?.close();
      if (reconnectTimer !== undefined) window.clearTimeout(reconnectTimer);
      onConnectionChange('CLOSED');
    },
  };
}

export const httpApi: ApiService = {
  getCurrentUser: () => request<AdminUser>('/auth/me'),
  login: async (username, password) => {
    await bootstrapCsrfCookie();
    return await request<AdminUser>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    });
  },
  logout: () => request<undefined>('/auth/logout', { method: 'POST' }),
  getTaskCapabilities: () => request<TaskCapability[]>('/task-capabilities'),
  resolveSource: (sourceType, input) =>
    request<SourceResolution>('/sources/resolve', {
      method: 'POST',
      body: JSON.stringify({ sourceType, input }),
    }),
  getTasks: (filters) => request<PageResponse<TaskSummary>>(`/tasks${queryString(filters)}`),
  getDashboardSummary: () => request<DashboardSummary>('/tasks/summary'),
  getTask: (taskId) => request<TaskDetail>(`/tasks/${encodeURIComponent(taskId)}`),
  getDiscoveredTasks: (taskId, filters: DiscoveredTaskFilters) =>
    request<DiscoveredTaskPage>(
      `/tasks/${encodeURIComponent(taskId)}/discovered-tasks${queryString(filters)}`,
    ),
  createTask: (input, idempotencyKey) =>
    request<TaskDetail>('/tasks', {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(input),
    }),
  pauseTask: (taskId) =>
    request<TaskDetail>(`/tasks/${encodeURIComponent(taskId)}/pause`, { method: 'POST' }),
  resumeTask: (taskId) =>
    request<TaskDetail>(`/tasks/${encodeURIComponent(taskId)}/resume`, { method: 'POST' }),
  runTask: (taskId) =>
    request<TaskExecution>(`/tasks/${encodeURIComponent(taskId)}/run`, { method: 'POST' }),
  getExecutions: (taskId, cursor) => {
    const suffix = cursor ? `?cursor=${encodeURIComponent(cursor)}` : '';
    return request<PageResponse<TaskExecution>>(
      `/tasks/${encodeURIComponent(taskId)}/executions${suffix}`,
    );
  },
  getComments: (taskId, cursor) => {
    const suffix = cursor ? `?cursor=${encodeURIComponent(cursor)}` : '';
    return request<PageResponse<CommentRecord>>(
      `/tasks/${encodeURIComponent(taskId)}/comments${suffix}`,
    );
  },
  searchComments: (taskId, input: CommentSearchRequest) =>
    request<CommentSearchPage>(`/tasks/${encodeURIComponent(taskId)}/comments/search`, {
      method: 'POST',
      body: JSON.stringify(input),
    }),
  createCommentExport: (taskId, input: CreateCommentExportInput, idempotencyKey) =>
    request<CommentExportJob>(`/tasks/${encodeURIComponent(taskId)}/comment-exports`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(input),
    }),
  getCommentExports: (taskId, limit = 20) =>
    request<CommentExportJob[]>(
      `/tasks/${encodeURIComponent(taskId)}/comment-exports?limit=${encodeURIComponent(String(limit))}`,
    ),
  getCommentExport: (exportId) =>
    request<CommentExportJob>(`/comment-exports/${encodeURIComponent(exportId)}`),
  commentExportDownloadUrl: (exportId) =>
    `${API_BASE}/comment-exports/${encodeURIComponent(exportId)}/download`,
  deleteCommentExport: (exportId) =>
    request<undefined>(`/comment-exports/${encodeURIComponent(exportId)}`, { method: 'DELETE' }),
  getSystemSummary: () => request<SystemSummary>('/system/summary'),
  getCredentials: () => request<CredentialProfile[]>('/credentials'),
  replaceCredentialSecret: (credentialId, secret) =>
    request<undefined>(`/credentials/${encodeURIComponent(credentialId)}/secret`, {
      method: 'PUT',
      body: JSON.stringify({ secret }),
    }),
  validateCredential: (credentialId) =>
    request<CredentialProfile>(`/credentials/${encodeURIComponent(credentialId)}/validate`, {
      method: 'POST',
    }),
  subscribeToEvents: eventSubscription,
};

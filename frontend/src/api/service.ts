import type {
  AdminUser,
  ApiProblem,
  CommentRecord,
  CommentExportJob,
  CommentSearchPage,
  CommentSearchRequest,
  ConnectionState,
  CreateTaskInput,
  CreateCommentExportInput,
  CredentialProfile,
  DashboardSummary,
  DiscoveredTaskFilters,
  DiscoveredTaskPage,
  LiveEvent,
  PageResponse,
  SourceResolution,
  SourceType,
  SystemSummary,
  TaskCapability,
  TaskDetail,
  TaskExecution,
  TaskFilters,
  TaskSummary,
} from './types';

export class ApiError extends Error {
  readonly problem: ApiProblem;

  constructor(problem: ApiProblem) {
    super(problem.detail);
    this.name = 'ApiError';
    this.problem = problem;
  }
}

export interface LiveSubscription {
  close: () => void;
}

export interface ApiService {
  getCurrentUser: () => Promise<AdminUser>;
  login: (username: string, password: string) => Promise<AdminUser>;
  logout: () => Promise<void>;
  getTaskCapabilities: () => Promise<TaskCapability[]>;
  resolveSource: (sourceType: SourceType, input: string) => Promise<SourceResolution>;
  getTasks: (filters: TaskFilters) => Promise<PageResponse<TaskSummary>>;
  getDashboardSummary: () => Promise<DashboardSummary>;
  getTask: (taskId: string) => Promise<TaskDetail>;
  getDiscoveredTasks: (
    taskId: string,
    filters: DiscoveredTaskFilters,
  ) => Promise<DiscoveredTaskPage>;
  createTask: (input: CreateTaskInput, idempotencyKey: string) => Promise<TaskDetail>;
  pauseTask: (taskId: string) => Promise<TaskDetail>;
  resumeTask: (taskId: string) => Promise<TaskDetail>;
  runTask: (taskId: string) => Promise<TaskExecution>;
  getExecutions: (taskId: string, cursor?: string) => Promise<PageResponse<TaskExecution>>;
  getComments: (taskId: string, cursor?: string) => Promise<PageResponse<CommentRecord>>;
  searchComments: (taskId: string, input: CommentSearchRequest) => Promise<CommentSearchPage>;
  createCommentExport: (
    taskId: string,
    input: CreateCommentExportInput,
    idempotencyKey: string,
  ) => Promise<CommentExportJob>;
  getCommentExports: (taskId: string, limit?: number) => Promise<CommentExportJob[]>;
  getCommentExport: (exportId: string) => Promise<CommentExportJob>;
  commentExportDownloadUrl: (exportId: string) => string;
  deleteCommentExport: (exportId: string) => Promise<void>;
  getSystemSummary: () => Promise<SystemSummary>;
  getCredentials: () => Promise<CredentialProfile[]>;
  replaceCredentialSecret: (credentialId: string, secret: string) => Promise<void>;
  validateCredential: (credentialId: string) => Promise<CredentialProfile>;
  subscribeToEvents: (
    onEvent: (event: LiveEvent) => void,
    onConnectionChange: (state: ConnectionState) => void,
  ) => LiveSubscription;
}

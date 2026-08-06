import type { components } from './generated';

type Schemas = components['schemas'];

// HTTP/SSE transport types come from openapi/openapi.yaml via `npm run api:generate`.
export type TaskKind = Schemas['TaskKind'];
export type SourceType = Schemas['SourceType'];
export type CollectionMode = Schemas['CollectionMode'];
export type DesiredState = Schemas['DesiredState'];
export type RuntimeState = Schemas['RuntimeState'];
export type HealthStatus = Schemas['HealthStatus'];
export type ExecutionStatus = Schemas['ExecutionStatus'];
export type ExecutionPhase = Schemas['ExecutionPhase'];

export type AdminUser = Schemas['AdminUser'];
export type TaskSource = Schemas['TaskSource'];
export type TaskOrigin = Schemas['TaskOrigin'];
export type TaskRelationMode = Schemas['TaskRelationMode'];
export type TaskScope = Schemas['TaskScope'];
export type ParentTaskReference = Schemas['ParentTaskReference'];
export type DiscoveredTaskSummary = Schemas['DiscoveredTaskSummary'];
export type TaskSummary = Schemas['TaskSummary'];
export type TaskDetail = Schemas['TaskDetail'];
export type TaskCapability = Schemas['TaskCapability'];
export type SourceResolution = Schemas['SourceResolution'];
export type CreateTaskInput = Schemas['CreateTaskRequest'];
export type TaskExecution = Schemas['TaskExecution'];
export type CommentRecord = Schemas['Comment'];
export type CommentReplyScope = Schemas['CommentReplyScope'];
export type CommentSort = Schemas['CommentSort'];
export type CommentExportFormat = Schemas['CommentExportFormat'];
export type CommentExportColumn = Schemas['CommentExportColumn'];
export type CommentExportStatus = Schemas['CommentExportStatus'];
export type CommentFilter = Schemas['CommentFilter'];
export type CommentSearchRequest = Schemas['CommentSearchRequest'];
export type CommentSearchPage = Schemas['CommentSearchPage'];
export type CreateCommentExportInput = Schemas['CreateCommentExportRequest'];
export type CommentExportJob = Schemas['CommentExportJob'];
export type CredentialProfile = Schemas['CredentialProfile'];
export type SystemSummary = Schemas['SystemSummary'];
export type DashboardSummary = Schemas['DashboardSummary'];
export type ApiProblem = Schemas['ApiProblem'];

type PageMetadata = Omit<Schemas['TaskPage'], 'items'>;

export type PageResponse<T> = PageMetadata & {
  items: T[];
};

export type LiveEvent<T = Schemas['LiveEvent']['data']> = Omit<Schemas['LiveEvent'], 'data'> & {
  data: T;
};

// UI-only state is deliberately kept out of the server contract.
export interface TaskFilters {
  query?: string;
  sourceType?: SourceType | '';
  collectionMode?: CollectionMode | '';
  runtimeState?: RuntimeState | '';
  health?: HealthStatus | '';
  scope?: TaskScope;
  cursor?: string;
  limit?: number;
}

export interface DiscoveredTaskFilters {
  query?: string;
  sourceType?: Exclude<SourceType, 'CREATOR'> | '';
  runtimeState?: RuntimeState | '';
  health?: HealthStatus | '';
  cursor?: string;
  limit?: number;
}

export type DiscoveredTaskItem = Schemas['DiscoveredTaskItem'];
export type DiscoveredTaskPage = Schemas['DiscoveredTaskPage'];

export type ConnectionState = 'CONNECTING' | 'OPEN' | 'RECONNECTING' | 'CLOSED';

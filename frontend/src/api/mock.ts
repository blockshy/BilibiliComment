import { ApiError, type ApiService, type LiveSubscription } from './service';
import type {
  CommentExportColumn,
  CommentExportJob,
  CommentFilter,
  CommentRecord,
  CommentSearchRequest,
  CreateCommentExportInput,
  CreateTaskInput,
  CredentialProfile,
  LiveEvent,
  SourceType,
  TaskDetail,
  TaskExecution,
  TaskFilters,
} from './types';

const now = Date.now();
const iso = (offsetMinutes = 0): string => new Date(now + offsetMinutes * 60_000).toISOString();
const sleep = async (milliseconds = 140): Promise<void> =>
  await new Promise((resolve) => window.setTimeout(resolve, milliseconds));
const mockSessionKey = 'bilibili-comment:mock-admin';
const mockStateKey = 'bilibili-comment:mock-state-v2';

function encodeTaskCursor(taskId: string): string {
  return window.btoa(`task-v1:${taskId}`)
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replace(/=+$/, '');
}

function decodeTaskCursor(cursor: string): bigint {
  try {
    const base64 = cursor.replaceAll('-', '+').replaceAll('_', '/');
    const decoded = window.atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, '='));
    const [version, taskId, ...extra] = decoded.split(':');
    if (version !== 'task-v1' || !taskId || extra.length > 0 || !/^[1-9]\d*$/.test(taskId)) {
      throw new Error('invalid task cursor');
    }
    if (encodeTaskCursor(taskId) !== cursor) throw new Error('non-canonical task cursor');
    return BigInt(taskId);
  } catch {
    throw new ApiError({ status: 422, code: 'CURSOR_INVALID', detail: '分页游标无效' });
  }
}

function readMockSession(): { username: string; displayName: string } | null {
  const stored = window.sessionStorage.getItem(mockSessionKey);
  if (!stored) return null;
  try {
    const user = JSON.parse(stored) as { username?: unknown; displayName?: unknown };
    if (typeof user.username === 'string' && typeof user.displayName === 'string') {
      return { username: user.username, displayName: user.displayName };
    }
  } catch {
    // Invalid development-only state is treated as an expired session.
  }
  window.sessionStorage.removeItem(mockSessionKey);
  return null;
}

const initialTasks: TaskDetail[] = [
  {
    id: '101',
    version: 3,
    name: '番剧更新评论',
    kind: 'CONTENT_COMMENTS',
    source: { type: 'VIDEO', id: 'BV1xx411c7mD', title: '七月新番先导片' },
    collectionMode: 'FOLLOW_ONLY',
    desiredState: 'ACTIVE',
    runtimeState: 'RUNNING',
    health: 'HEALTHY',
    scheduleLabel: '自适应频率',
    credentialName: '主账号',
    lastSuccessAt: iso(-8),
    nextRunAt: iso(2),
    latestInsertedCount: 18,
    updatedAt: iso(-1),
    origin: 'MANUAL',
    relationMode: null,
    parentTask: null,
    discoveredSummary: null,
    remark: '观察首发三天内的最新评论',
    createdAt: iso(-1_800),
    watchedContentTypes: [],
  },
  {
    id: '102',
    version: 2,
    name: '绘画动态评论',
    kind: 'CONTENT_COMMENTS',
    source: { type: 'DYNAMIC', id: '987654321012345678', title: '七月绘画过程记录' },
    collectionMode: 'FOLLOW_ONLY',
    desiredState: 'ACTIVE',
    runtimeState: 'IDLE',
    health: 'HEALTHY',
    scheduleLabel: '自适应频率',
    credentialName: '主账号',
    lastSuccessAt: iso(-23),
    nextRunAt: iso(7),
    latestInsertedCount: 4,
    updatedAt: iso(-23),
    origin: 'MANUAL',
    relationMode: null,
    parentTask: null,
    discoveredSummary: null,
    remark: null,
    createdAt: iso(-3_200),
    watchedContentTypes: [],
  },
  {
    id: '103',
    version: 5,
    name: 'UP 主内容监控',
    kind: 'CREATOR_WATCH',
    source: { type: 'CREATOR', id: '3493271523872334', title: '示例创作者' },
    collectionMode: 'FOLLOW_ONLY',
    desiredState: 'ACTIVE',
    runtimeState: 'RETRY_WAIT',
    health: 'DEGRADED',
    scheduleLabel: '每 5 分钟',
    credentialName: '备用账号',
    lastSuccessAt: iso(-78),
    nextRunAt: iso(4),
    latestInsertedCount: 0,
    updatedAt: iso(-4),
    origin: 'MANUAL',
    relationMode: null,
    parentTask: null,
    discoveredSummary: {
      total: 3,
      neverStarted: 0,
      queuedOrRunning: 1,
      retryWaiting: 0,
      errors: 1,
      commentsInserted24h: 29,
      newestDiscoveredAt: iso(-34),
    },
    remark: '发现视频和图文动态',
    createdAt: iso(-9_600),
    watchedContentTypes: ['VIDEO', 'DYNAMIC'],
  },
  {
    id: '104',
    version: 2,
    name: '七月新番制作访谈',
    kind: 'CONTENT_COMMENTS',
    source: { type: 'VIDEO', id: 'BV1AutoManaged', title: '七月新番制作访谈' },
    collectionMode: 'FOLLOW_ONLY',
    desiredState: 'ACTIVE',
    runtimeState: 'RUNNING',
    health: 'HEALTHY',
    scheduleLabel: '自适应频率',
    credentialName: '备用账号',
    lastSuccessAt: iso(-39),
    nextRunAt: iso(3),
    latestInsertedCount: 11,
    updatedAt: iso(-2),
    origin: 'DISCOVERED',
    relationMode: 'MANAGED',
    parentTask: { taskId: '103', name: 'UP 主内容监控' },
    discoveredSummary: null,
    remark: '由 UP 主内容监控自动建立',
    createdAt: iso(-1_200),
    watchedContentTypes: [],
  },
  {
    id: '105',
    version: 4,
    name: '绘画直播预告',
    kind: 'CONTENT_COMMENTS',
    source: { type: 'DYNAMIC', id: '987654321099900001', title: '绘画直播预告' },
    collectionMode: 'FOLLOW_ONLY',
    desiredState: 'ACTIVE',
    runtimeState: 'COMPLETED',
    health: 'HEALTHY',
    scheduleLabel: '自适应频率',
    credentialName: '主账号',
    lastSuccessAt: iso(-54),
    nextRunAt: iso(8),
    latestInsertedCount: 5,
    updatedAt: iso(-34),
    origin: 'MANUAL',
    relationMode: 'REFERENCED',
    parentTask: { taskId: '103', name: 'UP 主内容监控' },
    discoveredSummary: null,
    remark: '已有任务，被 UP 主监控关联',
    createdAt: iso(-2_600),
    watchedContentTypes: [],
  },
  {
    id: '106',
    version: 1,
    name: '幕后花絮动态',
    kind: 'CONTENT_COMMENTS',
    source: { type: 'DYNAMIC', id: '987654321099900002', title: '幕后花絮动态' },
    collectionMode: 'FOLLOW_ONLY',
    desiredState: 'ACTIVE',
    runtimeState: 'ERROR',
    health: 'ERROR',
    scheduleLabel: '自适应频率',
    credentialName: '备用账号',
    lastSuccessAt: null,
    nextRunAt: iso(12),
    latestInsertedCount: 0,
    updatedAt: iso(-31),
    origin: 'DISCOVERED',
    relationMode: 'MANAGED',
    parentTask: { taskId: '103', name: 'UP 主内容监控' },
    discoveredSummary: null,
    remark: null,
    createdAt: iso(-920),
    watchedContentTypes: [],
  },
];

const discoveredRelations = [
  {
    parentTaskId: '103',
    childTaskId: '104',
    firstDiscoveredAt: iso(-1_200),
    lastDiscoveredAt: iso(-34),
    firstDiscoveredExecutionId: '9001',
    lastDiscoveredExecutionId: '9002',
    commentsTotal: 1_840,
    commentsInserted24h: 24,
  },
  {
    parentTaskId: '103',
    childTaskId: '105',
    firstDiscoveredAt: iso(-900),
    lastDiscoveredAt: iso(-48),
    firstDiscoveredExecutionId: '9001',
    lastDiscoveredExecutionId: '9002',
    commentsTotal: 927,
    commentsInserted24h: 5,
  },
  {
    parentTaskId: '103',
    childTaskId: '106',
    firstDiscoveredAt: iso(-920),
    lastDiscoveredAt: iso(-31),
    firstDiscoveredExecutionId: '9001',
    lastDiscoveredExecutionId: '9002',
    commentsTotal: 0,
    commentsInserted24h: 0,
  },
];

const initialExecutions: TaskExecution[] = [
  {
    id: '9005',
    taskId: '101',
    trigger: 'SCHEDULED',
    status: 'RUNNING',
    phase: 'FETCHING_COMMENTS',
    pagesFetched: 1,
    commentsDiscovered: 46,
    commentsInserted: 18,
    duplicatesSkipped: 28,
    retryCount: 0,
    cursor: 'next:46',
    errorSummary: null,
    startedAt: iso(-1),
    finishedAt: null,
    lastHeartbeatAt: iso(0),
  },
  {
    id: '9004',
    taskId: '101',
    trigger: 'SCHEDULED',
    status: 'SUCCEEDED',
    phase: 'SCHEDULING_NEXT',
    pagesFetched: 1,
    commentsDiscovered: 39,
    commentsInserted: 7,
    duplicatesSkipped: 32,
    retryCount: 0,
    cursor: null,
    errorSummary: null,
    startedAt: iso(-12),
    finishedAt: iso(-11),
    lastHeartbeatAt: iso(-11),
  },
  {
    id: '9003',
    taskId: '102',
    trigger: 'MANUAL',
    status: 'SUCCEEDED',
    phase: 'SCHEDULING_NEXT',
    pagesFetched: 1,
    commentsDiscovered: 20,
    commentsInserted: 4,
    duplicatesSkipped: 16,
    retryCount: 0,
    cursor: null,
    errorSummary: null,
    startedAt: iso(-24),
    finishedAt: iso(-23),
    lastHeartbeatAt: iso(-23),
  },
  {
    id: '9002',
    taskId: '103',
    trigger: 'SCHEDULED',
    status: 'RETRY_WAIT',
    phase: 'WAITING_RATE_LIMIT',
    pagesFetched: 1,
    commentsDiscovered: 3,
    commentsInserted: 2,
    duplicatesSkipped: 1,
    retryCount: 2,
    cursor: null,
    errorSummary: '上游请求受限，将在退避后重试',
    startedAt: iso(-5),
    finishedAt: null,
    lastHeartbeatAt: iso(-4),
  },
];

interface PersistedMockState {
  version: 2;
  taskSequence: number;
  executionSequence: number;
  tasks: TaskDetail[];
  executions: TaskExecution[];
}

function readMockState(): PersistedMockState | null {
  const stored = window.sessionStorage.getItem(mockStateKey);
  if (!stored) return null;
  try {
    const state = JSON.parse(stored) as Partial<PersistedMockState>;
    if (
      state.version === 2
      && Number.isSafeInteger(state.taskSequence)
      && Number.isSafeInteger(state.executionSequence)
      && Array.isArray(state.tasks)
      && Array.isArray(state.executions)
    ) {
      return state as PersistedMockState;
    }
  } catch {
    // Invalid development-only state is replaced by the deterministic fixtures.
  }
  window.sessionStorage.removeItem(mockStateKey);
  return null;
}

const storedState = readMockState();
let taskSequence = storedState?.taskSequence ?? 8;
let executionSequence = storedState?.executionSequence ?? 6;
let eventSequence = 20;
const tasks = storedState?.tasks ?? initialTasks;
const executions = storedState?.executions ?? initialExecutions;

function persistMockState(): void {
  const state: PersistedMockState = {
    version: 2,
    taskSequence,
    executionSequence,
    tasks,
    executions,
  };
  window.sessionStorage.setItem(mockStateKey, JSON.stringify(state));
}

const comments: CommentRecord[] = Array.from({ length: 28 }, (_, index) => ({
  id: String(8000 - index),
  rpid: String(2_000_000_000 + index),
  parentRpid: index % 5 === 0 ? String(1_900_000_000 + index) : null,
  mid: String(500_000 + index),
  uname: ['春日记录', '白鲸汽水', '山风入夜', '纸片宇宙'][index % 4] ?? '访客',
  avatar: null,
  currentLevel: (index % 6) + 1,
  content: [
    '这个镜头的节奏处理得很好，期待完整版。',
    '字幕信息很清楚，已经收藏了。',
    '第二段的配色很舒服，感谢分享过程。',
    '刚好在找相关资料，这条动态很有帮助。',
  ][index % 4] ?? '',
  ctime: iso(-index * 3),
}));

const defaultExportColumns: CommentExportColumn[] = [
  'RPID',
  'PARENT_RPID',
  'MID',
  'UNAME',
  'CURRENT_LEVEL',
  'CONTENT',
  'CTIME',
];
const commentExports: CommentExportJob[] = [];
const exportRequests = new Map<string, CreateCommentExportInput>();
let commentExportSequence = 0;

const credentials: CredentialProfile[] = [
  { id: '1', name: '主账号', enabled: true, valid: true, lastValidatedAt: iso(-60) },
  { id: '2', name: '备用账号', enabled: true, valid: false, lastValidatedAt: iso(-720) },
];

const subscribers = new Set<(event: LiveEvent) => void>();

function emit(type: LiveEvent['type'], taskId?: string, executionId?: string): void {
  eventSequence += 1;
  const event: LiveEvent = {
    eventId: `mock-${String(eventSequence)}`,
    type,
    occurredAt: new Date().toISOString(),
    sequence: eventSequence,
    taskId: taskId ?? null,
    executionId: executionId ?? null,
    data: {},
  };
  subscribers.forEach((subscriber) => subscriber(event));
}

function findTask(taskId: string): TaskDetail {
  const task = tasks.find((item) => item.id === taskId);
  if (!task) {
    throw new ApiError({ status: 404, code: 'TASK_NOT_FOUND', detail: '任务不存在或已停用' });
  }
  return task;
}

function mutateTask(taskId: string, change: Partial<TaskDetail>): TaskDetail {
  const task = findTask(taskId);
  Object.assign(task, change, {
    version: task.version + 1,
    updatedAt: new Date().toISOString(),
  });
  persistMockState();
  emit('task.updated', taskId);
  return { ...task };
}

function normalizeSource(sourceType: SourceType, input: string): string {
  const trimmed = input.trim();
  const match = trimmed.match(sourceType === 'VIDEO' ? /(BV[\w]+)/i : /(\d{6,})/);
  return match?.[1] ?? trimmed;
}

function filteredComments(filter: CommentFilter, sort: CommentSearchRequest['sort']): CommentRecord[] {
  const keyword = filter.keyword?.trim().toLowerCase();
  const uname = filter.uname?.trim().toLowerCase();
  const from = filter.ctimeFrom ? new Date(filter.ctimeFrom).getTime() : null;
  const before = filter.ctimeBefore ? new Date(filter.ctimeBefore).getTime() : null;
  const result = comments.filter((comment) => {
    const commentTime = new Date(comment.ctime).getTime();
    const matchesKeyword = !keyword
      || (comment.content ?? '').toLowerCase().includes(keyword)
      || (comment.uname ?? '').toLowerCase().includes(keyword);
    return matchesKeyword
      && (!filter.mid || comment.mid === filter.mid)
      && (!uname || (comment.uname ?? '').toLowerCase().includes(uname))
      && (filter.levelMin == null || (comment.currentLevel != null && comment.currentLevel >= filter.levelMin))
      && (filter.levelMax == null || (comment.currentLevel != null && comment.currentLevel <= filter.levelMax))
      && (!filter.unknownLevelOnly || comment.currentLevel == null)
      && (from == null || commentTime >= from)
      && (before == null || commentTime < before)
      && (!filter.rpid || comment.rpid === filter.rpid)
      && (!filter.parentRpid || comment.parentRpid === filter.parentRpid)
      && (filter.replyScope !== 'ROOT' || comment.parentRpid == null)
      && (filter.replyScope !== 'REPLIES' || comment.parentRpid != null);
  });
  return [...result].sort((left, right) => {
    const direction = sort === 'CTIME_ASC' ? 1 : -1;
    return direction * (
      new Date(left.ctime).getTime() - new Date(right.ctime).getTime()
      || Number(BigInt(left.rpid) - BigInt(right.rpid))
    );
  });
}

function commentExportValue(comment: CommentRecord, column: CommentExportColumn): unknown {
  switch (column) {
    case 'RPID': return comment.rpid;
    case 'PARENT_RPID': return comment.parentRpid;
    case 'MID': return comment.mid;
    case 'UNAME': return comment.uname;
    case 'AVATAR': return comment.avatar;
    case 'CURRENT_LEVEL': return comment.currentLevel;
    case 'CONTENT': return comment.content;
    case 'CTIME': return comment.ctime;
  }
}

function safeCsvCell(value: unknown): string {
  let raw = '';
  if (typeof value === 'string') raw = value;
  else if (typeof value === 'number' || typeof value === 'bigint') raw = value.toString();
  else if (typeof value === 'boolean') raw = value ? 'true' : 'false';
  const guarded = /^[=+\-@]/.test(raw) ? `'${raw}` : raw;
  return `"${guarded.replaceAll('"', '""')}"`;
}

function mockExportBlob(job: CommentExportJob): Blob {
  const request = exportRequests.get(job.id);
  const columns = request?.columns ?? defaultExportColumns;
  const rows = filteredComments(
    request?.filter ?? { unknownLevelOnly: false },
    request?.sort ?? 'CTIME_DESC',
  );
  if (job.format === 'JSONL') {
    const text = rows.map((comment) => JSON.stringify(Object.fromEntries(
      columns.map((column) => [column, commentExportValue(comment, column)]),
    ))).join('\n');
    return new Blob([text], { type: 'application/x-ndjson;charset=utf-8' });
  }
  const text = [
    columns.map(safeCsvCell).join(','),
    ...rows.map((comment) => columns
      .map((column) => safeCsvCell(commentExportValue(comment, column)))
      .join(',')),
  ].join('\r\n');
  return new Blob([`\ufeff${text}`], { type: 'text/csv;charset=utf-8' });
}

export const mockApi: ApiService = {
  async getCurrentUser() {
    await sleep(60);
    const user = readMockSession();
    if (!user) {
      throw new ApiError({ status: 401, code: 'UNAUTHORIZED', detail: '登录会话已失效' });
    }
    return user;
  },
  async login(username, password) {
    await sleep();
    if (!username.trim() || !password) {
      throw new ApiError({ status: 422, code: 'INVALID_CREDENTIALS', detail: '请输入用户名和密码' });
    }
    const user = { username: username.trim(), displayName: '管理员' };
    window.sessionStorage.setItem(mockSessionKey, JSON.stringify(user));
    return user;
  },
  async logout() {
    await sleep(60);
    window.sessionStorage.removeItem(mockSessionKey);
  },
  async getTaskCapabilities() {
    await sleep(70);
    return [
      {
        kind: 'CONTENT_COMMENTS',
        sourceTypes: ['VIDEO', 'DYNAMIC'],
        collectionModes: ['FOLLOW_ONLY', 'BACKFILL_ONLY'],
      },
      {
        kind: 'CREATOR_WATCH',
        sourceTypes: ['CREATOR'],
        collectionModes: ['FOLLOW_ONLY'],
      },
    ];
  },
  async resolveSource(sourceType, input) {
    await sleep(280);
    if (!input.trim()) {
      throw new ApiError({ status: 422, code: 'INVALID_SOURCE', detail: '请输入来源标识' });
    }
    return {
      source: {
        type: sourceType,
        id: normalizeSource(sourceType, input),
        title: sourceType === 'CREATOR' ? '已解析的 UP 主' : '已解析的 Bilibili 内容',
      },
      accessible: true,
      message: null,
    };
  },
  async getTasks(filters: TaskFilters) {
    await sleep();
    const query = filters.query?.trim().toLowerCase();
    const items = tasks.filter((task) => {
      const matchesQuery = !query ||
        task.name.toLowerCase().includes(query) ||
        task.source.id.toLowerCase().includes(query);
      const matchesScope = filters.scope === 'ALL'
        || (filters.scope === 'MANAGED'
          ? task.origin === 'DISCOVERED' && task.relationMode === 'MANAGED'
          : task.origin === 'MANUAL');
      return matchesQuery && matchesScope &&
        (!filters.sourceType || task.source.type === filters.sourceType) &&
        (!filters.collectionMode || task.collectionMode === filters.collectionMode) &&
        (!filters.runtimeState || task.runtimeState === filters.runtimeState) &&
        (!filters.health || task.health === filters.health);
    }).sort((left, right) => {
      const leftId = BigInt(left.id);
      const rightId = BigInt(right.id);
      return leftId === rightId ? 0 : leftId > rightId ? -1 : 1;
    });
    const beforeTaskId = filters.cursor ? decodeTaskCursor(filters.cursor) : null;
    const eligibleItems = beforeTaskId === null
      ? items
      : items.filter((task) => BigInt(task.id) < beforeTaskId);
    const size = Math.max(1, Math.min(filters.limit ?? 50, 100));
    const rows = eligibleItems.slice(0, size + 1);
    const hasNext = rows.length > size;
    const page = hasNext ? rows.slice(0, size) : rows;
    const boundary = page.at(-1);
    return {
      items: page.map((item) => ({ ...item })),
      nextCursor: hasNext && boundary ? encodeTaskCursor(boundary.id) : null,
      total: items.length,
    };
  },
  async getDashboardSummary() {
    await sleep(90);
    return {
      activeTasks: tasks.filter((task) => task.desiredState === 'ACTIVE').length,
      primaryActiveTasks: tasks.filter(
        (task) => task.desiredState === 'ACTIVE' && task.origin !== 'DISCOVERED',
      ).length,
      managedActiveTasks: tasks.filter(
        (task) => task.desiredState === 'ACTIVE' && task.origin === 'DISCOVERED',
      ).length,
      runningTasks: tasks.filter((task) => task.runtimeState === 'RUNNING').length,
      retryWaitingTasks: tasks.filter((task) => task.runtimeState === 'RETRY_WAIT').length,
      commentsInserted24h: 126,
      recentFailures: tasks.filter((task) => task.health === 'ERROR').length,
    };
  },
  async getTask(taskId) {
    await sleep(90);
    return { ...findTask(taskId) };
  },
  async getDiscoveredTasks(taskId, filters) {
    await sleep(100);
    const parent = findTask(taskId);
    if (parent.kind !== 'CREATOR_WATCH') {
      throw new ApiError({
        status: 422,
        code: 'DISCOVERED_TASKS_NOT_APPLICABLE',
        detail: '仅 UP 主内容监控任务包含发现内容',
      });
    }
    const query = filters.query?.trim().toLowerCase();
    const matched = discoveredRelations.flatMap((relation) => {
      if (relation.parentTaskId !== taskId) return [];
      const task = findTask(relation.childTaskId);
      const matchesQuery = !query
        || task.name.toLowerCase().includes(query)
        || task.source.id.toLowerCase().includes(query)
        || task.source.title.toLowerCase().includes(query);
      if (!matchesQuery
        || (filters.sourceType && task.source.type !== filters.sourceType)
        || (filters.runtimeState && task.runtimeState !== filters.runtimeState)
        || (filters.health && task.health !== filters.health)) {
        return [];
      }
      return [{ ...relation, task: { ...task } }];
    });
    const start = filters.cursor ? Number(filters.cursor) : 0;
    const limit = filters.limit ?? 50;
    const items = matched.slice(start, start + limit);
    const next = start + items.length;
    return {
      items,
      nextCursor: next < matched.length ? String(next) : null,
      total: matched.length,
    };
  },
  async createTask(input: CreateTaskInput) {
    await sleep(360);
    const normalizedId = normalizeSource(input.source.type, input.source.input);
    const duplicate = tasks.find(
      (task) => task.source.type === input.source.type && task.source.id === normalizedId,
    );
    if (duplicate) {
      throw new ApiError({
        status: 409,
        code: 'TASK_ALREADY_EXISTS',
        detail: '该来源已有启用任务',
        existingTaskId: duplicate.id,
      });
    }
    taskSequence += 1;
    const created: TaskDetail = {
      id: String(100 + taskSequence),
      version: 1,
      name: input.name,
      kind: input.kind,
      source: { type: input.source.type, id: normalizedId, title: input.name },
      collectionMode: input.collectionMode,
      desiredState: input.desiredState,
      runtimeState: input.startNow ? 'QUEUED' : 'IDLE',
      health: 'UNKNOWN',
      scheduleLabel: input.schedule.strategy === 'ADAPTIVE'
        ? '自适应频率'
        : input.schedule.cronExpression ?? 'Cron',
      credentialName: credentials.find((item) => item.id === input.credentialProfileId)?.name ?? '凭据',
      lastSuccessAt: null,
      nextRunAt: input.desiredState === 'ACTIVE' ? iso(5) : null,
      latestInsertedCount: 0,
      updatedAt: new Date().toISOString(),
      origin: 'MANUAL',
      relationMode: null,
      parentTask: null,
      discoveredSummary: input.kind === 'CREATOR_WATCH' ? {
        total: 0,
        neverStarted: 0,
        queuedOrRunning: 0,
        retryWaiting: 0,
        errors: 0,
        commentsInserted24h: 0,
        newestDiscoveredAt: null,
      } : null,
      remark: input.remark || null,
      createdAt: new Date().toISOString(),
      watchedContentTypes: input.watchedContentTypes ?? [],
    };
    tasks.unshift(created);
    persistMockState();
    emit('task.updated', created.id);
    return { ...created };
  },
  async pauseTask(taskId) {
    await sleep();
    return mutateTask(taskId, { desiredState: 'PAUSED', nextRunAt: null });
  },
  async resumeTask(taskId) {
    await sleep();
    return mutateTask(taskId, { desiredState: 'ACTIVE', nextRunAt: iso(5) });
  },
  async runTask(taskId) {
    await sleep();
    const task = findTask(taskId);
    if (task.runtimeState === 'RUNNING' || task.runtimeState === 'QUEUED') {
      throw new ApiError({ status: 409, code: 'TASK_ALREADY_RUNNING', detail: '任务已有活动执行' });
    }
    executionSequence += 1;
    const execution: TaskExecution = {
      id: String(9000 + executionSequence),
      taskId,
      trigger: 'MANUAL',
      status: 'QUEUED',
      phase: 'RESOLVING_SOURCE',
      pagesFetched: 0,
      commentsDiscovered: 0,
      commentsInserted: 0,
      duplicatesSkipped: 0,
      retryCount: 0,
      cursor: null,
      errorSummary: null,
      startedAt: new Date().toISOString(),
      finishedAt: null,
      lastHeartbeatAt: new Date().toISOString(),
    };
    executions.unshift(execution);
    mutateTask(taskId, { runtimeState: 'QUEUED' });
    emit('execution.updated', taskId, execution.id);
    return { ...execution };
  },
  async getExecutions(taskId, cursor) {
    await sleep(90);
    const items = executions.filter((execution) => execution.taskId === taskId);
    const cursorIndex = cursor ? items.findIndex((execution) => execution.id === cursor) : -1;
    const start = cursorIndex < 0 ? 0 : cursorIndex + 1;
    const page = items.slice(start, start + 50);
    return {
      items: page.map((item) => ({ ...item })),
      nextCursor: start + page.length < items.length ? page.at(-1)?.id ?? null : null,
      total: items.length,
    };
  },
  async getComments(taskId, cursor) {
    await sleep(100);
    findTask(taskId);
    const start = cursor ? Number(cursor) : 0;
    const items = comments.slice(start, start + 12);
    const next = start + items.length;
    return {
      items,
      nextCursor: next < comments.length ? String(next) : null,
      total: comments.length,
    };
  },
  async searchComments(taskId, input) {
    await sleep(100);
    const task = findTask(taskId);
    if (task.kind !== 'CONTENT_COMMENTS') {
      throw new ApiError({ status: 422, code: 'COMMENTS_NOT_APPLICABLE', detail: '该任务不采集评论' });
    }
    const filtered = filteredComments(input.filter, input.sort);
    const start = input.cursor ? Number(input.cursor) : 0;
    const size = input.limit;
    const items = filtered.slice(start, start + size);
    const next = start + items.length;
    return {
      items,
      nextCursor: next < filtered.length ? String(next) : null,
      total: input.includeTotal ? filtered.length : null,
    };
  },
  async createCommentExport(taskId, input) {
    await sleep(120);
    const task = findTask(taskId);
    if (task.kind !== 'CONTENT_COMMENTS') {
      throw new ApiError({ status: 422, code: 'COMMENTS_NOT_APPLICABLE', detail: '该任务不采集评论' });
    }
    commentExportSequence += 1;
    const id = `export-${String(commentExportSequence)}`;
    const createdAt = new Date().toISOString();
    const job: CommentExportJob = {
      id,
      taskId,
      status: 'QUEUED',
      format: input.format,
      columns: input.columns ?? defaultExportColumns,
      rowsWritten: 0,
      bytesWritten: 0,
      downloadReady: false,
      errorCode: null,
      errorSummary: null,
      createdAt,
      startedAt: null,
      finishedAt: null,
      expiresAt: iso(24 * 60),
    };
    commentExports.unshift(job);
    exportRequests.set(id, input);
    window.setTimeout(() => {
      if (job.status === 'CANCELLED') return;
      job.status = 'RUNNING';
      job.startedAt = new Date().toISOString();
      window.setTimeout(() => {
        if (job.status === 'CANCELLED') return;
        const blob = mockExportBlob(job);
        job.status = 'SUCCEEDED';
        job.rowsWritten = filteredComments(input.filter, input.sort).length;
        job.bytesWritten = blob.size;
        job.downloadReady = true;
        job.finishedAt = new Date().toISOString();
      }, 250);
    }, 180);
    return { ...job, columns: [...job.columns] };
  },
  async getCommentExports(taskId, limit = 20) {
    await sleep(70);
    findTask(taskId);
    return commentExports
      .filter((job) => job.taskId === taskId)
      .slice(0, limit)
      .map((job) => ({ ...job, columns: [...job.columns] }));
  },
  async getCommentExport(exportId) {
    await sleep(60);
    const job = commentExports.find((item) => item.id === exportId);
    if (!job) throw new ApiError({ status: 404, code: 'COMMENT_EXPORT_NOT_FOUND', detail: '导出任务不存在' });
    return { ...job, columns: [...job.columns] };
  },
  commentExportDownloadUrl: (exportId) =>
    `/api/v1/comment-exports/${encodeURIComponent(exportId)}/download`,
  async deleteCommentExport(exportId) {
    await sleep(70);
    const index = commentExports.findIndex((item) => item.id === exportId);
    if (index < 0) throw new ApiError({ status: 404, code: 'COMMENT_EXPORT_NOT_FOUND', detail: '导出任务不存在' });
    const job = commentExports[index];
    if (job && (job.status === 'QUEUED' || job.status === 'RUNNING')) {
      job.status = 'CANCELLED';
      job.finishedAt = new Date().toISOString();
      return;
    }
    commentExports.splice(index, 1);
    exportRequests.delete(exportId);
  },
  async getSystemSummary() {
    await sleep(100);
    return {
      api: 'UP',
      database: 'UP',
      bilibili: 'DEGRADED',
      schedulerEnabled: true,
      schedulerActive: 3,
      workerActive: 2,
      workerPoolSize: 4,
      workerQueueSize: 1,
      sseConnections: subscribers.size,
      recentFailureCount: 1,
      rateLimitedCount: 2,
      measuredAt: new Date().toISOString(),
    };
  },
  async getCredentials() {
    await sleep(90);
    return credentials.map((item) => ({ ...item }));
  },
  async replaceCredentialSecret(credentialId, secret) {
    await sleep(220);
    if (secret.trim().length < 8) {
      throw new ApiError({ status: 422, code: 'INVALID_SECRET', detail: 'Cookie 内容过短' });
    }
    const credential = credentials.find((item) => item.id === credentialId);
    if (!credential) {
      throw new ApiError({ status: 404, code: 'CREDENTIAL_NOT_FOUND', detail: '凭据不存在' });
    }
    credential.valid = false;
    credential.lastValidatedAt = null;
  },
  async validateCredential(credentialId) {
    await sleep(320);
    const credential = credentials.find((item) => item.id === credentialId);
    if (!credential) {
      throw new ApiError({ status: 404, code: 'CREDENTIAL_NOT_FOUND', detail: '凭据不存在' });
    }
    credential.valid = true;
    credential.lastValidatedAt = new Date().toISOString();
    return { ...credential };
  },
  subscribeToEvents(onEvent, onConnectionChange): LiveSubscription {
    let closed = false;
    onConnectionChange('CONNECTING');
    const openTimer = window.setTimeout(() => onConnectionChange('OPEN'), 120);
    subscribers.add(onEvent);
    const heartbeatTimer = window.setInterval(() => emit('heartbeat'), 20_000);
    return {
      close: () => {
        if (closed) return;
        closed = true;
        subscribers.delete(onEvent);
        window.clearTimeout(openTimer);
        window.clearInterval(heartbeatTimer);
        onConnectionChange('CLOSED');
      },
    };
  },
};

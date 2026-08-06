import * as Dialog from '@radix-ui/react-dialog';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Download, Filter, Search, X } from 'lucide-react';
import {
  useEffect,
  useRef,
  useState,
} from 'react';
import { Navigate } from 'react-router-dom';
import {
  api,
  ApiError,
  type CommentExportFormat,
  type CommentExportJob,
  type CommentFilter,
  type CommentReplyScope,
  type CommentSort,
} from '../../api';
import { EmptyState, ErrorState, LoadingState } from '../../components/PageState';
import { StatusText } from '../../components/StatusText';
import { formatDateTime } from '../../lib/format';
import { useLiveEvents } from '../../live/LiveEventsContext';
import { beijingLocalDateTimeToIso, isoToBeijingLocalDateTime } from './commentTime';
import { useTaskDetail } from './TaskDetailContext';

interface AdvancedFilterDraft {
  mid: string;
  uname: string;
  rpid: string;
  parentRpid: string;
  replyScope: CommentReplyScope;
  levelMin: string;
  levelMax: string;
  unknownLevelOnly: boolean;
  ctimeFrom: string;
  ctimeBefore: string;
}

interface CommentViewState {
  filter: CommentFilter;
  sort: CommentSort;
}

const commentViewSessionPrefix = 'bilibili-comment:comment-view:';

const exportStatusLabels: Record<CommentExportJob['status'], string> = {
  QUEUED: '排队中',
  RUNNING: '生成中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  EXPIRED: '已过期',
};

function isReplyScope(value: unknown): value is CommentReplyScope {
  return value === 'ALL' || value === 'ROOT' || value === 'REPLIES';
}

function isSort(value: unknown): value is CommentSort {
  return value === 'CTIME_DESC' || value === 'CTIME_ASC';
}

function numericLevel(value: string | null): number | null {
  if (value == null || value === '') return null;
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed >= 0 && parsed <= 6 ? parsed : null;
}

function emptyFilter(): CommentFilter {
  return {
    replyScope: 'ALL',
    unknownLevelOnly: false,
  };
}

function draftFromFilter(filter: CommentFilter): AdvancedFilterDraft {
  return {
    mid: filter.mid ?? '',
    uname: filter.uname ?? '',
    rpid: filter.rpid ?? '',
    parentRpid: filter.parentRpid ?? '',
    replyScope: filter.replyScope ?? 'ALL',
    levelMin: filter.levelMin == null ? '' : String(filter.levelMin),
    levelMax: filter.levelMax == null ? '' : String(filter.levelMax),
    unknownLevelOnly: filter.unknownLevelOnly,
    ctimeFrom: isoToBeijingLocalDateTime(filter.ctimeFrom),
    ctimeBefore: isoToBeijingLocalDateTime(filter.ctimeBefore),
  };
}

function optionalString(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value : null;
}

function storedLevel(value: unknown): number | null {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 6
    ? value
    : null;
}

function readCommentViewState(taskId: string): CommentViewState {
  const fallback = { filter: emptyFilter(), sort: 'CTIME_DESC' as const };
  try {
    const stored = window.sessionStorage.getItem(`${commentViewSessionPrefix}${taskId}`);
    if (!stored) return fallback;
    const candidate = JSON.parse(stored) as { filter?: unknown; sort?: unknown };
    const source = candidate.filter && typeof candidate.filter === 'object'
      ? candidate.filter as Record<string, unknown>
      : {};
    return {
      sort: isSort(candidate.sort) ? candidate.sort : fallback.sort,
      filter: {
        keyword: optionalString(source.keyword),
        mid: optionalString(source.mid),
        uname: optionalString(source.uname),
        levelMin: storedLevel(source.levelMin),
        levelMax: storedLevel(source.levelMax),
        unknownLevelOnly: source.unknownLevelOnly === true,
        ctimeFrom: optionalString(source.ctimeFrom),
        ctimeBefore: optionalString(source.ctimeBefore),
        rpid: optionalString(source.rpid),
        parentRpid: optionalString(source.parentRpid),
        replyScope: isReplyScope(source.replyScope) ? source.replyScope : 'ALL',
      },
    };
  } catch {
    window.sessionStorage.removeItem(`${commentViewSessionPrefix}${taskId}`);
    return fallback;
  }
}

function activeFilterCount(filter: CommentFilter): number {
  return [
    filter.keyword,
    filter.mid,
    filter.uname,
    filter.rpid,
    filter.parentRpid,
    filter.replyScope && filter.replyScope !== 'ALL' ? filter.replyScope : null,
    filter.levelMin,
    filter.levelMax,
    filter.unknownLevelOnly ? true : null,
    filter.ctimeFrom,
    filter.ctimeBefore,
  ].filter((value) => value != null && value !== '').length;
}

function apiErrorMessage(error: unknown, fallback: string): string | null {
  if (error instanceof ApiError) return error.problem.detail;
  return error ? fallback : null;
}

function exportTone(status: CommentExportJob['status']): 'neutral' | 'positive' | 'warning' | 'negative' | 'active' {
  if (status === 'QUEUED' || status === 'RUNNING') return 'active';
  if (status === 'SUCCEEDED') return 'positive';
  if (status === 'FAILED') return 'negative';
  if (status === 'EXPIRED') return 'warning';
  return 'neutral';
}

function formatBytes(value: number): string {
  if (value < 1024) return `${String(value)} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}

function newIdempotencyKey(): string {
  return globalThis.crypto.randomUUID();
}

interface CommentExportDialogProps {
  taskId: string;
  filter: CommentFilter;
  sort: CommentSort;
  resultCount: number | null;
}

function CommentExportDialog({ taskId, filter, sort, resultCount }: CommentExportDialogProps) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [format, setFormat] = useState<CommentExportFormat>('CSV');
  const idempotencyKey = useRef(newIdempotencyKey());
  const jobsQuery = useQuery({
    queryKey: ['comment-exports', taskId],
    queryFn: () => api.getCommentExports(taskId, 20),
    enabled: open,
    refetchInterval: (query) => query.state.data?.some(
      (job) => job.status === 'QUEUED' || job.status === 'RUNNING',
    ) ? 1_000 : false,
  });
  const createExport = useMutation({
    mutationFn: () => api.createCommentExport(taskId, { filter, sort, format }, idempotencyKey.current),
    onSuccess: () => {
      idempotencyKey.current = newIdempotencyKey();
      void queryClient.invalidateQueries({ queryKey: ['comment-exports', taskId] });
    },
  });
  const cancelExport = useMutation({
    mutationFn: api.deleteCommentExport,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['comment-exports', taskId] }),
  });
  const createError = apiErrorMessage(createExport.error, '导出任务创建失败，请稍后重试');
  const cancelError = apiErrorMessage(cancelExport.error, '导出任务取消失败，请稍后重试');

  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Trigger asChild>
        <button type="button" className="button button--secondary">
          <Download size={16} aria-hidden="true" />
          导出
        </button>
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="modal-overlay" />
        <Dialog.Content className="modal-content export-dialog" aria-describedby="comment-export-description">
          <header className="modal-header">
            <div>
              <Dialog.Title>导出评论</Dialog.Title>
              <Dialog.Description id="comment-export-description">
                导出使用当前搜索与筛选条件，文件在后台生成。
              </Dialog.Description>
            </div>
            <Dialog.Close asChild>
              <button type="button" className="icon-button" aria-label="关闭导出">
                <X size={18} aria-hidden="true" />
              </button>
            </Dialog.Close>
          </header>

          <div className="export-create-row">
            <fieldset className="inline-options">
              <legend className="field-label">文件格式</legend>
              <label>
                <input
                  type="radio"
                  name="comment-export-format"
                  value="CSV"
                  checked={format === 'CSV'}
                  onChange={() => setFormat('CSV')}
                />
                CSV
              </label>
              <label>
                <input
                  type="radio"
                  name="comment-export-format"
                  value="JSONL"
                  checked={format === 'JSONL'}
                  onChange={() => setFormat('JSONL')}
                />
                JSONL
              </label>
            </fieldset>
            <div className="export-create-action">
              <span>{resultCount == null ? '按当前条件导出' : `预计 ${resultCount.toLocaleString('zh-CN')} 条`}</span>
              <button
                type="button"
                className="button button--primary"
                disabled={createExport.isPending}
                onClick={() => createExport.mutate()}
              >
                {createExport.isPending ? '正在提交…' : '创建导出任务'}
              </button>
            </div>
          </div>
          {createError ? <div className="inline-error" role="alert">{createError}</div> : null}
          {cancelError ? <div className="inline-error" role="alert">{cancelError}</div> : null}

          <section className="export-jobs" aria-labelledby="export-jobs-title">
            <h3 id="export-jobs-title">最近导出</h3>
            {jobsQuery.isPending ? <LoadingState label="正在加载导出记录" /> : null}
            {jobsQuery.isError ? (
              <ErrorState message="导出记录加载失败" onRetry={() => void jobsQuery.refetch()} />
            ) : null}
            {jobsQuery.data?.length === 0 ? (
              <EmptyState title="暂无导出记录" detail="创建后可在这里查看进度并下载。" />
            ) : null}
            {jobsQuery.data?.length ? (
              <ol className="export-job-list">
                {jobsQuery.data.map((job) => (
                  <li key={job.id}>
                    <div className="export-job-main">
                      <StatusText tone={exportTone(job.status)}>{exportStatusLabels[job.status]}</StatusText>
                      <span>{job.format}</span>
                      <time>{formatDateTime(job.createdAt)}</time>
                    </div>
                    <div className="export-job-meta">
                      <span>{job.rowsWritten.toLocaleString('zh-CN')} 条</span>
                      <span>{formatBytes(job.bytesWritten)}</span>
                      {job.errorSummary ? <span className="execution-error">{job.errorSummary}</span> : null}
                    </div>
                    <div className="export-job-actions">
                      {job.downloadReady ? (
                        <a
                          className="button button--secondary button--small"
                          href={api.commentExportDownloadUrl(job.id)}
                          download={`bilibili-comments-${job.id}.${job.format === 'JSONL' ? 'jsonl' : 'csv'}`}
                        >
                          <Download size={14} aria-hidden="true" />
                          下载
                        </a>
                      ) : null}
                      {job.status === 'QUEUED' || job.status === 'RUNNING' ? (
                        <button
                          type="button"
                          className="icon-button"
                          aria-label="取消导出"
                          disabled={cancelExport.isPending}
                          onClick={() => cancelExport.mutate(job.id)}
                        >
                          <X size={15} aria-hidden="true" />
                        </button>
                      ) : null}
                    </div>
                  </li>
                ))}
              </ol>
            ) : null}
          </section>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

export function TaskCommentsPage() {
  const { taskId, task } = useTaskDetail();
  const queryClient = useQueryClient();
  const { connectionState, commentRevisions } = useLiveEvents();
  const commentRevision = commentRevisions[taskId] ?? 0;
  const [seenCommentRevision, setSeenCommentRevision] = useState(commentRevision);
  const [viewState, setViewState] = useState<CommentViewState>(() => readCommentViewState(taskId));
  const [keyword, setKeyword] = useState(viewState.filter.keyword ?? '');
  const [filterOpen, setFilterOpen] = useState(false);
  const [filterDraft, setFilterDraft] = useState(() => draftFromFilter(viewState.filter));
  const [filterError, setFilterError] = useState<string | null>(null);
  const { filter, sort } = viewState;
  const filterCount = activeFilterCount(filter);
  const commentsQueryKey = ['comments', taskId, filter, sort] as const;

  useEffect(() => {
    window.sessionStorage.setItem(
      `${commentViewSessionPrefix}${taskId}`,
      JSON.stringify(viewState),
    );
  }, [taskId, viewState]);

  useEffect(() => {
    const appliedKeyword = filter.keyword ?? '';
    if (keyword.trim() === appliedKeyword) return undefined;
    const timer = window.setTimeout(() => {
      setViewState((current) => ({
        ...current,
        filter: { ...current.filter, keyword: keyword.trim() || null },
      }));
    }, 300);
    return () => window.clearTimeout(timer);
  }, [filter.keyword, keyword]);

  const commentsQuery = useInfiniteQuery({
    queryKey: commentsQueryKey,
    queryFn: ({ pageParam }) => api.searchComments(taskId, {
      filter,
      sort,
      cursor: pageParam ?? null,
      limit: 50,
      includeTotal: pageParam == null,
    }),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: task.kind === 'CONTENT_COMMENTS',
    refetchInterval: (query) => connectionState === 'OPEN'
      || (query.state.data?.pages.length ?? 0) > 1
      ? false
      : 15_000,
  });
  const comments = commentsQuery.data?.pages.flatMap((page) => page.items) ?? [];
  const total = commentsQuery.data?.pages[0]?.total ?? null;
  const commentsError = apiErrorMessage(commentsQuery.error, '评论加载失败，请稍后重试');
  const hasNewComments = commentRevision > seenCommentRevision;

  if (task.kind !== 'CONTENT_COMMENTS') {
    return <Navigate to={`/tasks/${encodeURIComponent(taskId)}`} replace />;
  }

  const openFilters = (): void => {
    setFilterDraft(draftFromFilter(filter));
    setFilterError(null);
    setFilterOpen(true);
  };

  const applyFilters = (): void => {
    const min = numericLevel(filterDraft.levelMin);
    const max = numericLevel(filterDraft.levelMax);
    if (!filterDraft.unknownLevelOnly && min != null && max != null && min > max) {
      setFilterError('最低等级不能高于最高等级');
      return;
    }
    if (filterDraft.mid && !/^[1-9]\d*$/.test(filterDraft.mid.trim())) {
      setFilterError('用户 UID 必须是正整数');
      return;
    }
    if (filterDraft.rpid && !/^[1-9]\d*$/.test(filterDraft.rpid.trim())) {
      setFilterError('评论 RPID 必须是正整数');
      return;
    }
    if (filterDraft.parentRpid && !/^[1-9]\d*$/.test(filterDraft.parentRpid.trim())) {
      setFilterError('父评论 RPID 必须是正整数');
      return;
    }
    if (filterDraft.parentRpid && filterDraft.replyScope !== 'REPLIES') {
      setFilterError('筛选父评论 RPID 时，评论类型必须选择“回复”');
      return;
    }
    const ctimeFrom = beijingLocalDateTimeToIso(filterDraft.ctimeFrom);
    const ctimeBefore = beijingLocalDateTimeToIso(filterDraft.ctimeBefore);
    if (filterDraft.ctimeFrom && !ctimeFrom) {
      setFilterError('开始时间格式无效');
      return;
    }
    if (filterDraft.ctimeBefore && !ctimeBefore) {
      setFilterError('结束时间格式无效');
      return;
    }
    if (ctimeFrom && ctimeBefore && ctimeFrom >= ctimeBefore) {
      setFilterError('结束时间必须晚于开始时间');
      return;
    }
    setViewState((current) => ({
      ...current,
      filter: {
        keyword: current.filter.keyword ?? null,
        mid: filterDraft.mid.trim() || null,
        uname: filterDraft.uname.trim() || null,
        rpid: filterDraft.rpid.trim() || null,
        parentRpid: filterDraft.parentRpid.trim() || null,
        replyScope: filterDraft.replyScope,
        levelMin: filterDraft.unknownLevelOnly ? null : min,
        levelMax: filterDraft.unknownLevelOnly ? null : max,
        unknownLevelOnly: filterDraft.unknownLevelOnly,
        ctimeFrom,
        ctimeBefore,
      },
    }));
    setFilterOpen(false);
  };

  const clearAllFilters = (): void => {
    setKeyword('');
    setViewState((current) => ({ ...current, filter: emptyFilter() }));
  };

  const changeSort = (nextSort: CommentSort): void => {
    setViewState((current) => ({ ...current, sort: nextSort }));
  };

  return (
    <section className="comments-workspace" aria-labelledby="comments-title">
      <header className="section-heading comments-section-heading">
        <div>
          <h2 id="comments-title">评论</h2>
          <p>搜索、筛选并导出当前任务采集的评论。</p>
        </div>
        <span>{total == null ? `已加载 ${comments.length.toLocaleString('zh-CN')} 条` : `共 ${total.toLocaleString('zh-CN')} 条`}</span>
      </header>

      <div className="comment-toolbar">
        <div className="comment-search-group">
          <label className="search-field comment-search-field">
            <Search size={16} aria-hidden="true" />
            <span className="sr-only">搜索评论内容或用户名</span>
            <input
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              placeholder="搜索评论内容或用户名"
              aria-describedby="comment-search-help"
            />
          </label>
          <span id="comment-search-help">搜索条件仅在当前浏览器会话中保存。</span>
        </div>
        <div className="comment-toolbar-actions">
          <button type="button" className="button button--secondary" onClick={openFilters}>
            <Filter size={16} aria-hidden="true" />
            {filterCount > 0 ? `筛选（${String(filterCount)}）` : '筛选'}
          </button>
          <label className="comment-sort">
            <span className="sr-only">评论排序</span>
            <select value={sort} onChange={(event) => changeSort(event.target.value as CommentSort)}>
              <option value="CTIME_DESC">最新发布</option>
              <option value="CTIME_ASC">最早发布</option>
            </select>
          </label>
          <CommentExportDialog taskId={taskId} filter={filter} sort={sort} resultCount={total} />
          {filterCount > 0 ? (
            <button type="button" className="text-button" onClick={clearAllFilters}>清除筛选</button>
          ) : null}
        </div>
      </div>

      {hasNewComments ? (
        <div className="new-comments-notice" role="status">
          <span>有新评论可查看。</span>
          <button
            type="button"
            className="text-button"
            onClick={() => {
              setSeenCommentRevision(commentRevision);
              void queryClient.resetQueries({ queryKey: commentsQueryKey, exact: true });
            }}
          >
            刷新评论
          </button>
        </div>
      ) : null}

      {commentsQuery.isPending ? <LoadingState label="正在加载评论" /> : null}
      {commentsQuery.isError ? (
        <ErrorState message={commentsError ?? '评论加载失败'} onRetry={() => void commentsQuery.refetch()} />
      ) : null}
      {!commentsQuery.isPending && !commentsQuery.isError && comments.length === 0 ? (
        <EmptyState
          title={filterCount > 0 ? '没有匹配的评论' : '暂无评论'}
          detail={filterCount > 0 ? '调整搜索或筛选条件后重试。' : '任务采集到评论后会显示在这里。'}
        />
      ) : null}
      {comments.length > 0 ? (
        <div className="table-scroll comment-table-scroll">
          <table className="data-table comment-table">
            <thead>
              <tr>
                <th>发布时间</th>
                <th>用户</th>
                <th>评论内容</th>
                <th>类型</th>
                <th>评论标识</th>
              </tr>
            </thead>
            <tbody>
              {comments.map((comment) => (
                <tr key={comment.id}>
                  <td className="comment-time"><time dateTime={comment.ctime}>{formatDateTime(comment.ctime)}</time></td>
                  <td className="comment-user">
                    <strong>{comment.uname?.trim() || '用户名未获取'}</strong>
                    <span>用户 UID {comment.mid}</span>
                    <span>{comment.currentLevel == null ? '用户等级未知' : `用户等级 ${String(comment.currentLevel)}`}</span>
                  </td>
                  <td className="comment-content">{comment.content?.trim() || '（空评论）'}</td>
                  <td className="comment-type">{comment.parentRpid == null ? '一级评论' : '回复'}</td>
                  <td className="comment-identifiers">
                    <span>评论 RPID {comment.rpid}</span>
                    {comment.parentRpid ? <span>父评论 RPID {comment.parentRpid}</span> : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      {commentsQuery.hasNextPage ? (
        <div className="comments-pagination">
          <span>已加载 {comments.length.toLocaleString('zh-CN')}{total == null ? '' : ` / ${total.toLocaleString('zh-CN')}`}</span>
          <button
            type="button"
            className="button button--secondary"
            disabled={commentsQuery.isFetchingNextPage}
            onClick={() => void commentsQuery.fetchNextPage()}
          >
            {commentsQuery.isFetchingNextPage ? '正在加载…' : '加载更多'}
          </button>
        </div>
      ) : null}

      <Dialog.Root open={filterOpen} onOpenChange={setFilterOpen}>
        <Dialog.Portal>
          <Dialog.Overlay className="modal-overlay" />
          <Dialog.Content className="modal-content comment-filter-dialog" aria-describedby="comment-filter-description">
            <header className="modal-header">
              <div>
                <Dialog.Title>筛选评论</Dialog.Title>
                <Dialog.Description id="comment-filter-description">
                  精确筛选用户、评论标识、回复类型、用户等级和发布时间；时间按北京时间处理。
                </Dialog.Description>
              </div>
              <Dialog.Close asChild>
                <button type="button" className="icon-button" aria-label="关闭筛选">
                  <X size={18} aria-hidden="true" />
                </button>
              </Dialog.Close>
            </header>
            <form
              className="comment-filter-form"
              onSubmit={(event) => {
                event.preventDefault();
                applyFilters();
              }}
            >
              <div className="filter-field-grid">
                <label className="field"><span>用户名</span><input value={filterDraft.uname} onChange={(event) => setFilterDraft((current) => ({ ...current, uname: event.target.value }))} /></label>
                <label className="field"><span>用户 UID</span><input inputMode="numeric" value={filterDraft.mid} onChange={(event) => setFilterDraft((current) => ({ ...current, mid: event.target.value }))} /></label>
                <label className="field"><span>评论 RPID</span><input inputMode="numeric" value={filterDraft.rpid} onChange={(event) => setFilterDraft((current) => ({ ...current, rpid: event.target.value }))} /></label>
                <label className="field"><span>父评论 RPID</span><input inputMode="numeric" value={filterDraft.parentRpid} onChange={(event) => setFilterDraft((current) => ({ ...current, parentRpid: event.target.value }))} /></label>
                <label className="field">
                  <span>评论类型</span>
                  <select value={filterDraft.replyScope} onChange={(event) => setFilterDraft((current) => ({ ...current, replyScope: event.target.value as CommentReplyScope }))}>
                    <option value="ALL">全部评论</option>
                    <option value="ROOT">一级评论</option>
                    <option value="REPLIES">回复</option>
                  </select>
                </label>
                <label className="field"><span>开始时间（含）</span><input type="datetime-local" value={filterDraft.ctimeFrom} onChange={(event) => setFilterDraft((current) => ({ ...current, ctimeFrom: event.target.value }))} /></label>
                <label className="field"><span>结束时间（不含）</span><input type="datetime-local" value={filterDraft.ctimeBefore} onChange={(event) => setFilterDraft((current) => ({ ...current, ctimeBefore: event.target.value }))} /></label>
              </div>
              <fieldset className="level-filter">
                <legend>用户等级</legend>
                <label><span>最低</span><select disabled={filterDraft.unknownLevelOnly} value={filterDraft.levelMin} onChange={(event) => setFilterDraft((current) => ({ ...current, levelMin: event.target.value }))}><option value="">不限</option>{[0, 1, 2, 3, 4, 5, 6].map((level) => <option key={level} value={level}>{level}</option>)}</select></label>
                <label><span>最高</span><select disabled={filterDraft.unknownLevelOnly} value={filterDraft.levelMax} onChange={(event) => setFilterDraft((current) => ({ ...current, levelMax: event.target.value }))}><option value="">不限</option>{[0, 1, 2, 3, 4, 5, 6].map((level) => <option key={level} value={level}>{level}</option>)}</select></label>
                <label className="checkbox-field"><input type="checkbox" checked={filterDraft.unknownLevelOnly} onChange={(event) => setFilterDraft((current) => ({ ...current, unknownLevelOnly: event.target.checked }))} />仅等级未知</label>
              </fieldset>
              {filterError ? <div className="inline-error" role="alert">{filterError}</div> : null}
              <div className="modal-actions filter-dialog-actions">
                <button type="button" className="button button--secondary" onClick={() => setFilterDraft(draftFromFilter(emptyFilter()))}>清空</button>
                <button type="submit" className="button button--primary">应用筛选</button>
              </div>
            </form>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </section>
  );
}

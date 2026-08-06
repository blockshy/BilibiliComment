import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  Link,
  MemoryRouter,
  Route,
  Routes,
  useLocation,
} from 'react-router-dom';
import { afterEach, beforeEach, vi } from 'vitest';
import { api, ApiError, type LiveEvent } from '../../api';
import { LiveEventsProvider } from '../../live/LiveEventsContext';
import {
  TaskCommentsPage,
} from './TaskCommentsPage';
import { beijingLocalDateTimeToIso, isoToBeijingLocalDateTime } from './commentTime';
import { TaskConfigurationPage } from './TaskConfigurationPage';
import { TaskDetailPage } from './TaskDetailPage';
import { TaskDiscoveredPage } from './TaskDiscoveredPage';
import { TaskExecutionsPage } from './TaskExecutionsPage';
import { TaskOverviewPage } from './TaskOverviewPage';

function LocationProbe() {
  const location = useLocation();
  return (
    <>
      <output data-testid="location">{location.pathname}{location.search}</output>
      <Link to="/tasks/101/comments">测试切换到任务 101</Link>
      <Link to="/tasks/102/comments">测试切换到任务 102</Link>
    </>
  );
}

interface RenderDetailOptions {
  path?: string;
  state?: Record<string, unknown>;
  live?: boolean;
}

function renderDetail({
  path = '/tasks/101/comments',
  state,
  live = false,
}: RenderDetailOptions = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 0 } },
  });
  const routes = (
    <>
      <LocationProbe />
      <Routes>
        <Route path="/tasks" element={<div>任务列表</div>} />
        <Route path="/tasks/:taskId" element={<TaskDetailPage />}>
          <Route index element={<TaskOverviewPage />} />
          <Route path="discovered" element={<TaskDiscoveredPage />} />
          <Route path="comments" element={<TaskCommentsPage />} />
          <Route path="executions" element={<TaskExecutionsPage />} />
          <Route path="configuration" element={<TaskConfigurationPage />} />
        </Route>
      </Routes>
    </>
  );
  const content = live ? <LiveEventsProvider>{routes}</LiveEventsProvider> : routes;
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[{ pathname: path, state }]}>
        {content}
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('TaskDetailPage', () => {
  beforeEach(() => window.sessionStorage.clear());
  afterEach(() => vi.restoreAllMocks());

  it('切换详情子路由后仍返回原任务筛选地址', async () => {
    const user = userEvent.setup();
    renderDetail({
      path: '/tasks/101',
      state: { from: '/tasks?query=%E7%95%AA%E5%89%A7&health=HEALTHY' },
    });

    expect(await screen.findByRole('heading', { name: '番剧更新评论' })).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: '执行记录' }));

    expect(screen.getByTestId('location')).toHaveTextContent('/tasks/101/executions');
    expect(screen.getByRole('link', { name: '返回任务' })).toHaveAttribute(
      'href',
      '/tasks?query=%E7%95%AA%E5%89%A7&health=HEALTHY',
    );
  });

  it('UP 主监控使用发现指标，并可筛选发现内容后进入规范任务地址', async () => {
    const user = userEvent.setup();
    renderDetail({ path: '/tasks/103' });

    expect(await screen.findByRole('heading', { name: 'UP 主内容监控' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '发现内容' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '评论' })).not.toBeInTheDocument();
    expect(await screen.findByText('建立失败')).toBeInTheDocument();
    expect(screen.getByText('新建自动采集')).toBeInTheDocument();
    expect(screen.getByText('关联已有')).toBeInTheDocument();
    expect(screen.queryByText('发现评论')).not.toBeInTheDocument();

    await user.click(screen.getByRole('link', { name: '发现内容' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/tasks/103/discovered');
    expect(await screen.findByText('查看该 UP 主发现的内容及其评论采集状态。')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: '查看 七月新番制作访谈' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '采集方式' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '评论总量' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '24 小时新增' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '1,840' })).toHaveAttribute('data-label', '评论总量');
    expect(screen.getByRole('cell', { name: '24' })).toHaveAttribute('data-label', '24 小时新增');

    await user.selectOptions(screen.getByRole('combobox', { name: '健康状态' }), 'ERROR');
    await waitFor(() => {
      expect(screen.getByTestId('location')).toHaveTextContent('/tasks/103/discovered?health=ERROR');
      expect(screen.getByRole('button', { name: '查看 幕后花絮动态' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: '查看 七月新番制作访谈' })).not.toBeInTheDocument();
    });

    await user.click(screen.getByRole('button', { name: '查看 幕后花絮动态' }));
    expect(await screen.findByRole('heading', { name: '幕后花絮动态' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/tasks/106');
    expect(screen.getByRole('link', { name: 'UP 主内容监控' })).toHaveAttribute(
      'href',
      '/tasks/103/discovered',
    );
    expect(screen.getByRole('link', { name: '返回发现内容' })).toHaveAttribute(
      'href',
      '/tasks/103/discovered?health=ERROR',
    );
  });

  it('仅在有计划的启用任务遇到调度器停用时提示', async () => {
    vi.spyOn(api, 'getSystemSummary').mockResolvedValue({
      api: 'UP',
      database: 'UP',
      bilibili: 'UP',
      schedulerEnabled: false,
      schedulerActive: 0,
      workerActive: 0,
      workerPoolSize: 4,
      workerQueueSize: 0,
      sseConnections: 0,
      recentFailureCount: 0,
      rateLimitedCount: 0,
      measuredAt: new Date().toISOString(),
    });

    renderDetail({ path: '/tasks/103' });

    expect(await screen.findByText('自动调度当前未启用。')).toBeInTheDocument();
    expect(screen.getByText('任务不会按计划启动，仍可使用“立即执行”。')).toBeInTheDocument();
  });

  it('评论条件不写入 URL，并按任务在同一会话中恢复', async () => {
    const user = userEvent.setup();
    renderDetail();
    const search = await screen.findByRole('textbox', { name: '搜索评论内容或用户名' });
    await user.type(search, '字幕');

    await waitFor(() => {
      const stored = window.sessionStorage.getItem('bilibili-comment:comment-view:101');
      expect(stored).not.toBeNull();
      expect(JSON.parse(stored ?? '{}')).toMatchObject({ filter: { keyword: '字幕' } });
    });
    expect(screen.getByTestId('location')).toHaveTextContent('/tasks/101/comments');
    expect(screen.getByTestId('location')).not.toHaveTextContent('?');

    await user.click(screen.getByRole('link', { name: '测试切换到任务 102' }));
    expect(await screen.findByRole('heading', { name: '绘画动态评论' })).toBeInTheDocument();
    expect(await screen.findByRole('textbox', { name: '搜索评论内容或用户名' })).toHaveValue('');
    await waitFor(() => {
      const task102State = window.sessionStorage.getItem('bilibili-comment:comment-view:102');
      expect(JSON.parse(task102State ?? '{}')).toMatchObject({ filter: { unknownLevelOnly: false } });
      expect(JSON.parse(task102State ?? '{}')).not.toMatchObject({ filter: { keyword: '字幕' } });
    });
    expect(JSON.parse(
      window.sessionStorage.getItem('bilibili-comment:comment-view:101') ?? '{}',
    )).toMatchObject({ filter: { keyword: '字幕' } });

    await user.click(screen.getByRole('link', { name: '测试切换到任务 101' }));
    expect(await screen.findByRole('heading', { name: '番剧更新评论' })).toBeInTheDocument();
    expect(await screen.findByRole('textbox', { name: '搜索评论内容或用户名' })).toHaveValue('字幕');
  });

  it('验证用户 UID，并要求父评论筛选与回复类型匹配', async () => {
    const user = userEvent.setup();
    renderDetail();

    await user.click(await screen.findByRole('button', { name: '筛选' }));
    await user.type(screen.getByRole('textbox', { name: '用户 UID' }), '0');
    await user.click(screen.getByRole('button', { name: '应用筛选' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('用户 UID 必须是正整数');

    await user.clear(screen.getByRole('textbox', { name: '用户 UID' }));
    await user.type(screen.getByRole('textbox', { name: '父评论 RPID' }), '123');
    await user.click(screen.getByRole('button', { name: '应用筛选' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '筛选父评论 RPID 时，评论类型必须选择“回复”',
    );
  });

  it('按北京时间解析和回显筛选边界，不依赖浏览器本地时区', () => {
    expect(beijingLocalDateTimeToIso('2026-07-14T00:00')).toBe('2026-07-13T16:00:00.000Z');
    expect(isoToBeijingLocalDateTime('2026-07-13T16:00:00.000Z')).toBe('2026-07-14T00:00');
  });

  it('评论搜索失败时显示服务端问题详情', async () => {
    vi.spyOn(api, 'searchComments').mockRejectedValue(new ApiError({
      status: 422,
      code: 'COMMENT_SEARCH_TOO_BROAD',
      detail: '关键词少于 3 个字符时必须同时提供其他筛选条件',
    }));

    renderDetail();

    expect(await screen.findByText('关键词少于 3 个字符时必须同时提供其他筛选条件')).toBeInTheDocument();
  });

  it('收到新评论事件时只提示，用户刷新后才重新请求首屏', async () => {
    const user = userEvent.setup();
    let onEvent: ((event: LiveEvent) => void) | undefined;
    vi.spyOn(api, 'subscribeToEvents').mockImplementation((eventHandler, connectionHandler) => {
      onEvent = eventHandler;
      connectionHandler('OPEN');
      return { close: vi.fn() };
    });
    const originalSearch = api.searchComments.bind(api);
    const searchComments = vi.spyOn(api, 'searchComments').mockImplementation(originalSearch);
    renderDetail({ live: true });

    expect((await screen.findAllByText('这个镜头的节奏处理得很好，期待完整版。')).length).toBeGreaterThan(0);
    await waitFor(() => expect(searchComments).toHaveBeenCalled());
    const callsBeforeEvent = searchComments.mock.calls.length;

    act(() => onEvent?.({
      eventId: 'event-1',
      type: 'comments.appended',
      occurredAt: new Date().toISOString(),
      sequence: 1,
      taskId: '101',
      executionId: '1',
      data: {},
    }));

    expect(await screen.findByText('有新评论可查看。')).toBeInTheDocument();
    expect(searchComments).toHaveBeenCalledTimes(callsBeforeEvent);

    await user.click(screen.getByRole('button', { name: '刷新评论' }));
    await waitFor(() => expect(searchComments.mock.calls.length).toBeGreaterThan(callsBeforeEvent));
    expect(searchComments.mock.calls.at(-1)?.[1]).toMatchObject({ cursor: null, includeTotal: true });
  });
});

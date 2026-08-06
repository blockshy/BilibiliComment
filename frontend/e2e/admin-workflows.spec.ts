import { expect, type Locator, type Page, test } from '@playwright/test';

const mockOrigin = 'http://127.0.0.1:4177';
const blockedRequests = new WeakMap<Page, string[]>();

test.beforeEach(async ({ page }) => {
  const blocked: string[] = [];
  blockedRequests.set(page, blocked);
  await page.route('**/*', async (route) => {
    const requestUrl = route.request().url();
    const sameOrigin = requestUrl === mockOrigin || requestUrl.startsWith(`${mockOrigin}/`);
    const path = sameOrigin ? requestUrl.slice(mockOrigin.length) : '';
    if (sameOrigin && /^\/api\/v1\/comment-exports\/[^/]+\/download$/.test(path)) {
      await route.fulfill({
        status: 200,
        contentType: 'text/csv; charset=UTF-8',
        headers: {
          'Cache-Control': 'no-store',
          'Content-Disposition': 'attachment; filename="bilibili-comments-e2e.csv"',
        },
        body: 'rpid,content\r\n9001,fixture\r\n',
      });
      return;
    }
    if (!sameOrigin || path.startsWith('/api/')) {
      blocked.push(requestUrl);
      await route.abort('blockedbyclient');
      return;
    }
    await route.continue();
  });
});

test.afterEach(({ page }) => {
  expect(blockedRequests.get(page) ?? [], 'mock 验收不应发出 API 或站外请求').toEqual([]);
});

async function login(page: Page): Promise<void> {
  await page.goto('/login');
  await page.evaluate(() => window.sessionStorage.clear());
  await page.goto('/tasks');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByLabel('用户名').fill('e2e-admin');
  await page.getByLabel('密码').fill('mock-password');
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page).toHaveURL(/\/tasks$/);
  await expect(page.getByRole('heading', { name: '任务', exact: true }))
    .toBeVisible({ timeout: 10_000 });
}

interface TaskDraft {
  kind: 'CONTENT_COMMENTS' | 'CREATOR_WATCH';
  sourceType?: '视频' | '动态';
  sourceInput: string;
  name: string;
}

async function fillTaskDraft(page: Page, draft: TaskDraft): Promise<Locator> {
  await page.getByRole('button', { name: '新建任务' }).click();
  const dialog = page.getByRole('dialog', { name: '新建任务' });
  await expect(dialog).toBeVisible();

  if (draft.kind === 'CREATOR_WATCH') {
    await dialog.getByRole('radio', { name: /UP 主内容监控/ }).check();
    await expect(dialog.getByText('来源固定为 UP 主 UID。')).toBeVisible();
  } else {
    await dialog.getByRole('radio', { name: /评论采集/ }).check();
    await dialog.getByRole('radio', { name: draft.sourceType ?? '视频', exact: true }).check();
  }
  await dialog.getByRole('button', { name: '下一步' }).click();

  const sourceLabel = draft.kind === 'CREATOR_WATCH'
    ? 'UID 或空间链接'
    : draft.sourceType === '动态'
      ? '动态 ID 或链接'
      : 'BV 号或视频链接';
  await dialog.getByLabel(sourceLabel).fill(draft.sourceInput);
  await dialog.getByRole('button', { name: '下一步' }).click();

  await expect(dialog.getByRole('heading', { name: '任务配置' })).toBeVisible();
  await dialog.getByLabel('任务名称').fill(draft.name);
  await dialog.getByLabel('访问凭据').selectOption('1');
  await dialog.getByRole('button', { name: '下一步' }).click();
  await expect(dialog.getByRole('heading', { name: '确认任务' })).toBeVisible();
  return dialog;
}

async function createPausedTask(page: Page, draft: TaskDraft): Promise<Locator> {
  const dialog = await fillTaskDraft(page, draft);
  await dialog.getByRole('button', { name: '创建但暂停' }).click();
  await expect(dialog).toBeHidden();
  const detail = page.locator('.task-detail-page');
  await expect(page).toHaveURL(/\/tasks\/\d+$/);
  await expect(detail.getByRole('heading', { name: draft.name })).toBeVisible();
  return detail;
}

test('管理员会话从登录开始，可刷新恢复并退出', async ({ page }) => {
  test.setTimeout(60_000);
  await login(page);

  await page.reload();
  await expect(page).toHaveURL(/\/tasks$/);
  await expect(page.getByRole('heading', { name: '任务', exact: true })).toBeVisible();

  await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: '管理员登录' })).toBeVisible();

  await page.reload();
  await expect(page).toHaveURL(/\/login$/);
});

test('评论任务支持筛选导出、重复来源提示和调度操作', async ({ page }) => {
  test.setTimeout(60_000);
  const taskName = 'E2E 视频评论采集';
  const sourceInput = 'BV1E2E7001abc';
  await login(page);
  const detail = await createPausedTask(page, {
    kind: 'CONTENT_COMMENTS',
    sourceType: '视频',
    sourceInput,
    name: taskName,
  });

  await detail.getByRole('link', { name: '评论' }).click();
  await expect(page).toHaveURL(/\/tasks\/\d+\/comments$/);
  const commentRows = detail.locator('.comment-table tbody tr');
  await expect(commentRows).toHaveCount(28);

  await detail.getByRole('textbox', { name: '搜索评论内容或用户名' }).fill('字幕');
  await expect(commentRows).toHaveCount(7);
  await detail.getByRole('button', { name: /^筛选/ }).click();
  const filterDialog = page.getByRole('dialog', { name: '筛选评论' });
  await filterDialog.getByLabel('评论类型').selectOption('REPLIES');
  await filterDialog.getByRole('button', { name: '应用筛选' }).click();
  await expect(commentRows).toHaveCount(2);
  await detail.getByRole('button', { name: '清除筛选' }).click();
  await expect(commentRows).toHaveCount(28);

  await detail.getByRole('button', { name: '导出', exact: true }).click();
  const exportDialog = page.getByRole('dialog', { name: '导出评论' });
  await exportDialog.getByRole('button', { name: '创建导出任务' }).click();
  await expect(exportDialog.getByText('已完成')).toBeVisible({ timeout: 5_000 });
  const downloadPromise = page.waitForEvent('download');
  await exportDialog.getByRole('link', { name: '下载' }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toMatch(/\.csv$/);
  await exportDialog.getByRole('button', { name: '关闭导出' }).click();

  await detail.getByRole('button', { name: '恢复任务' }).click();
  await expect(detail.getByRole('button', { name: '暂停任务' })).toBeVisible();
  await detail.getByRole('button', { name: '暂停任务' }).click();
  await expect(detail.getByRole('button', { name: '恢复任务' })).toBeVisible();
  await detail.getByRole('button', { name: '立即执行' }).click();
  await expect(detail.getByText('排队中', { exact: true })).toBeVisible();
  await expect(detail.getByRole('button', { name: '立即执行' })).toBeDisabled();

  await page.reload();
  await expect(page).toHaveURL(/\/tasks\/\d+\/comments$/);
  await expect(detail.getByRole('heading', { name: taskName })).toBeVisible();
  await expect(detail.getByRole('button', { name: '立即执行' })).toBeDisabled();
  await detail.getByRole('link', { name: '执行记录' }).click();
  await expect(detail.getByText('手动触发')).toBeVisible();

  await detail.getByRole('link', { name: '返回任务' }).click();

  const duplicateDialog = await fillTaskDraft(page, {
    kind: 'CONTENT_COMMENTS',
    sourceType: '视频',
    sourceInput,
    name: '重复来源验证',
  });
  await duplicateDialog.getByRole('button', { name: '创建但暂停' }).click();
  await expect(duplicateDialog.getByRole('alert')).toContainText('该来源已有启用任务');
  await duplicateDialog.getByRole('button', { name: '查看已有任务' }).click();
  await expect(duplicateDialog).toBeHidden();
  await expect(page.locator('.task-detail-page').getByRole('heading', { name: taskName })).toBeVisible();
});

test('动态评论任务可创建并按采集模式筛选', async ({ page }) => {
  const taskName = 'E2E 动态评论采集';
  const sourceInput = '987654321012340001';
  await login(page);
  const detail = await createPausedTask(page, {
    kind: 'CONTENT_COMMENTS',
    sourceType: '动态',
    sourceInput,
    name: taskName,
  });

  await expect(detail).toContainText(`动态 · ${sourceInput}`);
  await expect(detail.getByRole('link', { name: '评论' })).toBeVisible();

  await detail.getByRole('link', { name: '返回任务' }).click();
  await page.getByLabel('采集模式').selectOption('BACKFILL_ONLY');
  await expect(page.getByText('没有匹配的任务', { exact: true })).toBeVisible();
  await page.getByLabel('采集模式').selectOption('FOLLOW_ONLY');
  await expect(page.getByRole('row', { name: new RegExp(taskName) })).toBeVisible();
});

test('UP 主监控提供发现内容且不暴露评论页签', async ({ page }) => {
  const taskName = 'E2E UP 主内容监控';
  await login(page);
  const detail = await createPausedTask(page, {
    kind: 'CREATOR_WATCH',
    sourceInput: '7654321098765',
    name: taskName,
  });

  await expect(detail.getByRole('link', { name: '评论' })).toHaveCount(0);
  await expect(detail.getByRole('link', { name: '发现内容' })).toBeVisible();
  await expect(detail.getByRole('link', { name: '配置' })).toBeVisible();
});

test('任务范围、全局异常入口与发现内容保持清晰的导航关系', async ({ page }) => {
  await login(page);

  await expect(page.getByRole('button', { name: '主要任务' })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('button', { name: '查看 UP 主内容监控', exact: true })).toBeVisible();
  await expect(page.getByRole('row', { name: /七月新番制作访谈/ })).toHaveCount(0);

  await page.getByRole('button', { name: '自动采集' }).click();
  await expect(page).toHaveURL(/\/tasks\?scope=MANAGED$/);
  await expect(page.getByRole('row', { name: /七月新番制作访谈/ })).toBeVisible();
  await expect(page.getByRole('row', { name: /幕后花絮动态/ })).toBeVisible();
  await expect(page.getByRole('button', { name: '查看 UP 主内容监控', exact: true })).toHaveCount(0);

  await page.getByRole('button', { name: '查看异常任务' }).click();
  await expect(page).toHaveURL(/\/tasks\?scope=ALL&health=ERROR$/);
  await expect(page.getByRole('row', { name: /幕后花絮动态/ })).toBeVisible();
  await expect(page.getByRole('row', { name: /七月新番制作访谈/ })).toHaveCount(0);

  await page.goto('/tasks/103');
  const detail = page.locator('.task-detail-page');
  await expect(detail.getByRole('heading', { name: 'UP 主内容监控' })).toBeVisible();
  await expect(detail.getByText('建立失败', { exact: true })).toBeVisible();
  await expect(detail.getByText('新建自动采集', { exact: true })).toBeVisible();
  await detail.getByRole('link', { name: '发现内容' }).click();

  await expect(page).toHaveURL(/\/tasks\/103\/discovered$/);
  await expect(detail.getByText('查看该 UP 主发现的内容及其评论采集状态。')).toBeVisible();
  await expect(detail.getByRole('columnheader', { name: '采集方式' })).toBeVisible();
  await detail.getByRole('combobox', { name: '健康状态' }).selectOption('ERROR');
  await expect(page).toHaveURL(/\/tasks\/103\/discovered\?health=ERROR$/);
  await expect(detail.getByRole('button', { name: '查看 幕后花絮动态' })).toBeVisible();
  await expect(detail.getByRole('button', { name: '查看 七月新番制作访谈' })).toHaveCount(0);

  await detail.getByRole('button', { name: '查看 幕后花絮动态' }).click();
  await expect(page).toHaveURL(/\/tasks\/106$/);
  await expect(detail.getByRole('heading', { name: '幕后花絮动态' })).toBeVisible();
  await expect(detail.getByRole('link', { name: 'UP 主内容监控' })).toHaveAttribute(
    'href',
    '/tasks/103/discovered',
  );
  await expect(detail.getByRole('link', { name: '返回发现内容' })).toHaveAttribute(
    'href',
    '/tasks/103/discovered?health=ERROR',
  );
});

test('移动端可登录并打开任务创建流程', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page);

  await expect(page.getByRole('navigation', { name: '主导航' })).toBeVisible();
  await expect(page.getByRole('link', { name: '任务' })).toBeVisible();
  const monitorRow = page.getByRole('row').filter({
    has: page.getByRole('button', { name: '查看 UP 主内容监控', exact: true }),
  });
  await expect(monitorRow.getByText('需关注', { exact: true })).toBeVisible();
  await expect(monitorRow.getByText('已发现 3 个内容', { exact: true })).toBeVisible();
  const overflow = Number(await page.evaluate('document.documentElement.scrollWidth - window.innerWidth'));
  expect(overflow, '390px 任务列表不应横向溢出').toBeLessThanOrEqual(1);
  await page.getByRole('button', { name: '新建任务' }).click();
  const dialog = page.getByRole('dialog', { name: '新建任务' });
  await expect(dialog).toBeVisible();
  const dialogBounds = await dialog.boundingBox();
  expect(dialogBounds).not.toBeNull();
  expect(dialogBounds?.width ?? Number.POSITIVE_INFINITY).toBeLessThanOrEqual(390.5);
  await dialog.getByRole('button', { name: '关闭新建任务' }).click();
  await expect(dialog).toBeHidden();
});

test('任务详情在 390、768、1280 和 1440 宽度下保持可操作且不横向溢出', async ({ page }) => {
  test.setTimeout(60_000);
  await login(page);

  for (const width of [390, 768, 1280, 1440]) {
    await page.setViewportSize({ width, height: width <= 768 ? 844 : 960 });
    await page.goto('/tasks/101/comments');
    const detail = page.locator('.task-detail-page');
    await expect(detail.getByRole('heading', { name: '番剧更新评论' })).toBeVisible();
    await expect(detail.getByRole('textbox', { name: '搜索评论内容或用户名' })).toBeVisible();
    await expect(detail.locator('.comment-table tbody tr').first()).toBeVisible();
    const overflow = Number(await page.evaluate('document.documentElement.scrollWidth - window.innerWidth'));
    expect(overflow, `${String(width)}px 视口不应横向溢出`).toBeLessThanOrEqual(1);
  }

  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: '筛选' }).click();
  const filterDialog = page.getByRole('dialog', { name: '筛选评论' });
  const bounds = await filterDialog.boundingBox();
  expect(bounds).not.toBeNull();
  expect(bounds?.width ?? Number.POSITIVE_INFINITY).toBeLessThanOrEqual(390.5);
});

test('访问凭据页面使用 Cookie 与验证术语', async ({ page }) => {
  await login(page);
  await page.getByRole('link', { name: '访问凭据' }).click();

  await expect(page.getByRole('heading', { name: 'Bilibili 访问凭据' })).toBeVisible();
  await expect(page.getByRole('columnheader', { name: '验证状态' })).toBeVisible();
  await expect(page.getByRole('button', { name: '更新 Cookie' }).first()).toBeVisible();
});

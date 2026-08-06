import { beforeEach, describe, expect, it, vi } from 'vitest';

import { httpApi } from './http';

function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('httpApi', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
    vi.restoreAllMocks();
  });

  it('obtains a CSRF cookie before sending credentials', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => {
        document.cookie = 'XSRF-TOKEN=csrf-from-bootstrap; Path=/';
        return Promise.resolve(jsonResponse(
          { status: 401, code: 'AUTHENTICATION_REQUIRED', detail: '请先登录' },
          401,
        ));
      })
      .mockResolvedValueOnce(jsonResponse({ username: 'admin', displayName: '管理员' }, 200));

    await expect(httpApi.login('admin', 'not-logged-or-retried')).resolves.toEqual({
      username: 'admin',
      displayName: '管理员',
    });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/auth/me');
    const [loginUrl, loginInit] = fetchMock.mock.calls[1] ?? [];
    expect(loginUrl).toBe('/api/v1/auth/login');
    expect(loginInit?.method).toBe('POST');
    expect(new Headers(loginInit?.headers).get('X-XSRF-TOKEN')).toBe('csrf-from-bootstrap');
    expect(loginInit?.body).toBe(JSON.stringify({
      username: 'admin',
      password: 'not-logged-or-retried',
    }));
  });

  it('builds a same-origin download URL without buffering the file in JavaScript', () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch');

    expect(httpApi.commentExportDownloadUrl('42/unsafe'))
      .toBe('/api/v1/comment-exports/42%2Funsafe/download');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('serializes task scope and discovered-content filters on their canonical endpoints', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockImplementation(() => Promise.resolve(
        jsonResponse({ items: [], nextCursor: null, total: 0 }, 200),
      ));

    await httpApi.getTasks({
      scope: 'ALL',
      health: 'ERROR',
      query: '',
      cursor: 'dGFzay12MToxMDM',
      limit: 50,
    });
    await httpApi.getDiscoveredTasks('103/unsafe', {
      query: '幕后 花絮',
      sourceType: 'DYNAMIC',
      runtimeState: '',
      health: 'ERROR',
      cursor: 'next:2',
      limit: 25,
    });

    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      '/api/v1/tasks?scope=ALL&health=ERROR&cursor=dGFzay12MToxMDM&limit=50',
    );
    expect(fetchMock.mock.calls[1]?.[0]).toBe(
      '/api/v1/tasks/103%2Funsafe/discovered-tasks?query=%E5%B9%95%E5%90%8E+%E8%8A%B1%E7%B5%AE&sourceType=DYNAMIC&health=ERROR&cursor=next%3A2&limit=25',
    );
  });
});

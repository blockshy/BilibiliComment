import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { afterEach, vi } from 'vitest';
import { api, type AdminUser } from '../api';
import { AuthProvider, useAuth } from './AuthContext';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => {
    resolve = complete;
  });
  return { promise, resolve };
}

function AuthProbe() {
  const { user, loading, login } = useAuth();
  return (
    <>
      <output aria-label="当前用户">
        {loading ? '加载中' : user?.username ?? '未登录'}
      </output>
      <button type="button" onClick={() => void login('new-admin', 'password')}>
        登录
      </button>
    </>
  );
}

function renderAuth(children: ReactNode, queryClient: QueryClient) {
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>{children}</AuthProvider>
    </QueryClientProvider>,
  );
}

describe('AuthProvider', () => {
  afterEach(() => vi.restoreAllMocks());

  it('登录成功后忽略此前仍在进行的当前用户请求结果', async () => {
    const pendingCurrentUser = deferred<AdminUser>();
    const loggedInUser: AdminUser = {
      username: 'new-admin',
      displayName: '新管理员',
    };
    const staleUser: AdminUser = {
      username: 'old-admin',
      displayName: '旧管理员',
    };
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    const cancelQueries = vi.spyOn(queryClient, 'cancelQueries');
    vi.spyOn(api, 'getCurrentUser').mockReturnValue(pendingCurrentUser.promise);
    vi.spyOn(api, 'login').mockImplementation(() => {
      expect(cancelQueries).toHaveBeenCalledTimes(1);
      return Promise.resolve(loggedInUser);
    });

    renderAuth(<AuthProbe />, queryClient);
    await waitFor(() => expect(api.getCurrentUser).toHaveBeenCalledTimes(1));

    await userEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByLabelText('当前用户')).toHaveTextContent('new-admin');
    expect(cancelQueries).toHaveBeenCalledTimes(2);
    expect(cancelQueries).toHaveBeenNthCalledWith(1, { queryKey: ['auth', 'me'] });
    expect(cancelQueries).toHaveBeenNthCalledWith(2, { queryKey: ['auth', 'me'] });

    await act(async () => {
      pendingCurrentUser.resolve(staleUser);
      await pendingCurrentUser.promise;
    });

    expect(screen.getByLabelText('当前用户')).toHaveTextContent('new-admin');
    expect(screen.getByLabelText('当前用户')).not.toHaveTextContent('old-admin');
    expect(queryClient.getQueryData(['auth', 'me'])).toEqual(loggedInUser);
  });
});

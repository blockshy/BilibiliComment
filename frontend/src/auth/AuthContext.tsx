import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createContext,
  type ReactNode,
  useContext,
  useEffect,
  useMemo,
} from 'react';
import { api, ApiError, type AdminUser } from '../api';

interface AuthContextValue {
  user: AdminUser | null;
  loading: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  loginPending: boolean;
  loginError: string | null;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const currentUser = useQuery({
    queryKey: ['auth', 'me'],
    queryFn: api.getCurrentUser,
    retry: false,
    staleTime: 60_000,
  });
  const loginMutation = useMutation({
    mutationFn: ({ username, password }: { username: string; password: string }) =>
      api.login(username, password),
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey: ['auth', 'me'] });
    },
    onSuccess: async (user) => {
      await queryClient.cancelQueries({ queryKey: ['auth', 'me'] });
      queryClient.setQueryData(['auth', 'me'], user);
    },
  });
  const logoutMutation = useMutation({
    mutationFn: api.logout,
    onSuccess: () => {
      queryClient.clear();
      queryClient.setQueryData(['auth', 'me'], null);
    },
  });

  useEffect(() => {
    const handleUnauthorized = (): void => {
      queryClient.removeQueries({
        predicate: (query) => query.queryKey[0] !== 'auth',
      });
      queryClient.setQueryData(['auth', 'me'], null);
    };
    window.addEventListener('bilibili-comment:unauthorized', handleUnauthorized);
    return () => window.removeEventListener('bilibili-comment:unauthorized', handleUnauthorized);
  }, [queryClient]);

  const value = useMemo<AuthContextValue>(() => ({
    user: currentUser.data ?? null,
    loading: currentUser.isPending,
    login: async (username, password) => {
      await loginMutation.mutateAsync({ username, password });
    },
    logout: async () => {
      await logoutMutation.mutateAsync();
    },
    loginPending: loginMutation.isPending,
    loginError: loginMutation.error instanceof ApiError
      ? loginMutation.error.problem.detail
      : loginMutation.error
        ? '登录失败，请稍后重试'
        : null,
  }), [
    currentUser.data,
    currentUser.isPending,
    loginMutation,
    logoutMutation,
  ]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used within AuthProvider');
  return context;
}

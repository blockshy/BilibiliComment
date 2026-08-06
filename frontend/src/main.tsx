import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App';
import { ApiError } from './api';
import { AuthProvider } from './auth/AuthContext';
import './styles/global.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 10_000,
      retry: (failureCount, error) => {
        const status = error instanceof ApiError ? error.problem.status : undefined;
        return status !== 401 && failureCount < 2;
      },
      refetchOnWindowFocus: true,
    },
  },
});

const rootElement = document.getElementById('root');
if (!rootElement) throw new Error('缺少应用挂载节点');

createRoot(rootElement).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <App />
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);

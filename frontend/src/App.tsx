import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AppShell } from './components/AppShell';
import { LoadingState } from './components/PageState';
import { useAuth } from './auth/AuthContext';
import { LiveEventsProvider } from './live/LiveEventsContext';

const LoginPage = lazy(async () => ({ default: (await import('./pages/LoginPage')).LoginPage }));
const TasksPage = lazy(async () => ({ default: (await import('./pages/TasksPage')).TasksPage }));
const TaskDetailPage = lazy(async () => ({
  default: (await import('./pages/task-detail/TaskDetailPage')).TaskDetailPage,
}));
const TaskOverviewPage = lazy(async () => ({
  default: (await import('./pages/task-detail/TaskOverviewPage')).TaskOverviewPage,
}));
const TaskCommentsPage = lazy(async () => ({
  default: (await import('./pages/task-detail/TaskCommentsPage')).TaskCommentsPage,
}));
const TaskDiscoveredPage = lazy(async () => ({
  default: (await import('./pages/task-detail/TaskDiscoveredPage')).TaskDiscoveredPage,
}));
const TaskExecutionsPage = lazy(async () => ({
  default: (await import('./pages/task-detail/TaskExecutionsPage')).TaskExecutionsPage,
}));
const TaskConfigurationPage = lazy(async () => ({
  default: (await import('./pages/task-detail/TaskConfigurationPage')).TaskConfigurationPage,
}));
const SystemPage = lazy(async () => ({ default: (await import('./pages/SystemPage')).SystemPage }));
const CredentialsPage = lazy(async () => ({ default: (await import('./pages/CredentialsPage')).CredentialsPage }));

function ProtectedLayout() {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) return <LoadingState label="正在确认会话" />;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;

  return (
    <LiveEventsProvider>
      <AppShell />
    </LiveEventsProvider>
  );
}

export function App() {
  return (
    <Suspense fallback={<LoadingState label="正在加载页面" />}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<ProtectedLayout />}>
          <Route path="/tasks" element={<TasksPage />} />
          <Route path="/tasks/:taskId" element={<TaskDetailPage />}>
            <Route index element={<TaskOverviewPage />} />
            <Route path="discovered" element={<TaskDiscoveredPage />} />
            <Route path="comments" element={<TaskCommentsPage />} />
            <Route path="executions" element={<TaskExecutionsPage />} />
            <Route path="configuration" element={<TaskConfigurationPage />} />
          </Route>
          <Route path="/system" element={<SystemPage />} />
          <Route path="/settings/credentials" element={<CredentialsPage />} />
        </Route>
        <Route path="/" element={<Navigate to="/tasks" replace />} />
        <Route path="*" element={<Navigate to="/tasks" replace />} />
      </Routes>
    </Suspense>
  );
}

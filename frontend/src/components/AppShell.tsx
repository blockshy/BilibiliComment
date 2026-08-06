import {
  Activity,
  Database,
  ListChecks,
  LogOut,
  Radio,
  RadioTower,
} from 'lucide-react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { resolveAppEnvironment } from '../lib/environment';
import { useLiveEvents } from '../live/LiveEventsContext';

const environment = resolveAppEnvironment();

export function AppShell() {
  const { user, logout } = useAuth();
  const { connectionState } = useLiveEvents();
  const navigate = useNavigate();
  const connected = connectionState === 'OPEN';

  const handleLogout = async (): Promise<void> => {
    await logout();
    await navigate('/login', { replace: true });
  };

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="sidebar__brand">
          <span className="brand-name">Bilibili 评论任务</span>
          <span className={`environment-label environment-label--${environment.toLowerCase()}`}>
            {environment}
          </span>
        </div>

        <nav className="primary-nav" aria-label="主导航">
          <NavLink to="/tasks" className={({ isActive }) => isActive ? 'nav-link is-active' : 'nav-link'}>
            <ListChecks size={18} aria-hidden="true" />
            任务
          </NavLink>
          <NavLink to="/system" className={({ isActive }) => isActive ? 'nav-link is-active' : 'nav-link'}>
            <Activity size={18} aria-hidden="true" />
            系统状态
          </NavLink>
          <NavLink
            to="/settings/credentials"
            className={({ isActive }) => isActive ? 'nav-link is-active' : 'nav-link'}
          >
            <Database size={18} aria-hidden="true" />
            访问凭据
          </NavLink>
        </nav>

        <div className="sidebar__footer">
          <div className="connection-status" aria-live="polite">
            {connected ? <Radio size={16} aria-hidden="true" /> : <RadioTower size={16} aria-hidden="true" />}
            <span>{connected ? '实时连接正常' : '实时连接恢复中'}</span>
          </div>
          <div className="account-row">
            <span>{user?.displayName ?? '管理员'}</span>
            <button
              type="button"
              className="icon-button"
              aria-label="退出登录"
              onClick={() => { void handleLogout(); }}
            >
              <LogOut size={17} aria-hidden="true" />
            </button>
          </div>
          <span className="app-version">版本 {import.meta.env.VITE_APP_VERSION ?? 'local'}</span>
        </div>
      </aside>

      <main className="main-workspace">
        <Outlet />
      </main>
    </div>
  );
}

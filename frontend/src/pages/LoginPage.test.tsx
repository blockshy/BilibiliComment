import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';
import { LoginPage } from './LoginPage';

const login = vi.fn(async () => await Promise.resolve());

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: null,
    loading: false,
    login,
    logout: vi.fn(),
    loginPending: false,
    loginError: null,
  }),
}));

describe('LoginPage', () => {
  it('阻止空用户名和密码提交', async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>,
    );

    await user.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByText('请输入用户名')).toBeInTheDocument();
    expect(screen.getByText('请输入密码')).toBeInTheDocument();
    expect(login).not.toHaveBeenCalled();
  });
});

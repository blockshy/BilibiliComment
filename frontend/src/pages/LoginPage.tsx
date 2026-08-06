import { zodResolver } from '@hookform/resolvers/zod';
import { LockKeyhole } from 'lucide-react';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useLocation, useNavigate } from 'react-router-dom';
import { z } from 'zod';
import { useAuth } from '../auth/AuthContext';
import { resolveAppEnvironment } from '../lib/environment';

const loginSchema = z.object({
  username: z.string().trim().min(1, '请输入用户名'),
  password: z.string().min(1, '请输入密码'),
});

type LoginValues = z.infer<typeof loginSchema>;

export function LoginPage() {
  const environment = resolveAppEnvironment();
  const { user, login, loginPending, loginError } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const destination = (location.state as { from?: string } | null)?.from ?? '/tasks';
  const { register, handleSubmit, formState: { errors } } = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { username: '', password: '' },
  });

  useEffect(() => {
    if (user) void navigate(destination, { replace: true });
  }, [destination, navigate, user]);

  const submit = handleSubmit(async (values) => {
    await login(values.username, values.password);
  });

  return (
    <main className="login-page">
      <section className="login-panel" aria-labelledby="login-title">
        <div className="login-brand">
          <LockKeyhole size={20} aria-hidden="true" />
          <span>Bilibili 评论任务</span>
          <span className={`environment-label environment-label--${environment.toLowerCase()}`}>
            {environment}
          </span>
        </div>
        <h1 id="login-title">管理员登录</h1>
        <p>登录后可管理采集任务并查看实时执行状态。</p>

        <form
          className="form-stack"
          onSubmit={(event) => { void submit(event); }}
          noValidate
        >
          <label className="field">
            <span>用户名</span>
            <input autoComplete="username" {...register('username')} aria-invalid={Boolean(errors.username)} />
            {errors.username ? <span className="field-error">{errors.username.message}</span> : null}
          </label>
          <label className="field">
            <span>密码</span>
            <input
              type="password"
              autoComplete="current-password"
              {...register('password')}
              aria-invalid={Boolean(errors.password)}
            />
            {errors.password ? <span className="field-error">{errors.password.message}</span> : null}
          </label>

          {loginError ? <div className="form-error" role="alert">{loginError}</div> : null}

          <button type="submit" className="button button--primary button--full" disabled={loginPending}>
            {loginPending ? '正在登录…' : '登录'}
          </button>
        </form>
      </section>
    </main>
  );
}

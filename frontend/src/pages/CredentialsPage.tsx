import { zodResolver } from '@hookform/resolvers/zod';
import * as Dialog from '@radix-ui/react-dialog';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { KeyRound, RefreshCw, X } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { api, ApiError, type CredentialProfile } from '../api';
import { ErrorState, LoadingState } from '../components/PageState';
import { StatusText } from '../components/StatusText';
import { formatDate } from '../lib/format';

const secretSchema = z.object({
  secret: z.string().trim().min(8, '请输入完整的 Cookie 内容'),
});

type SecretValues = z.infer<typeof secretSchema>;

export function CredentialsPage() {
  const [selectedCredential, setSelectedCredential] = useState<CredentialProfile | null>(null);
  const queryClient = useQueryClient();
  const credentials = useQuery({ queryKey: ['credentials'], queryFn: api.getCredentials });
  const validation = useMutation({
    mutationFn: api.validateCredential,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['credentials'] }),
  });
  const replacement = useMutation({
    mutationFn: ({ id, secret }: { id: string; secret: string }) => api.replaceCredentialSecret(id, secret),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['credentials'] });
      setSelectedCredential(null);
      reset();
    },
  });
  const { register, handleSubmit, reset, formState: { errors } } = useForm<SecretValues>({
    resolver: zodResolver(secretSchema),
    defaultValues: { secret: '' },
  });

  const saveSecret = handleSubmit(async (values) => {
    if (!selectedCredential) return;
    await replacement.mutateAsync({ id: selectedCredential.id, secret: values.secret });
  });
  const replacementError = replacement.error instanceof ApiError
    ? replacement.error.problem.detail
    : replacement.error
      ? '凭据更新失败'
      : null;
  const validationError = validation.error instanceof ApiError
    ? validation.error.problem.detail
    : validation.error
      ? '访问凭据验证失败'
      : null;

  return (
    <div className="page-container page-container--narrow">
      <header className="page-header">
        <div>
          <h1>Bilibili 访问凭据</h1>
          <p>管理采集请求使用的登录 Cookie；Cookie 原文保存后不会回显。</p>
        </div>
      </header>

      <div className="security-notice">
        <KeyRound size={17} aria-hidden="true" />
        <span>页面、API 响应和实时事件只显示凭据状态，不显示 Cookie 内容。</span>
      </div>

      {validationError ? <div className="inline-error" role="alert">{validationError}</div> : null}
      {credentials.isPending ? <LoadingState label="正在加载访问凭据" /> : null}
      {credentials.isError ? <ErrorState message="访问凭据加载失败" onRetry={() => void credentials.refetch()} /> : null}
      {credentials.data ? (
        <div className="table-scroll">
          <table className="data-table credential-table">
            <thead>
              <tr>
                <th>名称</th>
                <th>启用状态</th>
                <th>验证状态</th>
                <th>最近验证</th>
                <th aria-label="操作" />
              </tr>
            </thead>
            <tbody>
              {credentials.data.map((credential) => (
                <tr key={credential.id}>
                  <td><strong>{credential.name}</strong></td>
                  <td><StatusText tone={credential.enabled ? 'positive' : 'neutral'}>{credential.enabled ? '已启用' : '已停用'}</StatusText></td>
                  <td><StatusText tone={credential.valid ? 'positive' : 'warning'}>{credential.valid ? '有效' : '待验证'}</StatusText></td>
                  <td>{formatDate(credential.lastValidatedAt)}</td>
                  <td>
                    <div className="row-actions">
                      <button
                        type="button"
                        className="button button--secondary button--small"
                        onClick={() => validation.mutate(credential.id)}
                        disabled={validation.isPending}
                      >
                        <RefreshCw size={14} aria-hidden="true" />
                        验证
                      </button>
                      <button
                        type="button"
                        className="button button--secondary button--small"
                        onClick={() => setSelectedCredential(credential)}
                      >
                        更新 Cookie
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      <Dialog.Root
        open={Boolean(selectedCredential)}
        onOpenChange={(open) => {
          if (!open) {
            setSelectedCredential(null);
            reset();
          }
        }}
      >
        <Dialog.Portal>
          <Dialog.Overlay className="modal-overlay" />
          <Dialog.Content className="modal-content" aria-describedby="secret-dialog-description">
            <header className="modal-header">
              <div>
                <Dialog.Title>更新 {selectedCredential?.name} 的 Cookie</Dialog.Title>
                <Dialog.Description id="secret-dialog-description">
                  保存后旧值立即失效，新值不会再次显示。
                </Dialog.Description>
              </div>
              <Dialog.Close asChild>
                <button type="button" className="icon-button" aria-label="关闭">
                  <X size={18} aria-hidden="true" />
                </button>
              </Dialog.Close>
            </header>
            <form
              className="form-stack"
              onSubmit={(event) => { void saveSecret(event); }}
              noValidate
            >
              <label className="field">
                <span>Cookie</span>
                <textarea
                  rows={6}
                  autoComplete="off"
                  spellCheck={false}
                  {...register('secret')}
                  aria-invalid={Boolean(errors.secret)}
                />
                {errors.secret ? <span className="field-error">{errors.secret.message}</span> : null}
              </label>
              {replacementError ? <div className="form-error" role="alert">{replacementError}</div> : null}
              <div className="modal-actions">
                <Dialog.Close asChild>
                  <button type="button" className="button button--secondary">取消</button>
                </Dialog.Close>
                <button type="submit" className="button button--primary" disabled={replacement.isPending}>
                  {replacement.isPending ? '正在保存…' : '保存 Cookie'}
                </button>
              </div>
            </form>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  );
}

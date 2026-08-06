import { AlertCircle, Inbox } from 'lucide-react';

export function LoadingState({ label = '正在加载' }: { label?: string }) {
  return (
    <div className="page-state" role="status">
      <span className="loading-line" aria-hidden="true" />
      <span>{label}</span>
    </div>
  );
}

export function EmptyState({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="page-state page-state--empty">
      <Inbox size={20} aria-hidden="true" />
      <strong>{title}</strong>
      <span>{detail}</span>
    </div>
  );
}

export function ErrorState({
  message,
  onRetry,
}: {
  message: string;
  onRetry?: () => void;
}) {
  return (
    <div className="page-state page-state--error" role="alert">
      <AlertCircle size={20} aria-hidden="true" />
      <span>{message}</span>
      {onRetry ? (
        <button type="button" className="button button--secondary" onClick={onRetry}>
          重试
        </button>
      ) : null}
    </div>
  );
}

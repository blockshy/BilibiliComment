import type { ReactNode } from 'react';

export function StatusText({
  tone,
  children,
}: {
  tone: 'neutral' | 'positive' | 'warning' | 'negative' | 'active';
  children: ReactNode;
}) {
  return <span className={`status-text status-text--${tone}`}>{children}</span>;
}

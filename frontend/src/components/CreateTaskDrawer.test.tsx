import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { vi } from 'vitest';
import { CreateTaskDrawer } from './CreateTaskDrawer';

function renderDrawer() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <CreateTaskDrawer
        open
        onOpenChange={vi.fn()}
        onCreated={vi.fn()}
        onExistingTask={vi.fn()}
      />
    </QueryClientProvider>,
  );
}

describe('CreateTaskDrawer', () => {
  it('切换动态来源并保持默认持续增量采集', async () => {
    const user = userEvent.setup();
    renderDrawer();

    await user.click(screen.getByRole('radio', { name: '动态' }));
    await user.click(screen.getByRole('button', { name: /下一步/ }));

    expect(await screen.findByRole('heading', { name: '填写动态来源' })).toBeInTheDocument();
    const sourceInput = screen.getByLabelText('动态 ID 或链接');
    await user.type(sourceInput, '987654321012345678');
    await user.click(screen.getByRole('button', { name: /下一步/ }));

    expect(await screen.findByRole('heading', { name: '任务配置' })).toBeInTheDocument();
    expect(screen.getByLabelText('采集模式')).toHaveValue('FOLLOW_ONLY');
    expect(screen.getByLabelText('任务名称')).not.toHaveValue('');
  });
});

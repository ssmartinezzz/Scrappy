import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import FailedLastRunBanner from './FailedLastRunBanner';

const RUN = {
  hayCorrida: true,
  fallidos: [
    { sitio: 'city', error: 'Deadline global', porDeadline: true },
    { sitio: 'freres', error: 'timeout', porDeadline: false },
  ],
};

function renderBanner(props = {}) {
  return render(<FailedLastRunBanner run={RUN} busy={false} error="" onRetry={vi.fn()} onDismiss={vi.fn()} {...props}/>);
}

describe('FailedLastRunBanner', () => {
  it('renders nothing when the last completed run has no failures', () => {
    const { container } = renderBanner({ run: null });
    expect(container).toBeEmptyDOMElement();
  });

  it('names failed sites and distinguishes global-deadline cuts', () => {
    renderBanner();
    expect(screen.getByRole('alert')).toHaveTextContent(/city.*deadline global/i);
    expect(screen.getByRole('alert')).toHaveTextContent(/freres.*timeout/i);
    expect(screen.getByRole('alert')).toHaveTextContent(/1 por deadline global/i);
  });

  it('summarizes a Playwright stack to its message and caps long errors', () => {
    const stack = "Error {\n  message='Target page, context or browser has been closed\n  stack='at x (/tmp/a.js:1:1)";
    const largo = 'x'.repeat(200);
    renderBanner({ run: { fallidos: [
      { sitio: 'dcshoes', error: stack, porDeadline: false },
      { sitio: 'venex', error: largo, porDeadline: false },
    ] } });
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('dcshoes (Target page, context or browser has been closed)');
    expect(alert).not.toHaveTextContent(/tmp\/a\.js/);
    expect(alert).toHaveTextContent(`venex (${'x'.repeat(79)}…)`);
    expect(alert).not.toHaveTextContent('x'.repeat(80));
  });

  it('retries or dismisses only through its explicit actions', async () => {
    const onRetry = vi.fn();
    const onDismiss = vi.fn();
    renderBanner({ onRetry, onDismiss });

    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }));
    await userEvent.click(screen.getByRole('button', { name: /ocultar/i }));

    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it('locks both actions while the retry is in flight', () => {
    renderBanner({ busy: true });
    expect(screen.getByRole('button', { name: /reintentando/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /ocultar/i })).toBeDisabled();
  });
});

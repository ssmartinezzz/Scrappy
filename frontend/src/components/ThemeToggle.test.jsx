import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ThemeToggle from './ThemeToggle';

beforeEach(() => {
  window.localStorage.clear();
  document.documentElement.classList.remove('dark');
  window.matchMedia = vi.fn().mockReturnValue({ matches: false });
});
afterEach(() => { delete window.matchMedia; });

describe('ThemeToggle', () => {
  it('offers dark mode first and flips label + aria-pressed on click', async () => {
    const user = userEvent.setup();
    render(<ThemeToggle />);
    const btn = screen.getByRole('button', { name: 'Activar modo oscuro' });
    expect(btn).toHaveAttribute('aria-pressed', 'false');
    await user.click(btn);
    const back = screen.getByRole('button', { name: 'Activar modo claro' });
    expect(back).toHaveAttribute('aria-pressed', 'true');
    expect(document.documentElement).toHaveClass('dark');
  });

  it.each([['{Enter}'], [' ']])('is keyboard-operable (key %j)', async key => {
    const user = userEvent.setup();
    render(<ThemeToggle />);
    await user.tab();
    expect(screen.getByRole('button')).toHaveFocus();
    await user.keyboard(key);
    expect(document.documentElement).toHaveClass('dark');
  });
});

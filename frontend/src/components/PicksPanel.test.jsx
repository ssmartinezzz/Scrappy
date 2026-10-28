import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import PicksPanel from '@/components/PicksPanel';
import { fetchMejores } from '@/api';

vi.mock('@/api', async importOriginal => ({
  ...(await importOriginal()),
  fetchMejores: vi.fn(),
}));

const cat = (categoria, count) => ({
  categoria, count, rubro: 'indumentaria', imgCat: '', mediana: 1000,
  picks: [{ tipo: 'valor', label: 'Mejor', nombre: 'P', precio: 1000, url: 'https://t/p', img: '' }],
});

beforeEach(() => {
  vi.clearAllMocks();
  // jsdom has no matchMedia; the carousel reads it for its page size.
  window.matchMedia = vi.fn().mockImplementation(query => ({
    matches: true, media: query, addEventListener: vi.fn(), removeEventListener: vi.fn(),
    addListener: vi.fn(), removeListener: vi.fn(),
  }));
  fetchMejores.mockImplementation(async (rubro, opts = {}) => (
    opts.gymrat ? [cat('Creatina Gym', 4)] : [cat('Remera', 9)]
  ));
});

describe('PicksPanel — Gymrat', () => {
  it('shows a Gymrat card after the rubros and fetches its summary on entry', async () => {
    render(<PicksPanel />);

    expect(await screen.findByRole('button', { name: 'Gymrat' })).toBeInTheDocument();
    await waitFor(() => expect(fetchMejores).toHaveBeenCalledWith('', { gymrat: true }));
  });

  it('opens the gallery filtered by gymrat and never sends it as a rubro', async () => {
    const user = userEvent.setup();
    render(<PicksPanel />);

    await user.click(await screen.findByRole('button', { name: 'Gymrat' }));

    expect(await screen.findByText('Creatina Gym')).toBeInTheDocument();
    expect(screen.queryByText('Remera')).not.toBeInTheDocument();
    const rubros = fetchMejores.mock.calls.map(c => c[0]);
    expect(rubros).not.toContain('gymrat');
  });

  it('keeps a Gymrat tab in the gallery bar', async () => {
    const user = userEvent.setup();
    render(<PicksPanel />);

    await user.click(await screen.findByRole('button', { name: 'Gymrat' }));
    await screen.findByText('Creatina Gym');

    expect(screen.getByRole('button', { name: /Gymrat/ })).toHaveClass('active');
  });
});

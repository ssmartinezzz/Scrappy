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
  window.matchMedia = vi.fn().mockImplementation(query => ({
    matches: true, media: query, addEventListener: vi.fn(), removeEventListener: vi.fn(),
    addListener: vi.fn(), removeListener: vi.fn(),
  }));
  fetchMejores.mockImplementation(async (rubro, opts = {}) => {
    if (opts.genero === 'hombre') return [cat('Short Hombre', 3)];
    if (opts.genero === 'mujer') return [cat('Short Mujer', 2)];
    return opts.gymrat ? [cat('Gym Todos', 5)] : [cat('Remera', 9)];
  });
});

async function abrirGymrat(user) {
  await user.click(await screen.findByRole('button', { name: 'Gymrat' }));
}

describe('PicksPanel — Gymrat Hombre / Mujer', () => {
  it('opens on Hombre and asks the backend for that gender', async () => {
    const user = userEvent.setup();
    render(<PicksPanel />);

    await abrirGymrat(user);

    expect(await screen.findByText('Short Hombre')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hombre' })).toHaveClass('active');
    expect(screen.getByRole('button', { name: 'Mujer' })).not.toHaveClass('active');
    expect(fetchMejores).toHaveBeenCalledWith('', { gymrat: true, genero: 'hombre' });
  });

  it('switches to Mujer and fetches it once', async () => {
    const user = userEvent.setup();
    render(<PicksPanel />);
    await abrirGymrat(user);
    await screen.findByText('Short Hombre');

    await user.click(screen.getByRole('button', { name: 'Mujer' }));

    expect(await screen.findByText('Short Mujer')).toBeInTheDocument();
    expect(screen.queryByText('Short Hombre')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Mujer' })).toHaveClass('active');

    await user.click(screen.getByRole('button', { name: 'Hombre' }));
    await screen.findByText('Short Hombre');
    const mujer = fetchMejores.mock.calls.filter(c => c[1]?.genero === 'mujer');
    expect(mujer).toHaveLength(1);
  });

  it('shows no gender toggle outside Gymrat', async () => {
    const user = userEvent.setup();
    render(<PicksPanel />);

    await user.click(await screen.findByRole('button', { name: /Indumentaria/ }));
    await screen.findByText('Remera');

    expect(screen.queryByRole('button', { name: 'Mujer' })).not.toBeInTheDocument();
  });

  it('keeps the entry card summary on the unfiltered Gymrat request', async () => {
    render(<PicksPanel />);

    await waitFor(() => expect(fetchMejores).toHaveBeenCalledWith('', { gymrat: true }));
  });
});

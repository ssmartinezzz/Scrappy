import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import PicksPanel from '@/components/PicksPanel';
import { fetchMejores, fetchMejoresPicks } from '@/api';
import { PERFUMERIA_PROXIMAMENTE, PERFUME_SUBCATEGORIAS } from '@/lib/perfumeria';

vi.mock('@/api', async importOriginal => ({
  ...(await importOriginal()),
  fetchMejores: vi.fn(),
  fetchMejoresPicks: vi.fn(),
}));

const pick = (n, sub) => ({
  tipo: 'top', label: 'Buena compra', nombre: `Fragancia ${n}`, precio: 1000 + n, url: `https://t/${n}`, img: '',
  ...(sub ? { subCategoria: sub } : {}),
});

const perfume = {
  categoria: 'Perfume', count: 4, rubro: 'perfumeria', imgCat: '', mediana: 1000,
  picks: [pick(1, 'Eau de Parfum'), pick(2, 'Eau de Toilette'), pick(3, 'Body Splash'), pick(4)],
};
const remera = { categoria: 'Remera', count: 9, rubro: 'indumentaria', imgCat: '', mediana: 1000, picks: [pick(9)] };

beforeEach(() => {
  vi.clearAllMocks();
  window.matchMedia = vi.fn().mockImplementation(query => ({
    matches: true, media: query, addEventListener: vi.fn(), removeEventListener: vi.fn(),
    addListener: vi.fn(), removeListener: vi.fn(),
  }));
  window.IntersectionObserver = class { observe() {} disconnect() {} };
  fetchMejores.mockImplementation(async rubro => (rubro === 'perfumeria' ? [perfume] : [remera]));
  fetchMejoresPicks.mockResolvedValue({ picks: [], nextCursor: null, total: 0 });
});

const abrirPerfumeria = async user => {
  await user.click(await screen.findByRole('button', { name: 'Perfumería' }));
  await screen.findByText('Perfume');
};

describe('PicksPanel — perfumería', () => {
  it('offers Perfumería as a rubro entry', async () => {
    render(<PicksPanel />);
    expect(await screen.findByRole('button', { name: 'Perfumería' })).toBeInTheDocument();
  });

  it('lists every mocked "Próximamente" card, greyed and not clickable', async () => {
    const user = userEvent.setup();
    render(<PicksPanel />);
    await abrirPerfumeria(user);

    expect(PERFUMERIA_PROXIMAMENTE.map(c => c.label))
      .toEqual(['Maquillaje', 'Cuidado de la piel', 'Cabello', 'Desodorantes']);
    for (const { label } of PERFUMERIA_PROXIMAMENTE) {
      const card = screen.getByText(label).closest('[data-proximamente]');
      expect(card).not.toBeNull();
      expect(card).toHaveAttribute('aria-disabled', 'true');
      expect(card.tagName).not.toBe('BUTTON');
      await user.click(card);
    }
    expect(screen.getAllByText('Próximamente')).toHaveLength(4);
    expect(fetchMejoresPicks).not.toHaveBeenCalled();
  });

  it('shows no Próximamente cards in other rubros', async () => {
    const user = userEvent.setup();
    render(<PicksPanel />);
    await user.click(await screen.findByRole('button', { name: 'Indumentaria' }));
    await screen.findByText('Remera');
    expect(screen.queryByText('Próximamente')).not.toBeInTheDocument();
  });

  it('filters the Perfume picks by sub-category chip', async () => {
    const user = userEvent.setup();
    render(<PicksPanel />);
    await abrirPerfumeria(user);
    await user.click(screen.getByText('Perfume'));

    for (const sub of PERFUME_SUBCATEGORIAS) {
      expect(await screen.findByRole('button', { name: sub })).toBeInTheDocument();
    }
    expect(screen.getByText('Fragancia 1')).toBeInTheDocument();
    expect(screen.getByText('Fragancia 4')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Eau de Toilette' }));
    await waitFor(() => expect(screen.queryByText('Fragancia 1')).not.toBeInTheDocument());
    expect(screen.getByText('Fragancia 2')).toBeInTheDocument();
    expect(screen.queryByText('Fragancia 4')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Todos' }));
    expect(await screen.findByText('Fragancia 4')).toBeInTheDocument();
  });

  it('shows no sub-category chips on a category of another rubro', async () => {
    const user = userEvent.setup();
    render(<PicksPanel />);
    await user.click(await screen.findByRole('button', { name: 'Indumentaria' }));
    await user.click(await screen.findByText('Remera'));
    expect(screen.queryByRole('button', { name: 'Eau de Toilette' })).not.toBeInTheDocument();
  });
});

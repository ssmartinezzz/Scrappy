import { act, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import CategoryPicksView from '@/components/CategoryPicksView';
import { fetchMejoresPicks } from '@/api';

vi.mock('@/api', async importOriginal => ({
  ...(await importOriginal()),
  fetchMejoresPicks: vi.fn(),
}));

const pick = (n, tipo = 'top') => ({
  tipo, label: 'Buena compra', nombre: `Producto ${n}`, precio: 1000 + n, url: `https://t/${n}`, img: '',
});

const cat = { categoria: 'Remera', count: 60, rubro: 'indumentaria', mediana: 1000, picks: [pick(1, 'valor'), pick(2)] };

let observers;
beforeEach(() => {
  vi.clearAllMocks();
  observers = [];
  window.matchMedia = vi.fn().mockImplementation(query => ({
    matches: true, media: query, addEventListener: vi.fn(), removeEventListener: vi.fn(),
    addListener: vi.fn(), removeListener: vi.fn(),
  }));
  window.IntersectionObserver = class {
    constructor(cb) { this.cb = cb; observers.push(this); }
    observe() {}
    disconnect() { this.gone = true; }
  };
});

const tocarCentinela = async () => {
  const vivo = observers.filter(o => !o.gone).at(-1);
  await act(async () => { vivo.cb([{ isIntersecting: true }]); });
};

describe('CategoryPicksView — every product of the category', () => {
  it('loads the next keyset page when the sentinel shows, passing the cursor and the filters, deduped by url', async () => {
    fetchMejoresPicks
      .mockResolvedValueOnce({ picks: [pick(1), pick(2), pick(3)], nextCursor: 'c1', total: 5 })
      .mockResolvedValueOnce({ picks: [pick(4), pick(5)], nextCursor: null, total: 5 });

    render(<CategoryPicksView cat={cat} onBack={() => {}} onProductClick={() => {}}
                              pickFilters={{ rubro: 'indumentaria', gymrat: false, genero: '' }} />);

    await tocarCentinela();
    await waitFor(() => expect(screen.getByText('Producto 3')).toBeInTheDocument());
    expect(fetchMejoresPicks).toHaveBeenNthCalledWith(1, 'Remera',
      { rubro: 'indumentaria', gymrat: false, genero: '', cursor: null });
    // The highlights (1, 2) are not repeated in the list below the carousel.
    expect(screen.getAllByText('Producto 1')).toHaveLength(1);

    await tocarCentinela();
    await waitFor(() => expect(screen.getByText('Producto 5')).toBeInTheDocument());
    expect(fetchMejoresPicks).toHaveBeenNthCalledWith(2, 'Remera',
      { rubro: 'indumentaria', gymrat: false, genero: '', cursor: 'c1' });
  });

  it('stops asking once nextCursor is null', async () => {
    fetchMejoresPicks.mockResolvedValueOnce({ picks: [pick(3)], nextCursor: null, total: 3 });

    render(<CategoryPicksView cat={cat} onBack={() => {}} onProductClick={() => {}} />);
    await tocarCentinela();
    await waitFor(() => expect(screen.getByText('Producto 3')).toBeInTheDocument());

    expect(observers.filter(o => !o.gone)).toHaveLength(0);
    expect(fetchMejoresPicks).toHaveBeenCalledTimes(1);
  });
});

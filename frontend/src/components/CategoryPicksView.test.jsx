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
  localStorage.clear();
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

describe('CategoryPicksView — carousel / list toggle', () => {
  const renderView = () => render(
    <CategoryPicksView cat={cat} onBack={() => {}} onProductClick={() => {}} />);
  const carrusel = () => screen.queryByRole('region', { name: 'Remera' });

  it('defaults to the carousel and puts every loaded product inside it, not in a list below', async () => {
    fetchMejoresPicks.mockResolvedValueOnce({ picks: [pick(3)], nextCursor: null, total: 3 });
    const { container } = renderView();

    await tocarCentinela();
    expect(await screen.findByText('Producto 3')).toBeInTheDocument();

    expect(carrusel()).toHaveTextContent('Producto 1');
    expect(carrusel()).toHaveTextContent('Producto 3');
    expect(container.querySelector('.picks-grid')).toBeNull();
    expect(screen.getByRole('button', { name: 'Vista carrusel' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('switches to a list holding the highlights and the loaded pages, with no carousel, and remembers it', async () => {
    fetchMejoresPicks.mockResolvedValueOnce({ picks: [pick(3)], nextCursor: null, total: 3 });
    const { container, unmount } = renderView();

    await act(async () => { screen.getByRole('button', { name: 'Vista lista' }).click(); });
    await tocarCentinela();
    expect(await screen.findByText('Producto 3')).toBeInTheDocument();

    expect(carrusel()).toBeNull();
    const grid = container.querySelector('.picks-grid');
    expect(grid).toHaveTextContent('Producto 1');
    expect(grid).toHaveTextContent('Producto 3');
    expect(screen.getAllByText('Producto 1')).toHaveLength(1);

    unmount();
    renderView();
    expect(screen.getByRole('button', { name: 'Vista lista' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('falls back to the carousel when the stored mode is unknown', () => {
    localStorage.setItem('picks:viewMode', 'mosaico');
    renderView();
    expect(screen.getByRole('button', { name: 'Vista carrusel' })).toHaveAttribute('aria-pressed', 'true');
  });
});

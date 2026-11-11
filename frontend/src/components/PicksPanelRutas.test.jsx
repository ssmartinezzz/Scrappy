import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { renderPicks } from '@/test/renderPicks';
import { fetchMejores } from '@/api';

vi.mock('@/api', async importOriginal => ({
  ...(await importOriginal()),
  fetchMejores: vi.fn(),
  fetchMejoresPicks: vi.fn(async () => ({ picks: [], nextCursor: null })),
}));

const cat = (categoria, rubro) => ({
  categoria, count: 3, rubro, imgCat: '', mediana: 1000,
  picks: [{ tipo: 'valor', label: 'Mejor', nombre: `Pick ${categoria}`, precio: 1000, url: `https://t/${categoria}`, img: '' }],
});

const POR_RUBRO = {
  tecnologia: [cat('Monitor', 'tecnologia'), cat('Placa de Video', 'tecnologia')],
  indumentaria: [cat('Remera', 'indumentaria')],
  '': [cat('Monitor', 'tecnologia'), cat('Remera', 'indumentaria')],
};

const ruta = () => screen.getByTestId('ruta').textContent;
const titulo = () => screen.findByRole('heading', { level: 2 });

beforeEach(() => {
  vi.clearAllMocks();
  window.matchMedia = vi.fn().mockImplementation(query => ({
    matches: true, media: query, addEventListener: vi.fn(), removeEventListener: vi.fn(),
    addListener: vi.fn(), removeListener: vi.fn(),
  }));
  window.IntersectionObserver = class { observe() {} disconnect() {} };
  fetchMejores.mockImplementation(async (rubro, opts = {}) => {
    if (opts.gymrat) return [cat(opts.genero === 'mujer' ? 'Calza' : 'Short', '')];
    return POR_RUBRO[rubro] || [];
  });
});

describe('PicksPanel — every step is a path', () => {
  it('walks /picks → /picks/tecnologia → /picks/tecnologia/monitor and Volver goes back one step', async () => {
    const user = userEvent.setup();
    renderPicks('/picks');

    await user.click(await screen.findByRole('button', { name: /Tecnología/ }));
    expect(ruta()).toBe('/picks/tecnologia');

    await user.click(await screen.findByText('Monitor'));
    expect(ruta()).toBe('/picks/tecnologia/monitor');
    expect(await titulo()).toHaveTextContent('Monitor');

    await user.click(screen.getByRole('button', { name: /Volver/ }));
    expect(ruta()).toBe('/picks/tecnologia');
    expect(await screen.findByText('Placa de Video')).toBeInTheDocument();
  });

  it('opens a category cold from its deep link, filtered by its rubro', async () => {
    renderPicks('/picks/tecnologia/placa-de-video');

    expect(await titulo()).toHaveTextContent('Placa de Video');
    expect(fetchMejores).toHaveBeenCalledWith('tecnologia');
  });

  it('opens a rubro gallery cold from /picks/<rubro>', async () => {
    renderPicks('/picks/indumentaria');

    expect(await screen.findByText('Remera')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Indumentaria/ })).toHaveClass('active');
  });

  it('spells the neutral rubro as /picks/todos', async () => {
    const user = userEvent.setup();
    renderPicks('/picks/indumentaria');

    await user.click(await screen.findByRole('button', { name: /Todos/ }));
    expect(ruta()).toBe('/picks/todos');
    expect(await screen.findByText('Monitor')).toBeInTheDocument();
  });

  it('puts the Gymrat gender in the path', async () => {
    const user = userEvent.setup();
    renderPicks('/picks/gymrat');

    await user.click(await screen.findByRole('button', { name: 'Mujer' }));
    expect(ruta()).toBe('/picks/gymrat/mujer');

    await user.click(await screen.findByText('Calza'));
    expect(ruta()).toBe('/picks/gymrat/mujer/calza');
    expect(fetchMejores).toHaveBeenCalledWith('', { gymrat: true, genero: 'mujer' });
  });

  it('redirects an old /picks/<categoria> link to its rubro path', async () => {
    renderPicks('/picks/monitor');

    await waitFor(() => expect(ruta()).toBe('/picks/tecnologia/monitor'));
    expect(await titulo()).toHaveTextContent('Monitor');
  });

  it('says so when the category does not exist in that rubro', async () => {
    renderPicks('/picks/tecnologia/heladera');

    expect(await screen.findByText(/No encontramos esta categoría/)).toBeInTheDocument();
  });
});

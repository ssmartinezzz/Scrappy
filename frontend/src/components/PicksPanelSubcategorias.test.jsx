import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { renderPicks } from '@/test/renderPicks';
import { fetchMejores, fetchMejoresPicks } from '@/api';
import { subChipsDe } from '@/lib/subcategorias';

vi.mock('@/api', async importOriginal => ({
  ...(await importOriginal()),
  fetchMejores: vi.fn(),
  fetchMejoresPicks: vi.fn(),
}));

const pick = (nombre, sub) => ({
  tipo: 'top', label: 'Buena compra', nombre, precio: 1000, url: `https://t/${nombre}`, img: '',
  ...(sub ? { subCategoria: sub } : {}),
});

const almacenamiento = {
  categoria: 'Almacenamiento', count: 4, rubro: 'tecnologia', imgCat: '', mediana: 1000,
  picks: [pick('Disco A', 'ssd'), pick('Disco B', 'hdd'), pick('Disco C', 'ssd'), pick('Disco D')],
};
const teclado = {
  categoria: 'Teclado', count: 2, rubro: 'tecnologia', imgCat: '', mediana: 1000,
  picks: [pick('Teclado A', 'inalámbrico'), pick('Teclado B', 'mecánico')],
};
const gabinete = { categoria: 'Gabinete', count: 1, rubro: 'tecnologia', imgCat: '', mediana: 1000, picks: [pick('Caja A')] };
const remera = { categoria: 'Remera', count: 1, rubro: 'indumentaria', imgCat: '', mediana: 1000, picks: [pick('Remera A')] };

const ruta = () => screen.getByTestId('ruta').textContent;

beforeEach(() => {
  vi.clearAllMocks();
  window.matchMedia = vi.fn().mockImplementation(query => ({
    matches: true, media: query, addEventListener: vi.fn(), removeEventListener: vi.fn(),
    addListener: vi.fn(), removeListener: vi.fn(),
  }));
  window.IntersectionObserver = class {
    constructor(cb) { this.cb = cb; }
    observe() { this.cb([{ isIntersecting: true }]); }
    disconnect() {}
  };
  fetchMejores.mockImplementation(async rubro => {
    if (rubro === 'tecnologia') return [almacenamiento, teclado, gabinete];
    return [remera];
  });
  // Like the backend: the sub filters the whole category before paging, and total counts the filtered list.
  fetchMejoresPicks.mockImplementation(async (categoria, { subCategoria = '' } = {}) => {
    const cat = [almacenamiento, teclado, gabinete, remera].find(c => c.categoria === categoria);
    const picks = (cat?.picks || []).filter(p => !subCategoria || p.subCategoria === subCategoria);
    return { picks, nextCursor: null, total: picks.length };
  });
});

describe('PicksPanel — sub-category level of the URL', () => {
  it('selects the chip and filters from a deep link', async () => {
    renderPicks('/picks/tecnologia/almacenamiento/ssd');

    expect(await screen.findByText('Disco A')).toBeInTheDocument();
    expect(screen.getByText('Disco C')).toBeInTheDocument();
    expect(screen.queryByText('Disco B')).not.toBeInTheDocument();
    expect(screen.queryByText('Disco D')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'SSD' })).toHaveClass('active');
    expect(ruta()).toBe('/picks/tecnologia/almacenamiento/ssd');
  });

  it('asks the server for the deep-linked sub, switches it with the chip and drops it on Todos', async () => {
    const user = userEvent.setup();
    renderPicks('/picks/tecnologia/almacenamiento/hdd');

    expect(await screen.findByText('Disco B')).toBeInTheDocument();
    expect(fetchMejoresPicks).toHaveBeenCalledWith('Almacenamiento', expect.objectContaining({ subCategoria: 'hdd', cursor: null }));
    expect(fetchMejoresPicks).not.toHaveBeenCalledWith('Almacenamiento', expect.objectContaining({ subCategoria: 'ssd' }));
    expect(screen.getByText(/^1 producto ·/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'SSD' }));
    expect(await screen.findByText('Disco A')).toBeInTheDocument();
    expect(fetchMejoresPicks).toHaveBeenCalledWith('Almacenamiento', expect.objectContaining({ subCategoria: 'ssd', cursor: null }));
    expect(screen.queryByText('Disco B')).not.toBeInTheDocument();

    fetchMejoresPicks.mockClear();
    await user.click(screen.getByRole('button', { name: 'Todos' }));
    expect(await screen.findByText('Disco D')).toBeInTheDocument();
    expect(screen.getByText('Disco B')).toBeInTheDocument();
    expect(fetchMejoresPicks).toHaveBeenCalled();
    for (const [, opts] of fetchMejoresPicks.mock.calls) expect(opts.subCategoria || '').toBe('');
  });

  it('navigates when a chip is clicked and Todos goes back to the category URL', async () => {
    const user = userEvent.setup();
    renderPicks('/picks/tecnologia/almacenamiento');

    await user.click(await screen.findByRole('button', { name: 'HDD' }));
    expect(ruta()).toBe('/picks/tecnologia/almacenamiento/hdd');
    await waitFor(() => expect(screen.queryByText('Disco A')).not.toBeInTheDocument());
    expect(screen.getByText('Disco B')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Todos' }));
    expect(ruta()).toBe('/picks/tecnologia/almacenamiento');
    expect(await screen.findByText('Disco D')).toBeInTheDocument();
  });

  it('round-trips an accented sub-category through its slug', async () => {
    const user = userEvent.setup();
    renderPicks('/picks/tecnologia/teclado');

    await user.click(await screen.findByRole('button', { name: 'Inalámbrico' }));
    expect(ruta()).toBe('/picks/tecnologia/teclado/inalambrico');
    expect(screen.getByText('Teclado A')).toBeInTheDocument();
    expect(screen.queryByText('Teclado B')).not.toBeInTheDocument();
  });

  it('opens an accented sub cold from its slug', async () => {
    renderPicks('/picks/tecnologia/teclado/inalambrico');

    expect(await screen.findByText('Teclado A')).toBeInTheDocument();
    expect(screen.queryByText('Teclado B')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Inalámbrico' })).toHaveClass('active');
  });

  it('replaces an unknown sub slug with the category URL and shows everything', async () => {
    renderPicks('/picks/tecnologia/almacenamiento/zzz');

    await waitFor(() => expect(ruta()).toBe('/picks/tecnologia/almacenamiento'));
    expect(await screen.findByText('Disco D')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Todos' })).toHaveClass('active');
  });

  it('drops a sub slug on a category without chips', async () => {
    renderPicks('/picks/tecnologia/gabinete/ssd');

    await waitFor(() => expect(ruta()).toBe('/picks/tecnologia/gabinete'));
    expect(await screen.findByText('Caja A')).toBeInTheDocument();
  });

  it('leaves gymrat routes without a sub level', async () => {
    renderPicks('/picks/gymrat/mujer/remera');

    expect(await screen.findByRole('heading', { name: 'Remera' })).toBeInTheDocument();
    expect(ruta()).toBe('/picks/gymrat/mujer/remera');
    expect(screen.queryByRole('group', { name: 'Subcategoría' })).not.toBeInTheDocument();
  });

  it('has chips for every technology category with sub-categories', () => {
    const esperado = {
      CPU: 6, Motherboard: 6, RAM: 4, GPU: 3, Auricular: 4, PC: 2, Almacenamiento: 5, Teclado: 3, Mouse: 2,
      Cooler: 3, Fuente: 2, Notebook: 1, Monitor: 3, Joystick: 2, Networking: 8, Cable: 3, 'Impresión': 3, 'Cámara': 3, Reloj: 4,
    };
    for (const [categoria, n] of Object.entries(esperado)) {
      expect(subChipsDe('tecnologia', categoria), categoria).toHaveLength(n);
    }
    expect(subChipsDe('tecnologia', 'Fuente').map(c => c.value)).toEqual(['80 plus', 'modular']);
  });

  it('offers the older Intel sockets, DDR3 and the office headsets', () => {
    for (const categoria of ['CPU', 'Motherboard']) {
      const chips = subChipsDe('tecnologia', categoria);
      expect(chips.map(c => c.value), categoria).toEqual(expect.arrayContaining(['lga1200', 'lga1151']));
      expect(chips.find(c => c.value === 'lga1200').label).toBe('LGA1200');
      expect(chips.find(c => c.value === 'lga1151').label).toBe('LGA1151');
    }
    expect(subChipsDe('tecnologia', 'RAM').find(c => c.value === 'ddr3').label).toBe('DDR3');
    expect(subChipsDe('tecnologia', 'Auricular').find(c => c.value === 'oficina').label).toBe('Oficina');
  });
});

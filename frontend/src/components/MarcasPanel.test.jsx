import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import MarcasPanel from '@/components/MarcasPanel';
import { fetchData, fetchMarcasBrowser } from '@/api';

vi.mock('@/api', async importOriginal => ({
  ...(await importOriginal()),
  fetchData: vi.fn(),
  fetchMarcasBrowser: vi.fn(),
}));

const prod = n => ({ url: `https://t/${n}`, nombre: `Producto ${n}`, precio: 1000 + n, img: '' });
const marca = { marca: 'Nike', count: 100, rubro: 'indumentaria', img: '', mediana: 1000, precioMin: 1, precioMax: 2, topCats: 'Remera', bestPick: null };

let observers;
beforeEach(() => {
  vi.clearAllMocks();
  observers = [];
  window.IntersectionObserver = class {
    constructor(cb) { this.cb = cb; observers.push(this); }
    observe() {}
    disconnect() { this.gone = true; }
  };
  fetchMarcasBrowser.mockResolvedValue([marca]);
});

const tocarCentinela = async () => {
  const vivo = observers.filter(o => !o.gone).at(-1);
  await act(async () => { vivo.cb([{ isIntersecting: true }]); });
};

describe('MarcasPanel — a brand shows ALL its products', () => {
  it('keeps loading keyset pages with the cursor while the backend returns nextCursor', async () => {
    fetchData
      .mockResolvedValueOnce({ productos: [prod(1), prod(2)], nextCursor: 'c1', meta: {} })
      .mockResolvedValueOnce({ productos: [prod(2), prod(3)], nextCursor: null, meta: {} });
    const user = userEvent.setup();
    render(<MarcasPanel onProductClick={() => {}} />);

    await user.click(await screen.findByText('Nike'));
    await screen.findByText('Producto 2');
    expect(fetchData).toHaveBeenNthCalledWith(1, { marca: 'Nike', size: 48, page: 0, orden: 'precio_asc' });

    await tocarCentinela();
    await waitFor(() => expect(screen.getByText('Producto 3')).toBeInTheDocument());
    expect(fetchData).toHaveBeenNthCalledWith(2, { marca: 'Nike', size: 48, orden: 'precio_asc', cursor: 'c1' });
    // Producto 2 came twice (page boundary overlap) and is shown once.
    expect(screen.getAllByText('Producto 2')).toHaveLength(1);
    expect(observers.filter(o => !o.gone)).toHaveLength(0);
  });
});

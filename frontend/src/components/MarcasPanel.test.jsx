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
  fetchMarcasBrowser.mockResolvedValue({ marcas: [marca], nextCursor: null, total: 1 });
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

const nombrada = n => ({ ...marca, marca: n });

describe('MarcasPanel — the brand grid pages by keyset', () => {
  it('starts A-Z, offers A-Z first, and asks for the first page without a cursor', async () => {
    render(<MarcasPanel onProductClick={() => {}} />);

    await screen.findByText('Nike');
    expect(fetchMarcasBrowser).toHaveBeenCalledTimes(1);
    expect(fetchMarcasBrowser).toHaveBeenCalledWith(expect.objectContaining({ sort: 'nombre', rubro: '', q: '' }));
    expect(fetchMarcasBrowser.mock.calls[0][0].cursor).toBeFalsy();
    const opciones = screen.getAllByRole('option').map(o => o.textContent);
    expect(opciones[0]).toBe('A–Z');
    expect(screen.getByRole('combobox')).toHaveValue('nombre');
  });

  it('loads the next page with the cursor when the sentinel shows, without repeating brands', async () => {
    fetchMarcasBrowser
      .mockResolvedValueOnce({ marcas: [nombrada('Adidas'), nombrada('Bata')], nextCursor: 'c1', total: 3 })
      .mockResolvedValueOnce({ marcas: [nombrada('Bata'), nombrada('Zara')], nextCursor: null, total: 3 });
    render(<MarcasPanel onProductClick={() => {}} />);

    await screen.findByText('Bata');
    await tocarCentinela();

    await screen.findByText('Zara');
    expect(fetchMarcasBrowser).toHaveBeenLastCalledWith(expect.objectContaining({ sort: 'nombre', cursor: 'c1' }));
    expect(screen.getAllByText('Bata')).toHaveLength(1);
    expect(observers.filter(o => !o.gone)).toHaveLength(0);
  });

  it('resets the list and drops the cursor when the sort changes', async () => {
    fetchMarcasBrowser
      .mockResolvedValueOnce({ marcas: [nombrada('Adidas')], nextCursor: 'c1', total: 2 })
      .mockResolvedValueOnce({ marcas: [nombrada('Puma')], nextCursor: null, total: 1 });
    const user = userEvent.setup();
    render(<MarcasPanel onProductClick={() => {}} />);
    await screen.findByText('Adidas');

    await user.selectOptions(screen.getByRole('combobox'), 'precio_asc');

    await screen.findByText('Puma');
    expect(screen.queryByText('Adidas')).not.toBeInTheDocument();
    const ultima = fetchMarcasBrowser.mock.calls.at(-1)[0];
    expect(ultima.sort).toBe('precio_asc');
    expect(ultima.cursor).toBeFalsy();
  });

  it('ignores a response that arrives after the filters changed', async () => {
    let resolverVieja;
    fetchMarcasBrowser
      .mockImplementationOnce(() => new Promise(res => { resolverVieja = res; }))
      .mockResolvedValueOnce({ marcas: [nombrada('Nueva')], nextCursor: null, total: 1 });
    const user = userEvent.setup();
    render(<MarcasPanel onProductClick={() => {}} />);
    await user.selectOptions(screen.getByRole('combobox'), 'count');
    await screen.findByText('Nueva');

    await act(async () => { resolverVieja({ marcas: [nombrada('Vieja')], nextCursor: null, total: 1 }); });

    expect(screen.queryByText('Vieja')).not.toBeInTheDocument();
    expect(screen.getByText('Nueva')).toBeInTheDocument();
  });
});

import { render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, fetchProductoDetalle: vi.fn(), fetchTendencias: vi.fn(), fetchComparado: vi.fn() };
});

import PriceHistoryPage from '@/components/PriceHistoryPage';
import { fetchComparado, fetchProductoDetalle, fetchTendencias } from '@/api';

const KEY = 'a1b2c3d4e5f60718';

function renderPage() {
  return render(
    <MemoryRouter initialEntries={[`/historial/${KEY}`]}>
      <Routes><Route path="/historial/:key" element={<PriceHistoryPage/>}/></Routes>
    </MemoryRouter>
  );
}

const detalle = {
  producto: { url: 'https://freres.com/r', nombre: 'Remera Negra', sitio: 'Freres', precio: 15990, categoria: 'Remeras' },
  historial: { puntos: [{ fecha: '2026-05-20', precio: 20000 }, { fecha: '2026-06-04', precio: 15990 }] },
};

const hermano = (sitio, precio, stock = 'en_stock') => ({
  sitio, url: `https://${sitio.toLowerCase()}.com/r`, key: `k-${sitio}`, precio, img: null, stock,
  historial: { puntos: [{ fecha: '2026-05-22', precio: precio + 1000 }, { fecha: '2026-06-03', precio }] },
});

describe('PriceHistoryPage comparado', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchTendencias.mockResolvedValue({ state: 'empty', data: null });
    fetchProductoDetalle.mockResolvedValue(detalle);
  });

  it('sin hermanos: sin leyenda de tiendas, el gráfico de siempre', async () => {
    fetchComparado.mockResolvedValue([]);
    const { container } = renderPage();

    await screen.findByText('Mínimo');
    await waitFor(() => expect(fetchComparado).toHaveBeenCalledWith(KEY));
    expect(screen.queryByRole('list', { name: /tiendas/i })).not.toBeInTheDocument();
    expect(container.querySelector('.recharts-wrapper')).toBeInTheDocument();
  });

  it('con hermanos: una entrada de leyenda por tienda con su precio actual', async () => {
    fetchComparado.mockResolvedValue([hermano('Dexter', 17000), hermano('Moov', 16500)]);
    renderPage();

    const leyenda = await screen.findByRole('list', { name: /tiendas/i });
    const items = within(leyenda).getAllByRole('listitem');
    expect(items).toHaveLength(3);
    expect(within(leyenda).getByText(/Freres/)).toBeInTheDocument();
    expect(within(leyenda).getByText(/Dexter/)).toBeInTheDocument();
    expect(within(leyenda).getByText(/\$17\.000/)).toBeInTheDocument();
  });

  it('resalta la más barata con stock', async () => {
    fetchComparado.mockResolvedValue([hermano('Dexter', 17000), hermano('Moov', 12000)]);
    renderPage();

    const leyenda = await screen.findByRole('list', { name: /tiendas/i });
    const baratas = within(leyenda).getAllByText('más barato');
    expect(baratas).toHaveLength(1);
    expect(baratas[0].closest('li')).toHaveTextContent('Moov');
  });

  it('una tienda sin stock se rotula y nunca es la más barata', async () => {
    fetchComparado.mockResolvedValue([hermano('Dexter', 1000, 'sin_stock'), hermano('Moov', 17000)]);
    renderPage();

    const leyenda = await screen.findByRole('list', { name: /tiendas/i });
    const dexter = within(leyenda).getByText(/Dexter/).closest('li');
    expect(dexter).toHaveTextContent('sin stock');
    expect(dexter).not.toHaveTextContent('más barato');
    expect(within(leyenda).getByText('más barato').closest('li')).toHaveTextContent('Freres');
  });

  it('el producto actual sin stock se rotula y no es el más barato', async () => {
    fetchProductoDetalle.mockResolvedValue({ ...detalle, producto: { ...detalle.producto, precio: 1000, stock: 'sin_stock' } });
    fetchComparado.mockResolvedValue([hermano('Moov', 17000)]);
    renderPage();

    const leyenda = await screen.findByRole('list', { name: /tiendas/i });
    const freres = within(leyenda).getByText(/Freres/).closest('li');
    expect(freres).toHaveTextContent('sin stock');
    expect(within(leyenda).getByText('más barato').closest('li')).toHaveTextContent('Moov');
  });

  it('cada entrada de otra tienda abre su listado en pestaña nueva', async () => {
    fetchComparado.mockResolvedValue([hermano('Dexter', 17000)]);
    renderPage();

    const link = await screen.findByRole('link', { name: /Dexter/ });
    expect(link).toHaveAttribute('href', 'https://dexter.com/r');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('si el comparado falla, queda el gráfico de siempre sin error', async () => {
    fetchComparado.mockRejectedValue(new Error('boom'));
    const { container } = renderPage();

    expect(await screen.findByText('Mínimo')).toBeInTheDocument();
    await waitFor(() => expect(fetchComparado).toHaveBeenCalled());
    expect(screen.queryByRole('list', { name: /tiendas/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/error/i)).not.toBeInTheDocument();
    expect(container.querySelector('.recharts-wrapper')).toBeInTheDocument();
  });

  it('mientras el comparado está pendiente se ve el gráfico de siempre', async () => {
    fetchComparado.mockReturnValue(new Promise(() => {}));
    const { container } = renderPage();

    expect(await screen.findByText('Mínimo')).toBeInTheDocument();
    expect(container.querySelector('.recharts-wrapper')).toBeInTheDocument();
  });
});

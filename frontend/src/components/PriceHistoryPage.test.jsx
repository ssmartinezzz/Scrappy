import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi, beforeEach } from 'vitest';

vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, fetchProductoDetalle: vi.fn() };
});

import PriceHistoryPage from '@/components/PriceHistoryPage';
import { fetchProductoDetalle } from '@/api';

const URL_PROD = 'https://site.com/remera-negra';
// 16 hex — el mismo largo que emite la columna generada de V25.
const KEY = 'a1b2c3d4e5f60718';

function renderPage(path = `/historial/${KEY}`) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/historial/:key" element={<PriceHistoryPage/>}/>
        <Route path="/historial"      element={<PriceHistoryPage/>}/>
      </Routes>
    </MemoryRouter>
  );
}

const producto = {
  url: URL_PROD, nombre: 'Remera Negra', sitio: 'Freres',
  precio: 15990, marca: 'Nike', img: 'https://img.example/r.jpg',
};

describe('PriceHistoryPage', () => {
  beforeEach(() => vi.clearAllMocks());

  it('pide el detalle por el handle corto de la ruta, no por la url', async () => {
    fetchProductoDetalle.mockResolvedValue({ producto, historial: { puntos: [] } });

    renderPage();

    await waitFor(() => expect(fetchProductoDetalle).toHaveBeenCalledWith(KEY));
    expect(fetchProductoDetalle).not.toHaveBeenCalledWith(URL_PROD);
  });

  it('con serie: muestra producto, stats y el gráfico', async () => {
    fetchProductoDetalle.mockResolvedValue({
      producto,
      historial: {
        puntos: [
          { fecha: '2026-05-20', precio: 20000 },
          { fecha: '2026-05-28', precio: 18000 },
          { fecha: '2026-06-04', precio: 16000 },
        ],
        min: 16000, max: 20000, avg: 18000, deltaPct: -20,
      },
    });

    const { container } = renderPage();

    expect(await screen.findByText('Remera Negra')).toBeInTheDocument();
    expect(screen.getByText('Mínimo')).toBeInTheDocument();
    expect(screen.getByText('-20%')).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();           // registros
    await waitFor(() => expect(container.querySelector('.recharts-wrapper')).toBeInTheDocument());
  });

  it('un producto sin historial IGUAL renderiza, con su explicación', async () => {
    // El contrato que separa /api/producto de /api/historial: este endpoint no
    // responde 204, porque la página tiene que mostrar el producto igual.
    fetchProductoDetalle.mockResolvedValue({ producto, historial: { puntos: [] } });

    renderPage();

    expect(await screen.findByText('Remera Negra')).toBeInTheDocument();
    expect(screen.getByText(/Sin historial suficiente/)).toBeInTheDocument();
    expect(screen.queryByText('Mínimo')).not.toBeInTheDocument();
  });

  it('un solo punto tampoco dibuja una serie', async () => {
    fetchProductoDetalle.mockResolvedValue({
      producto, historial: { puntos: [{ fecha: '2026-05-20', precio: 20000 }] },
    });

    renderPage();

    expect(await screen.findByText(/Sin historial suficiente/)).toBeInTheDocument();
  });

  it('404 del backend: mensaje y vuelta al catálogo, sin romper', async () => {
    fetchProductoDetalle.mockResolvedValue(null);

    renderPage();

    expect(await screen.findByText(/No encontramos ese producto/)).toBeInTheDocument();
    expect(screen.getByText(/Volver al catálogo/)).toBeInTheDocument();
  });

  it('sin handle en la ruta no pide nada', async () => {
    renderPage('/historial');

    await waitFor(() => expect(screen.getByText(/No encontramos ese producto/)).toBeInTheDocument());
    expect(fetchProductoDetalle).not.toHaveBeenCalled();
  });
});

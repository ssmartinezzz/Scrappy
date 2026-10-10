import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi, beforeEach } from 'vitest';

vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, fetchProductoDetalle: vi.fn(), fetchTendencias: vi.fn() };
});

import PriceHistoryPage from '@/components/PriceHistoryPage';
import { fetchProductoDetalle, fetchTendencias } from '@/api';

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
  categoria: 'Remeras', cantidadUnidades: 1,
};

describe('PriceHistoryPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchTendencias.mockResolvedValue({ state: 'empty', data: null });
  });

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

    expect(await screen.findByText(/No encontramos ese producto/)).toBeInTheDocument();
    expect(fetchProductoDetalle).not.toHaveBeenCalled();
  });

  describe('rangos', () => {
    const conSerie = {
      producto,
      historial: {
        puntos: [
          { fecha: '2026-05-20', precio: 20000 },
          { fecha: '2026-05-28', precio: 18000 },
          { fecha: '2026-06-04', precio: 16000 },
        ],
      },
    };

    beforeEach(() => {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date(2026, 5, 6, 12));
    });
    afterEach(() => vi.useRealTimers());

    it('ofrece 1D a Todo y arranca en Todo', async () => {
      fetchProductoDetalle.mockResolvedValue(conSerie);

      renderPage();

      await screen.findByText('Remera Negra');
      for (const r of ['1D', '1S', '1M', '3M', '6M', '1A']) {
        expect(screen.getByRole('button', { name: r })).toHaveAttribute('aria-pressed', 'false');
      }
      expect(screen.getByRole('button', { name: 'Todo' })).toHaveAttribute('aria-pressed', 'true');
    });

    it('elegir un rango re-acota las stats a esa ventana', async () => {
      fetchProductoDetalle.mockResolvedValue(conSerie);

      renderPage();
      await screen.findByText('-20%');

      fireEvent.click(screen.getByRole('button', { name: '1S' }));

      // 30 may → 6 jun: abre en 18000 (arrastrado del 28) y cierra en 16000.
      expect(screen.getByRole('button', { name: '1S' })).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByText('-11.1%')).toBeInTheDocument();
      expect(screen.queryByText('-20%')).not.toBeInTheDocument();
    });
  });

  describe('contexto de la categoría', () => {
    const conSerie = {
      producto,
      historial: { puntos: [{ fecha: '2026-05-20', precio: 20000 }, { fecha: '2026-06-04', precio: 15990 }] },
    };
    const tendencias = mediana => ({
      state: 'ok',
      data: { distribucionCategorias: { Remeras: { n: 80, median: mediana, q1: mediana * 0.8, q3: mediana * 1.2 } } },
    });

    it('compara el precio actual contra la mediana de su categoría', async () => {
      fetchProductoDetalle.mockResolvedValue(conSerie);
      fetchTendencias.mockResolvedValue(tendencias(32000));

      renderPage();

      expect(await screen.findByText('vs. mediana Remeras')).toBeInTheDocument();
      expect(screen.getByText('-50%')).toBeInTheDocument();
    });

    it('una mediana lejana se avisa en vez de aplastar la serie', async () => {
      fetchProductoDetalle.mockResolvedValue(conSerie);
      fetchTendencias.mockResolvedValue(tendencias(900000));

      renderPage();

      expect(await screen.findByText(/fuera de escala/)).toBeInTheDocument();
    });

    it('sin stats de la categoría se abstiene y la página igual anda', async () => {
      fetchProductoDetalle.mockResolvedValue(conSerie);
      fetchTendencias.mockResolvedValue({ state: 'failed', data: null });

      renderPage();

      expect(await screen.findByText('Promedio')).toBeInTheDocument();
      expect(screen.queryByText(/mediana/)).not.toBeInTheDocument();
    });
  });
});

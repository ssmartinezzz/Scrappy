import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';

import DetailPanel from '@/components/DetailPanel';
import { fetchHistorial } from '@/api';

// close-1nf-and-3nf-foundation (design DD6/V16): /api/tendencias emits
// distribucionCategorias keyed by the CANONICAL categoria (Title Case,
// "Remera"), not norm_cat's lowercase output ("remera") — catStats lookups
// must use the raw categoria directly.
vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, fetchHistorial: vi.fn() };
});

describe('DetailPanel — catStats se busca por categoria canónica, no por normCat', () => {
  beforeEach(() => {
    fetchHistorial.mockResolvedValue(null);
  });

  it('el box plot de distribución aparece cuando catStats está keyeado con la categoria tal cual llega del backend', () => {
    const product = {
      url: 'https://tienda.test/p/1',
      nombre: 'Remera Oversize',
      sitio: 'Freres',
      precio: 20000,
      categoria: 'Remera',
      talles: [],
      cantidadUnidades: 1,
    };
    const catStats = { Remera: { q1: 10000, median: 20000, q3: 30000, mean: 21000, mode: 19000, cv: 15, fence_low: 0, fence_high: 50000 } };

    render(<DetailPanel product={product} catStats={catStats} onClose={vi.fn()} />);

    // "Distribución en {categoria}" y el bloque Mediana/Media/Moda/CV están
    // gateados por `st` truthy — sólo aparecen si la búsqueda en catStats
    // encontró algo.
    expect(screen.getByText('Distribución en Remera')).toBeInTheDocument();
  });

  it('NO aparece el box plot cuando catStats no tiene la categoria (sin datos, no crashea)', () => {
    const product = {
      url: 'https://tienda.test/p/2',
      nombre: 'Zapatilla Running',
      sitio: 'Freres',
      precio: 20000,
      categoria: 'Zapatilla Running',
      talles: [],
      cantidadUnidades: 1,
    };

    render(<DetailPanel product={product} catStats={{}} onClose={vi.fn()} />);

    expect(screen.queryByText(/^Distribución en/)).not.toBeInTheDocument();
  });
});

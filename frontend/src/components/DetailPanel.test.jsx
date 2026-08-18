import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi, beforeEach } from 'vitest';

import DetailPanel from '@/components/DetailPanel';
import { fetchHistorial } from '@/api';

// close-1nf-and-3nf-foundation (design DD6/V16): /api/tendencias emits
// distribucionCategorias keyed by the CANONICAL categoria (Title Case,
// "Remera"), not norm_cat's lowercase output ("remera") — catStats lookups
// must use the raw categoria directly.
// DetailPanel linkea a /historial?url= con un <Link>, asi que necesita contexto
// de Router. No es ceremonia del test: es la dependencia nueva del componente.
function renderPanel(product, catStats) {
  return render(
    <MemoryRouter>
      <DetailPanel product={product} catStats={catStats} onClose={vi.fn()} />
    </MemoryRouter>
  );
}

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

    renderPanel(product, catStats);

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

    renderPanel(product, {});

    expect(screen.queryByText(/^Distribución en/)).not.toBeInTheDocument();
  });
});

describe('DetailPanel — link a la vista de historial', () => {
  const base = {
    url: 'https://tienda.test/p/1',
    nombre: 'Remera Oversize',
    sitio: 'Freres',
    precio: 20000,
    categoria: 'Remera',
    talles: [],
    cantidadUnidades: 1,
  };

  beforeEach(() => {
    fetchHistorial.mockResolvedValue(null);
  });

  it('linkea a /historial con el handle corto, no con la url', () => {
    renderPanel({ ...base, key: 'a1b2c3d4e5f60718', url: 'https://site.com/a b?x=1&y=2' }, {});

    const link = screen.getByRole('link', { name: /Ver historial completo/ });
    // Corta y estable: nada de la url percent-encodeada entra en la ruta.
    expect(link).toHaveAttribute('href', '/historial/a1b2c3d4e5f60718');
    expect(link.getAttribute('href')).not.toContain('site.com');
  });

  it('sin handle no ofrece el link', () => {
    // Una fila vieja de un cliente cacheado puede no traer `key` todavía; el
    // panel no ofrece un link roto por eso.
    renderPanel({ ...base, key: undefined }, {});

    expect(screen.queryByRole('link', { name: /Ver historial completo/ })).not.toBeInTheDocument();
  });
});

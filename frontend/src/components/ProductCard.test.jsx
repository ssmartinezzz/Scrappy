import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import ProductCard from '@/components/ProductCard';

// close-1nf-and-3nf-foundation (D1/DD7): precioOrig comes off the wire as a
// JSON number or null now (ProductJson/CatalogoEndpoints/etc. no longer emit
// a raw scraped string) — the frontend's own regex parser is deleted, not
// kept as a third disagreeing implementation alongside PrecioParser (Java)
// and sp_parse_precio_ar (SQL).
const baseProduct = {
  url: 'https://tienda.test/p/1',
  nombre: 'Remera Oversize',
  sitio: 'Freres',
  precio: 30000,
  precioOrig: null,
  categoria: 'Remera',
  talles: [],
  cantidadUnidades: 1,
};

function renderCard(overrides = {}) {
  return render(
    <ProductCard
      product={{ ...baseProduct, ...overrides }}
      catStats={{}}
      isInComparar={false}
      isFavorito={false}
      onOpenDetail={vi.fn()}
      onToggleComparar={vi.fn()}
      onToggleFavorito={vi.fn()}
    />
  );
}

describe('ProductCard — precioOrig numérico', () => {
  it('renderiza el precio original a partir de un NÚMERO, sin parsearlo como string', () => {
    // 3 dígitos decimales exactos: el viejo parser regex de la card
    // interpretaba un punto seguido de 3+ dígitos como separador de miles
    // AR y lo mangleaba (1234.567 -> 1234567). Un número ya no pasa por
    // ningún parser acá — sólo por fmt().
    renderCard({ precioOrig: 1234.567 });

    expect(screen.getByText('ARS $1.235')).toBeInTheDocument();
    expect(screen.queryByText(/1\.234\.567/)).not.toBeInTheDocument();
  });

  it('no renderiza precio original cuando precioOrig es null', () => {
    renderCard({ precioOrig: null });

    expect(screen.queryByText(/^ARS \$/)).toBeInTheDocument(); // el precio actual sigue ahí
    const origEl = document.querySelector('.card-price-orig');
    expect(origEl).toBeNull();
  });

  it('renderiza un precio original entero limpio', () => {
    renderCard({ precioOrig: 45000 });

    const origEl = document.querySelector('.card-price-orig');
    expect(origEl).not.toBeNull();
    expect(origEl.textContent).toBe('ARS $45.000');
  });
});

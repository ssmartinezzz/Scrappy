import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ProductCard from '@/components/ProductCard';

const base = { url: 'https://t.test/p/1', nombre: 'Remera', sitio: 'Freres', precio: 30000, precioOrig: null,
  categoria: 'Remera', talles: [], cantidadUnidades: 1 };

function renderCard(o = {}) {
  return render(<ProductCard product={{ ...base, ...o }} catStats={{}} isInComparar={false} isFavorito={false}
    onOpenDetail={vi.fn()} onToggleComparar={vi.fn()} onToggleFavorito={vi.fn()} />);
}

describe('ProductCard — promos de pago', () => {
  it('muestra el badge de la mejor promo y la fila de medios, dentro del footer de badges', () => {
    renderCard({
      medios: ['Mercado Pago', 'Transferencia'],
      mejorPromo: { medio: 'Transferencia', red: null, tipo: 'descuento', cuotas: null, pct: 15 },
    });
    const footer = document.querySelector('.card-badges-footer');
    expect(footer).toContainElement(screen.getByText('15% Transferencia'));
    expect(footer).toContainElement(screen.getByRole('img', { name: 'Mercado Pago' }));
  });

  it('sin promos la card no cambia: ni badge ni iconos', () => {
    renderCard({ medios: [] });
    expect(document.querySelector('.badge-promo')).toBeNull();
    expect(screen.queryByRole('img', { name: 'Transferencia' })).toBeNull();
  });

  it('medios sin mejorPromo (sólo recargos): iconos pero ningún badge', () => {
    renderCard({ medios: ['Visa'] });
    expect(document.querySelector('.badge-promo')).toBeNull();
    expect(screen.getByRole('img', { name: 'Visa' })).toBeInTheDocument();
  });
});

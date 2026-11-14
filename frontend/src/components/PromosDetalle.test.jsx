import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import PromosDetalle from './PromosDetalle';

const promo = (o) => ({
  sitio: 'Tienda', medio: { nombre: 'Santander', tipo: 'banco' }, red: { nombre: 'Visa' },
  tipo: 'cuotas_sin_interes', cuotas: 12, pct: null, alcance: 'sitio',
  vigenciaDesde: null, vigenciaHasta: null, textoFuente: 't', vistoAt: '2026-11-10T10:00:00Z', ...o,
});

describe('PromosDetalle', () => {
  it('sin promos no hay sección', () => {
    const { container } = render(<PromosDetalle promos={[]} />);
    expect(container).toBeEmptyDOMElement();
    const b = render(<PromosDetalle />);
    expect(b.container).toBeEmptyDOMElement();
  });

  it('lista cada promo con icono, texto, red y alcance', () => {
    render(<PromosDetalle promos={[
      promo({}),
      promo({ medio: { nombre: 'Transferencia', tipo: 'medio' }, red: null, tipo: 'descuento', cuotas: null, pct: 15, alcance: 'producto' }),
    ]} />);
    expect(screen.getByText('Promos y medios de pago')).toBeInTheDocument();
    const items = screen.getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(within(items[0]).getByText('12 cuotas sin interés')).toBeInTheDocument();
    expect(within(items[0]).getByText('Santander Visa')).toBeInTheDocument();
    expect(within(items[0]).getByText('Todo el sitio')).toBeInTheDocument();
    expect(within(items[0]).getByRole('img', { name: 'Santander' })).toBeInTheDocument();
    expect(within(items[1]).getByText('15% de descuento')).toBeInTheDocument();
    expect(within(items[1]).getByText('Transferencia', { selector: '.promo-item-sub' })).toBeInTheDocument();
    expect(within(items[1]).getByText('Este producto')).toBeInTheDocument();
  });

  it('muestra la vigencia sólo cuando existe', () => {
    render(<PromosDetalle promos={[
      promo({ vigenciaHasta: '2026-11-30' }),
      promo({ medio: { nombre: 'Galicia', tipo: 'banco' }, red: null }),
    ]} />);
    const items = screen.getAllByRole('listitem');
    expect(within(items[0]).getByText('Hasta el 30/11')).toBeInTheDocument();
    expect(within(items[1]).queryByText(/Hasta|Desde|Del /)).not.toBeInTheDocument();
  });
});

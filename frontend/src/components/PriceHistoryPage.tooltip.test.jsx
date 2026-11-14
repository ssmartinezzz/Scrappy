import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { TooltipPrecio } from '@/components/PriceHistoryPage';

const D1 = Date.UTC(2026, 10, 1);
const D2 = Date.UTC(2026, 10, 2);
const fila = { t: D2, vigente: 15990, precio: 15990, real: true };
const payload = [{ payload: fila }];

describe('TooltipPrecio', () => {
  it('sin otras tiendas muestra sólo el precio vigente, como siempre', () => {
    render(<TooltipPrecio active payload={payload}/>);
    expect(screen.getByText('ARS $15.990')).toBeInTheDocument();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
  });

  it('con otras tiendas lista el precio de cada una en el día señalado', () => {
    const extras = [
      { id: 'h0', sitio: 'Dexter', color: '#000', puntos: [{ t: D1, precio: 17000 }] },
      { id: 'h1', sitio: 'Moov', color: '#111', puntos: [{ t: D1, precio: 18000 }, { t: D2, precio: 16500 }] },
    ];
    render(<TooltipPrecio active payload={payload} actual={{ sitio: 'Freres' }} extras={extras}/>);
    const items = screen.getAllByRole('listitem');
    expect(items).toHaveLength(3);
    expect(items[0]).toHaveTextContent('Freres');
    expect(items[0]).toHaveTextContent('$15.990');
    expect(items[1]).toHaveTextContent('Dexter');
    expect(items[1]).toHaveTextContent('$17.000');
    expect(items[2]).toHaveTextContent('Moov');
    expect(items[2]).toHaveTextContent('$16.500');
  });

  it('una tienda que todavía no tenía precio ese día no aparece', () => {
    const extras = [{ id: 'h0', sitio: 'Dexter', color: '#000', puntos: [{ t: D2 + 864e5, precio: 17000 }] }];
    render(<TooltipPrecio active payload={payload} actual={{ sitio: 'Freres' }} extras={extras}/>);
    expect(screen.queryByText(/Dexter/)).not.toBeInTheDocument();
  });
});

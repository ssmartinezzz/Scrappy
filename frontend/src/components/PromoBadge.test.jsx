import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import PromoBadge, { MediosPago } from './PromoBadge';

describe('PromoBadge', () => {
  it('muestra la mejor promo con su texto corto', () => {
    render(<PromoBadge product={{ mejorPromo: { medio: 'Transferencia', red: null, tipo: 'descuento', cuotas: null, pct: 15 } }} />);
    expect(screen.getByText('15% Transferencia')).toBeInTheDocument();
  });
  it('sin mejorPromo no pinta nada', () => {
    const { container } = render(<PromoBadge product={{ medios: ['Visa'] }} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('MediosPago', () => {
  it('conserva el orden del servidor, un icono por medio', () => {
    render(<MediosPago medios={['Mercado Pago', 'Transferencia', 'Santander']} />);
    const imgs = screen.getAllByRole('img').map(n => n.getAttribute('aria-label'));
    expect(imgs).toEqual(['Mercado Pago', 'Transferencia', 'Santander']);
  });
  it('capea en 4 y suma "+N" con el resto en el title', () => {
    render(<MediosPago medios={['Mercado Pago', 'Transferencia', 'Santander', 'Galicia', 'Macro', 'BBVA']} />);
    expect(screen.getAllByRole('img')).toHaveLength(4);
    const mas = screen.getByText('+2');
    expect(mas).toHaveAttribute('title', 'Macro, BBVA');
  });
  it('exactamente 4 no muestra "+N"', () => {
    render(<MediosPago medios={['Visa', 'Mastercard', 'Cabal', 'Modo']} />);
    expect(screen.queryByText(/^\+/)).not.toBeInTheDocument();
  });
  it('vacío o ausente: nada', () => {
    const a = render(<MediosPago medios={[]} />);
    expect(a.container).toBeEmptyDOMElement();
    const b = render(<MediosPago />);
    expect(b.container).toBeEmptyDOMElement();
  });
});

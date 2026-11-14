import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import MedioPagoIcon from './MedioPagoIcon';

const CANONICOS = [
  'Mercado Pago', 'Transferencia', 'Santander', 'Galicia', 'Macro', 'BBVA', 'Nación', 'Provincia',
  'Naranja X', 'Modo', 'Ualá', 'Cuenta DNI', 'Visa', 'Mastercard', 'American Express', 'Cabal', 'Pago Nube',
];

describe('MedioPagoIcon', () => {
  it.each(CANONICOS)('%s tiene monograma propio, accesible por nombre', (medio) => {
    const { container } = render(<MedioPagoIcon medio={medio} />);
    expect(screen.getByRole('img', { name: medio })).toBeInTheDocument();
    expect(container.querySelector('svg title')?.textContent).toBe(medio);
    expect(container.querySelector('svg')).toHaveAttribute('data-generico', 'false');
  });

  it('un medio desconocido cae al glifo genérico y conserva el nombre (nunca inventa una marca)', () => {
    const { container } = render(<MedioPagoIcon medio="Banco Inventado" />);
    expect(screen.getByRole('img', { name: 'Banco Inventado' })).toBeInTheDocument();
    expect(container.querySelector('svg')).toHaveAttribute('data-generico', 'true');
  });

  it('los medios sin monograma propio del seed también son genéricos', () => {
    const { container } = render(<MedioPagoIcon medio="Efectivo" />);
    expect(container.querySelector('svg')).toHaveAttribute('data-generico', 'true');
  });

  it('respeta el tamaño pedido', () => {
    const { container } = render(<MedioPagoIcon medio="Visa" size={24} />);
    expect(container.querySelector('svg')).toHaveAttribute('width', '24');
  });
});

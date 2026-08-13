import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { CompareModal } from '@/components/CompareComponents';

// close-1nf-and-3nf-foundation (design DD1/DD7): precioOrig is a number or
// null now — `p.precioOrig || '—'` used to work by accident when it was a
// formatted string; a bare number renders unformatted (no thousands
// separator, no "ARS $" prefix), inconsistent with every other price on
// the page.
describe('CompareModal — precioOrig numérico', () => {
  it('formatea el precio original con fmt(), no lo imprime pelado', () => {
    const items = [
      { url: 'https://a.com/1', nombre: 'Remera A', precio: 30000, precioOrig: 45000, sitio: 'Freres' },
      { url: 'https://a.com/2', nombre: 'Remera B', precio: 20000, precioOrig: null, sitio: 'Freres' },
    ];

    render(<CompareModal items={items} onClose={vi.fn()} />);

    expect(screen.getByText('ARS $45.000')).toBeInTheDocument();
    expect(screen.queryByText('45000')).not.toBeInTheDocument();
  });

  it('muestra "—" cuando no hay precio original, no lo omite en silencio', () => {
    const items = [
      { url: 'https://a.com/1', nombre: 'Remera A', precio: 30000, precioOrig: null, sitio: 'Freres' },
      { url: 'https://a.com/2', nombre: 'Remera B', precio: 20000, precioOrig: 25000, sitio: 'Freres' },
    ];

    render(<CompareModal items={items} onClose={vi.fn()} />);

    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
  });
});

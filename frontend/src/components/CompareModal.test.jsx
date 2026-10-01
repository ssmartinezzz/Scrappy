import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CompareModal } from './CompareComponents';

const items = [
  { url: 'https://a.example/1', nombre: 'Remera A', precio: 1000, sitio: 'a' },
  { url: 'https://b.example/2', nombre: 'Remera B', precio: 2000, sitio: 'b' },
];

describe('CompareModal dismissal', () => {
  it('closes from the backdrop, which is a real button reachable without a mouse', async () => {
    const onClose = vi.fn();
    render(<CompareModal items={items} onClose={onClose} />);
    await userEvent.click(screen.getByRole('button', { name: 'Cerrar comparación' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('does not close when clicking inside the panel', async () => {
    const onClose = vi.fn();
    render(<CompareModal items={items} onClose={onClose} />);
    await userEvent.click(screen.getByText('⚖ Comparación'));
    expect(onClose).not.toHaveBeenCalled();
  });
});

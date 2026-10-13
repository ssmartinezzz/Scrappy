import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import BenchmarkChart, { nombreModelo } from '@/components/BenchmarkChart';

const cpuGaming = {
  escala: 'gaming',
  pick: { modelo: 'ryzen-5-7600x', puntaje: 673 },
  mejorDeGama: { modelo: 'ryzen-5-7600x3d', puntaje: 806, gama: 'MEDIA' },
  mejorDelCatalogo: { modelo: 'ryzen-7-9850x3d', puntaje: 1000 },
};

describe('nombreModelo', () => {
  it.each([
    ['ryzen-7-9850x3d', 'Ryzen 7 9850X3D'],
    ['ryzen-5-pro-5650g', 'Ryzen 5 PRO 5650G'],
    ['core-i7-14700k', 'Core i7-14700K'],
    ['core-ultra-7-270k', 'Core Ultra 7 270K'],
    ['rtx-5070-ti', 'RTX 5070 Ti'],
    ['rx-9070-xt', 'RX 9070 XT'],
    ['arc-b580', 'Arc B580'],
  ])('%s → %s', (clave, nombre) => {
    expect(nombreModelo(clave)).toBe(nombre);
  });
});

describe('BenchmarkChart', () => {
  it('compara el pick con la mejor de su gama y la del catálogo, en la escala del slot', () => {
    render(<BenchmarkChart referencias={{ cpu: cpuGaming }} />);
    const cpu = within(screen.getByRole('group', { name: /CPU/ }));

    expect(cpu.getByText(/índice gaming/i)).toBeInTheDocument();
    const filas = cpu.getAllByRole('meter');
    expect(filas.map(f => f.getAttribute('aria-label'))).toEqual([
      'Tu CPU: Ryzen 5 7600X',
      'Mejor de gama media: Ryzen 5 7600X3D',
      'Mejor del catálogo: Ryzen 7 9850X3D',
    ]);
    expect(filas.map(f => f.getAttribute('aria-valuenow'))).toEqual(['673', '806', '1000']);
    expect(filas[2]).toHaveAttribute('aria-valuemax', '1000');
  });

  it('un pick sin benchmark lo dice, sin barra, y las referencias siguen', () => {
    const sinPuntaje = { ...cpuGaming, pick: { modelo: 'ryzen-5-4500', puntaje: 0 } };
    render(<BenchmarkChart referencias={{ cpu: sinPuntaje }} />);
    const cpu = within(screen.getByRole('group', { name: /CPU/ }));

    expect(cpu.getByText('sin benchmark')).toBeInTheDocument();
    expect(cpu.getAllByRole('meter')).toHaveLength(2);
  });

  it('una referencia nula no se dibuja', () => {
    render(<BenchmarkChart referencias={{ gpu: {
      escala: 'gpu', pick: { modelo: 'rtx-5070', puntaje: 600 }, mejorDeGama: null, mejorDelCatalogo: null,
    } }} />);

    const gpu = within(screen.getByRole('group', { name: /Placa de video/ }));
    expect(gpu.getAllByRole('meter')).toHaveLength(1);
    expect(gpu.getByText(/1440p/)).toBeInTheDocument();
  });

  it('homelab rotula la escala de Blender', () => {
    render(<BenchmarkChart referencias={{ cpu: { ...cpuGaming, escala: 'blender' } }} />);
    expect(screen.getByText(/Blender/)).toBeInTheDocument();
  });

  it('sin referencias no renderiza nada', () => {
    const { container } = render(<BenchmarkChart referencias={{}} />);
    expect(container).toBeEmptyDOMElement();
  });
});

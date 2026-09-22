import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import PcsPanel from '@/components/PcsPanel';
import { fetchIndices, fetchPcPreferencia, fetchPcsBuilder, savePcPreferencia } from '@/api';

// Mismo seam que PcsPanel.test.jsx: se mockea el módulo de api, no global.fetch.
vi.mock('@/api', async importOriginal => ({
  ...(await importOriginal()),
  fetchPcsBuilder: vi.fn(),
  fetchPcPreferencia: vi.fn(),
  savePcPreferencia: vi.fn(),
  fetchIndices: vi.fn(),
}));

const pick = (slot, nombre, extra = {}) => ({
  slot,
  sitio: 'rockethard',
  nombre,
  precio: 100000,
  url: `https://test/${nombre}`,
  img: '',
  marca: 'Gigabyte',
  specs: { socket: '', ddr: '', formFactor: '', watts: 0, capacidadGb: 0, tipoMemoria: '' },
  ...extra,
});

function respuesta(overrides = {}) {
  return {
    picks: [pick('mother', 'B550M')],
    sinStock: [],
    sinCompatible: [],
    presupuesto: 0,
    totalEstimado: 1_500_000,
    mensajes: {},
    ...overrides,
  };
}

function llamada(n) {
  return fetchPcsBuilder.mock.calls[n][0];
}

function grupo(nombre) {
  return within(screen.getByRole('group', { name: nombre }));
}

async function armar(user) {
  await user.click(screen.getByRole('button', { name: 'Generar' }));
  await waitFor(() => expect(fetchPcsBuilder).toHaveBeenCalledTimes(1));
}

beforeEach(() => {
  vi.clearAllMocks();
  fetchPcsBuilder.mockResolvedValue(respuesta());
  fetchPcPreferencia.mockResolvedValue(null);
  savePcPreferencia.mockResolvedValue(null);
  fetchIndices.mockResolvedValue({
    ipc: { confianza: 'observado', variacionMensual: 2.1 },
    usd: { confianza: 'observado', ultimoValor: 1500 },
  });
});

describe('PcsPanel — las cuatro preferencias de la fase 9', () => {
  it('no manda ninguna de las cuatro cuando el usuario no eligió nada', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await armar(user);

    expect(llamada(0).tamanioGabinete).toBe('');
    expect(llamada(0).tipoCooler).toBe('');
    expect(llamada(0).capacidadMinimaGb).toBe(0);
    expect(llamada(0).wattsMinimos).toBe(0);
  });

  it('manda el tamaño de gabinete elegido', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await user.click(grupo('Gabinete').getByRole('button', { name: 'Mid tower' }));
    await armar(user);

    expect(llamada(0).tamanioGabinete).toBe('mid');
  });

  it('manda el tipo de cooler elegido', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await user.click(grupo('Refrigeración').getByRole('button', { name: 'Líquida' }));
    await armar(user);

    expect(llamada(0).tipoCooler).toBe('liquido');
  });

  it('manda el piso de capacidad elegido', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await user.click(grupo('Capacidad mínima del disco').getByRole('button', { name: '1 TB' }));
    await armar(user);

    expect(llamada(0).capacidadMinimaGb).toBe(1024);
  });

  it('manda el piso de watts elegido', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await user.click(grupo('Watts mínimos de la fuente').getByRole('button', { name: '850 W' }));
    await armar(user);

    expect(llamada(0).wattsMinimos).toBe(850);
  });

  it('avisa que pedir un tamaño de gabinete achica mucho el pool', async () => {
    render(<PcsPanel />);
    // El dato medido vive en la UI porque es contraintuitivo: el usuario
    // pide "mid tower" y recibe 43 candidatos de 622.
    expect(await screen.findByText(/pocos gabinetes declaran su tamaño/i)).toBeInTheDocument();
  });
});

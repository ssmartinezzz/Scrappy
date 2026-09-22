import { render, screen, waitFor } from '@testing-library/react';
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

describe('PcsPanel — el total estimado en pesos y en dólares', () => {
  it('muestra el equivalente en dólares con la cotización del header', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await armar(user);

    // 1.500.000 / 1500 = 1.000
    expect(await screen.findByText('$1.500.000')).toBeInTheDocument();
    expect(screen.getByText(/US\$\s?1\.000/)).toBeInTheDocument();
  });

  it('no inventa una cotización cuando el servicio no tiene dato', async () => {
    fetchIndices.mockResolvedValue({ ipc: null, usd: { confianza: 'sin_datos', ultimoValor: null } });
    const user = userEvent.setup();
    render(<PcsPanel />);
    await armar(user);

    expect(await screen.findByText('$1.500.000')).toBeInTheDocument();
    expect(screen.queryByText(/US\$/)).not.toBeInTheDocument();
  });

  it('tampoco muestra dólares si el servicio falla entero', async () => {
    fetchIndices.mockRejectedValue(new Error('backend caído'));
    const user = userEvent.setup();
    render(<PcsPanel />);
    await armar(user);

    expect(await screen.findByText('$1.500.000')).toBeInTheDocument();
    expect(screen.queryByText(/US\$/)).not.toBeInTheDocument();
  });
});

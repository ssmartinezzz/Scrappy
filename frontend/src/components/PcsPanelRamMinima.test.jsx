import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import PcsPanel from '@/components/PcsPanel';
import { fetchIndices, fetchPcPreferencia, fetchPcsBuilder, savePcPreferencia } from '@/api';

vi.mock('@/api', async importOriginal => ({
  ...(await importOriginal()),
  fetchPcsBuilder: vi.fn(),
  fetchPcPreferencia: vi.fn(),
  savePcPreferencia: vi.fn(),
  fetchIndices: vi.fn(),
}));

const grupo = nombre => within(screen.getByRole('group', { name: nombre }));

async function armar(user) {
  await user.click(screen.getByRole('button', { name: 'Generar' }));
  await waitFor(() => expect(fetchPcsBuilder).toHaveBeenCalledTimes(1));
}

beforeEach(() => {
  vi.clearAllMocks();
  fetchPcsBuilder.mockResolvedValue({
    picks: [], sinStock: [], sinCompatible: [], presupuesto: 0, totalEstimado: 0, mensajes: {},
  });
  fetchPcPreferencia.mockResolvedValue(null);
  savePcPreferencia.mockResolvedValue(null);
  fetchIndices.mockResolvedValue({});
});

describe('PcsPanel — piso de RAM', () => {
  it('sin pedido no manda piso ni lo guarda', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await user.click(screen.getByRole('button', { name: 'Alta' }));
    await armar(user);

    expect(fetchPcsBuilder.mock.calls[0][0].ramMinimaGb).toBe(0);
    expect(savePcPreferencia.mock.calls[0][0]).not.toHaveProperty('ramMinimaGb');
  });

  it('manda y guarda el piso elegido', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await user.click(screen.getByRole('button', { name: 'Alta' }));
    await user.click(grupo('RAM mínima').getByRole('button', { name: '32 GB' }));
    await armar(user);

    expect(fetchPcsBuilder.mock.calls[0][0].ramMinimaGb).toBe(32);
    expect(savePcPreferencia.mock.calls[0][0].ramMinimaGb).toBe(32);
  });

  it('la preferencia guardada precarga el piso', async () => {
    fetchPcPreferencia.mockResolvedValue({ gama: 'alta', presupuesto: null, conGpu: false, ramMinimaGb: 16 });
    render(<PcsPanel />);

    await waitFor(() =>
      expect(grupo('RAM mínima').getByRole('button', { name: '16 GB' })).toHaveAttribute('aria-pressed', 'true')
    );
  });
});

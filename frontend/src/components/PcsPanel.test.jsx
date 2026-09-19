import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import PcsPanel from '@/components/PcsPanel';
import { fetchPcsBuilder } from '@/api';

// Mocking the api module (not global.fetch) pins the component to the seam that
// carries VITE_API_BASE_URL — same reasoning as SuplementosPanel.test.jsx.
vi.mock('@/api', async importOriginal => ({
  ...(await importOriginal()),
  fetchPcsBuilder: vi.fn(),
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
    totalEstimado: 100000,
    ...overrides,
  };
}

/** The args the component sent on the Nth call to the builder. */
function llamada(n) {
  return fetchPcsBuilder.mock.calls[n][0];
}

beforeEach(() => {
  vi.clearAllMocks();
  fetchPcsBuilder.mockResolvedValue(respuesta());
});

async function armar(user) {
  await user.click(screen.getByRole('button', { name: 'Generar' }));
  await waitFor(() => expect(fetchPcsBuilder).toHaveBeenCalledTimes(1));
}

describe('PcsPanel — Regenerar', () => {
  it('el primer armado no excluye nada', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await armar(user);

    expect(llamada(0).excluir).toEqual([]);
  });

  it('Regenerar manda lo ya mostrado, para que el servidor ofrezca otro', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await armar(user);

    fetchPcsBuilder.mockResolvedValue(respuesta({ picks: [pick('mother', 'B550M-2')] }));
    await user.click(screen.getByRole('button', { name: 'Regenerar' }));
    await waitFor(() => expect(fetchPcsBuilder).toHaveBeenCalledTimes(2));

    expect(llamada(1).excluir).toEqual(['https://test/B550M']);
  });

  it('Regenerar acumula a lo largo de varios clicks, entre slots', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    fetchPcsBuilder.mockResolvedValue(respuesta({
      picks: [pick('mother', 'B550M'), pick('cpu', 'Ryzen 5')],
    }));
    await armar(user);

    fetchPcsBuilder.mockResolvedValue(respuesta({
      picks: [pick('mother', 'B550M-2'), pick('cpu', 'Ryzen 7')],
    }));
    await user.click(screen.getByRole('button', { name: 'Regenerar' }));
    await waitFor(() => expect(fetchPcsBuilder).toHaveBeenCalledTimes(2));

    expect(llamada(1).excluir).toEqual(expect.arrayContaining([
      'https://test/B550M', 'https://test/Ryzen 5',
    ]));
  });

  it('Armar arranca de cero aunque ya se haya regenerado', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await armar(user);
    await user.click(screen.getByRole('button', { name: 'Regenerar' }));
    await waitFor(() => expect(fetchPcsBuilder).toHaveBeenCalledTimes(2));

    await user.click(screen.getByRole('button', { name: 'Generar' }));
    await waitFor(() => expect(fetchPcsBuilder).toHaveBeenCalledTimes(3));

    expect(llamada(2).excluir).toEqual([]);
  });

  it('cuando el servidor recicla el pool de UN slot, solo ese slot reinicia su historial', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    fetchPcsBuilder.mockResolvedValue(respuesta({
      picks: [pick('mother', 'B550M'), pick('cpu', 'Ryzen 5')],
    }));
    await armar(user);

    // cpu repite la misma URL (pool agotado); mother avanza.
    fetchPcsBuilder.mockResolvedValue(respuesta({
      picks: [pick('mother', 'B550M-2'), pick('cpu', 'Ryzen 5')],
    }));
    await user.click(screen.getByRole('button', { name: 'Regenerar' }));
    await waitFor(() => expect(fetchPcsBuilder).toHaveBeenCalledTimes(2));

    fetchPcsBuilder.mockResolvedValue(respuesta({
      picks: [pick('mother', 'B550M-3'), pick('cpu', 'Ryzen 5')],
    }));
    await user.click(screen.getByRole('button', { name: 'Regenerar' }));
    await waitFor(() => expect(fetchPcsBuilder).toHaveBeenCalledTimes(3));

    expect(llamada(2).excluir).toEqual(expect.arrayContaining([
      'https://test/B550M', 'https://test/B550M-2', 'https://test/Ryzen 5',
    ]));
    expect(llamada(2).excluir).toHaveLength(3);
  });
});

describe('PcsPanel — conGpu', () => {
  it('el checkbox "Incluir placa de video" se manda como conGpu', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await user.click(screen.getByRole('checkbox', { name: 'Incluir placa de video' }));
    await armar(user);

    expect(llamada(0).conGpu).toBe(true);
  });

  it('sin marcar, conGpu no se manda en true', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await armar(user);

    expect(llamada(0).conGpu).toBe(false);
  });
});

describe('PcsPanel — placeholders y specs', () => {
  it('sinStock y sinCompatible muestran textos distintos', async () => {
    const user = userEvent.setup();
    fetchPcsBuilder.mockResolvedValue(respuesta({
      picks: [pick('mother', 'B550M')],
      sinStock: ['cpu'],
      sinCompatible: ['gpu'],
    }));
    render(<PcsPanel />);
    await armar(user);

    expect(screen.getByText('Sin stock')).toBeInTheDocument();
    expect(screen.getByText('Sin compatible')).toBeInTheDocument();
    expect(screen.getByText(/ninguna opción compatible con la mother elegida/i)).toBeInTheDocument();
  });

  it('la línea de specs omite los campos vacíos o en cero', async () => {
    const user = userEvent.setup();
    fetchPcsBuilder.mockResolvedValue(respuesta({
      picks: [pick('mother', 'B550M', {
        specs: { socket: 'AM4', ddr: 'DDR4', formFactor: '', watts: 650, capacidadGb: 0, tipoMemoria: '' },
      })],
    }));
    render(<PcsPanel />);
    await armar(user);

    expect(screen.getByText('AM4 · DDR4 · 650 W')).toBeInTheDocument();
  });

  it('el total muestra totalEstimado', async () => {
    const user = userEvent.setup();
    fetchPcsBuilder.mockResolvedValue(respuesta({ totalEstimado: 550000 }));
    render(<PcsPanel />);
    await armar(user);

    expect(screen.getByText('$550.000')).toBeInTheDocument();
  });
});

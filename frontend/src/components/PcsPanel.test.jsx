import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import PcsPanel from '@/components/PcsPanel';
import { fetchPcPreferencia, fetchPcsBuilder, fmt, savePcPreferencia } from '@/api';

// Mocking the api module (not global.fetch) pins the component to the seam that
// carries VITE_API_BASE_URL — same reasoning as SuplementosPanel.test.jsx.
vi.mock('@/api', async importOriginal => ({
  ...(await importOriginal()),
  fetchPcsBuilder: vi.fn(),
  fetchPcPreferencia: vi.fn(),
  savePcPreferencia: vi.fn(),
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
    mensajes: {},
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
  fetchPcPreferencia.mockResolvedValue(null);
  savePcPreferencia.mockResolvedValue(null);
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

describe('PcsPanel — Guardar', () => {
  it('no muestra el botón sin onSavePc', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await armar(user);

    expect(screen.queryByRole('button', { name: /Guardar/ })).not.toBeInTheDocument();
  });

  it('no muestra el botón sin picks', () => {
    render(<PcsPanel onSavePc={vi.fn()} />);

    expect(screen.queryByRole('button', { name: /Guardar/ })).not.toBeInTheDocument();
  });

  it('clickear Guardar llama a onSavePc una vez con picks, presupuesto, conGpu y total', async () => {
    const user = userEvent.setup();
    const onSavePc = vi.fn().mockResolvedValue({ ok: true, id: 1, nombre: 'PC', totalEstimado: 100000 });
    render(<PcsPanel onSavePc={onSavePc} />);
    await armar(user);

    await user.click(screen.getByRole('button', { name: /Guardar/ }));

    expect(onSavePc).toHaveBeenCalledTimes(1);
    expect(onSavePc).toHaveBeenCalledWith({
      nombre: `PC $${fmt(100000)}`,
      picks: respuesta().picks,
      presupuesto: 0,
      conGpu: false,
      gama: null,
      totalEstimado: 100000,
    });
  });

  it('el payload de Guardar lleva la gama elegida', async () => {
    const user = userEvent.setup();
    const onSavePc = vi.fn().mockResolvedValue({ ok: true });
    render(<PcsPanel onSavePc={onSavePc} />);
    await user.click(screen.getByRole('button', { name: 'Alta' }));
    await armar(user);

    await user.click(screen.getByRole('button', { name: /Guardar/ }));

    expect(onSavePc.mock.calls[0][0].gama).toBe('alta');
  });

  it('el botón se deshabilita mientras guarda', async () => {
    const user = userEvent.setup();
    let resolveSave;
    const onSavePc = vi.fn(() => new Promise(res => { resolveSave = res; }));
    render(<PcsPanel onSavePc={onSavePc} />);
    await armar(user);

    await user.click(screen.getByRole('button', { name: /Guardar/ }));

    expect(screen.getByRole('button', { name: /Guardando/ })).toBeDisabled();
    resolveSave({ ok: true });
  });
});

describe('PcsPanel — gama', () => {
  it('sin elegir gama, no se manda ninguna', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await armar(user);

    expect(llamada(0).gama).toBe('');
    expect(screen.getByRole('button', { name: 'Cualquiera' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('los chips son excluyentes y la gama elegida viaja como gama=', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await user.click(screen.getByRole('button', { name: 'Económica' }));
    await user.click(screen.getByRole('button', { name: 'Alta' }));
    await armar(user);

    expect(llamada(0).gama).toBe('alta');
    expect(screen.getByRole('button', { name: 'Alta' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Económica' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('button', { name: 'Cualquiera' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('Generar con una gama guarda la preferencia con presupuesto y conGpu', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await user.click(screen.getByRole('button', { name: 'Media' }));
    await user.click(screen.getByRole('checkbox', { name: 'Incluir placa de video' }));
    await armar(user);

    expect(savePcPreferencia).toHaveBeenCalledTimes(1);
    expect(savePcPreferencia).toHaveBeenCalledWith({ gama: 'media', presupuesto: null, conGpu: true });
  });

  it('Generar sin gama no guarda preferencia (el PUT la exige)', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await armar(user);

    expect(savePcPreferencia).not.toHaveBeenCalled();
  });

  it('la preferencia guardada precarga gama, presupuesto y conGpu — pero no arma sola', async () => {
    fetchPcPreferencia.mockResolvedValue({ gama: 'alta', presupuesto: 900000, conGpu: true });
    render(<PcsPanel />);

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Alta' })).toHaveAttribute('aria-pressed', 'true')
    );
    expect(screen.getByRole('checkbox', { name: 'Incluir placa de video' })).toBeChecked();
    expect(screen.getByLabelText(/Presupuesto/)).toHaveValue('900.000');
    expect(fetchPcsBuilder).not.toHaveBeenCalled();
  });

  it('un presupuesto null en la preferencia deja el campo vacío', async () => {
    fetchPcPreferencia.mockResolvedValue({ gama: 'media', presupuesto: null, conGpu: false });
    render(<PcsPanel />);

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Media' })).toHaveAttribute('aria-pressed', 'true')
    );
    expect(screen.getByLabelText(/Presupuesto/)).toHaveValue('');
  });

  it('si leer la preferencia falla, el panel sigue usable con los defaults', async () => {
    fetchPcPreferencia.mockRejectedValue(new Error('down'));
    const user = userEvent.setup();
    render(<PcsPanel />);
    await armar(user);

    expect(llamada(0).gama).toBe('');
  });
});

describe('PcsPanel — mensajes por slot', () => {
  it('un slot vacío muestra el motivo que manda el servidor', async () => {
    const user = userEvent.setup();
    fetchPcsBuilder.mockResolvedValue(respuesta({
      sinStock: ['cooler'],
      sinCompatible: ['cpu'],
      mensajes: {
        cooler: 'no hay productos en la categoría Cooler',
        cpu: 'ningún CPU de gama alta compatible con el socket AM4',
      },
    }));
    render(<PcsPanel />);
    await armar(user);

    expect(screen.getByText('no hay productos en la categoría Cooler')).toBeInTheDocument();
    expect(screen.getByText('ningún CPU de gama alta compatible con el socket AM4')).toBeInTheDocument();
    expect(screen.getByText('Cooler')).toBeInTheDocument();
  });

  it('sin motivo del servidor, sinCompatible cae al texto genérico', async () => {
    const user = userEvent.setup();
    fetchPcsBuilder.mockResolvedValue(respuesta({ sinCompatible: ['gpu'], mensajes: {} }));
    render(<PcsPanel />);
    await armar(user);

    expect(screen.getByText(/ninguna opción compatible con la mother elegida/i)).toBeInTheDocument();
  });
});

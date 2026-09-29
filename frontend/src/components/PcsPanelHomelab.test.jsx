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

describe('PcsPanel — chips de Uso', () => {
  it('Gaming está seleccionado por default, y uso no viaja', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    expect(grupo('Uso').getByRole('button', { name: 'Gaming' })).toHaveAttribute('aria-pressed', 'true');
    expect(grupo('Uso').getByRole('button', { name: 'Homelab' })).toHaveAttribute('aria-pressed', 'false');

    await armar(user);

    expect(llamada(0).uso).toBe('');
  });

  it('los chips son excluyentes y Homelab viaja como uso=homelab', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await user.click(grupo('Uso').getByRole('button', { name: 'Homelab' }));
    await armar(user);

    expect(llamada(0).uso).toBe('homelab');
    expect(grupo('Uso').getByRole('button', { name: 'Homelab' })).toHaveAttribute('aria-pressed', 'true');
    expect(grupo('Uso').getByRole('button', { name: 'Gaming' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('volver a Gaming después de elegir Homelab deja de mandar uso', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await user.click(grupo('Uso').getByRole('button', { name: 'Homelab' }));
    await user.click(grupo('Uso').getByRole('button', { name: 'Gaming' }));
    await armar(user);

    expect(llamada(0).uso).toBe('');
  });
});

describe('PcsPanel — Uso y la preferencia guardada', () => {
  it('Generar en Gaming (default) no manda la clave uso en la preferencia', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await user.click(screen.getByRole('button', { name: 'Media' }));
    await armar(user);

    expect(savePcPreferencia).toHaveBeenCalledTimes(1);
    const body = savePcPreferencia.mock.calls[0][0];
    expect('uso' in body).toBe(false);
  });

  it('Generar en Homelab manda uso=homelab en la preferencia', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await user.click(screen.getByRole('button', { name: 'Media' }));
    await user.click(grupo('Uso').getByRole('button', { name: 'Homelab' }));
    await armar(user);

    expect(savePcPreferencia).toHaveBeenCalledWith(expect.objectContaining({ uso: 'homelab' }));
  });

  it('Regenerar no vuelve a guardar preferencia (sólo Generar)', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await user.click(screen.getByRole('button', { name: 'Media' }));
    await user.click(grupo('Uso').getByRole('button', { name: 'Homelab' }));
    await armar(user);
    savePcPreferencia.mockClear();

    await user.click(screen.getByRole('button', { name: 'Regenerar' }));
    await waitFor(() => expect(fetchPcsBuilder).toHaveBeenCalledTimes(2));

    expect(savePcPreferencia).not.toHaveBeenCalled();
  });

  it('la preferencia guardada con uso=homelab precarga el chip Homelab', async () => {
    fetchPcPreferencia.mockResolvedValue({ gama: 'alta', presupuesto: null, conGpu: false, uso: 'homelab' });
    render(<PcsPanel />);

    await waitFor(() =>
      expect(grupo('Uso').getByRole('button', { name: 'Homelab' })).toHaveAttribute('aria-pressed', 'true')
    );
    expect(grupo('Uso').getByRole('button', { name: 'Gaming' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('la preferencia guardada con uso=gaming precarga el chip Gaming', async () => {
    fetchPcPreferencia.mockResolvedValue({ gama: 'alta', presupuesto: null, conGpu: false, uso: 'gaming' });
    render(<PcsPanel />);

    await waitFor(() =>
      expect(grupo('Uso').getByRole('button', { name: 'Gaming' })).toHaveAttribute('aria-pressed', 'true')
    );
  });

  it('una preferencia vieja sin campo uso precarga Gaming (default)', async () => {
    fetchPcPreferencia.mockResolvedValue({ gama: 'alta', presupuesto: null, conGpu: false });
    render(<PcsPanel />);

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Alta' })).toHaveAttribute('aria-pressed', 'true')
    );
    expect(grupo('Uso').getByRole('button', { name: 'Gaming' })).toHaveAttribute('aria-pressed', 'true');
  });
});

describe('PcsPanel — labels de los slots homelab', () => {
  it('muestra las etiquetas de sistema, datos y mini PC', async () => {
    const user = userEvent.setup();
    fetchPcsBuilder.mockResolvedValue(respuesta({
      picks: [
        pick('mother', 'B550M'),
        pick('sistema', 'SSD 500GB'),
        pick('datos', 'HDD 4TB'),
      ],
    }));
    render(<PcsPanel />);
    await armar(user);

    expect(screen.getByText('Disco de sistema')).toBeInTheDocument();
    expect(screen.getByText('Disco de datos')).toBeInTheDocument();
  });

  it('muestra la etiqueta de Mini PC en modo mini', async () => {
    const user = userEvent.setup();
    fetchPcsBuilder.mockResolvedValue(respuesta({
      picks: [
        pick('minipc', 'Mini PC Intel N100'),
        pick('datos', 'HDD 4TB'),
      ],
    }));
    render(<PcsPanel />);
    await armar(user);

    expect(screen.getByText('Mini PC', { ignore: 'button, script, style' })).toBeInTheDocument();
    expect(screen.getByText('Disco de datos')).toBeInTheDocument();
  });
});

describe('PcsPanel — chip Mini PC', () => {
  it('viaja como uso=homelab + tamanioGabinete=mini', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await user.click(grupo('Uso').getByRole('button', { name: 'Mini PC' }));
    await armar(user);

    expect(llamada(0)).toEqual(expect.objectContaining({ uso: 'homelab', tamanioGabinete: 'mini' }));
  });

  it('oculta los filtros de torre y deja gama y disco', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await user.click(grupo('Uso').getByRole('button', { name: 'Mini PC' }));

    for (const g of ['Memoria', 'CPU', 'Gabinete', 'Refrigeración', 'Watts mínimos de la fuente']) {
      expect(screen.queryByRole('group', { name: g })).not.toBeInTheDocument();
    }
    expect(screen.queryByRole('button', { name: 'RAM dual (2x)' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Incluir placa de video')).not.toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Gama' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Disco' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Capacidad mínima del disco' })).toBeInTheDocument();
  });

  it('en Homelab, Gabinete ya no ofrece Mini tower', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await user.click(grupo('Uso').getByRole('button', { name: 'Homelab' }));

    expect(grupo('Gabinete').queryByRole('button', { name: 'Mini tower' })).not.toBeInTheDocument();
  });

  it('guarda la preferencia como uso=homelab + tamanioGabinete=mini', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await user.click(screen.getByRole('button', { name: 'Media' }));
    await user.click(grupo('Uso').getByRole('button', { name: 'Mini PC' }));
    await armar(user);

    expect(savePcPreferencia).toHaveBeenCalledWith(
      expect.objectContaining({ uso: 'homelab', tamanioGabinete: 'mini' })
    );
  });

  it('una preferencia homelab + mini precarga el chip Mini PC', async () => {
    fetchPcPreferencia.mockResolvedValue({
      gama: 'media', presupuesto: null, conGpu: false, uso: 'homelab', tamanioGabinete: 'mini',
    });
    render(<PcsPanel />);

    await waitFor(() =>
      expect(grupo('Uso').getByRole('button', { name: 'Mini PC' })).toHaveAttribute('aria-pressed', 'true')
    );
  });
});

describe('PcsPanel — Regenerar en modo homelab', () => {
  it('excluye lo ya visto también para los slots sistema, datos y minipc', async () => {
    const user = userEvent.setup();
    render(<PcsPanel />);
    await user.click(grupo('Uso').getByRole('button', { name: 'Homelab' }));
    fetchPcsBuilder.mockResolvedValue(respuesta({
      picks: [pick('sistema', 'SSD 500GB'), pick('datos', 'HDD 4TB')],
    }));
    await armar(user);

    fetchPcsBuilder.mockResolvedValue(respuesta({
      picks: [pick('sistema', 'SSD 500GB-2'), pick('datos', 'HDD 4TB-2')],
    }));
    await user.click(screen.getByRole('button', { name: 'Regenerar' }));
    await waitFor(() => expect(fetchPcsBuilder).toHaveBeenCalledTimes(2));

    expect(llamada(1).excluir).toEqual(expect.arrayContaining([
      'https://test/SSD 500GB', 'https://test/HDD 4TB',
    ]));
  });
});

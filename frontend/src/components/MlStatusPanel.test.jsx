import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import MlStatusPanel from './MlStatusPanel';
import { aplicarModeloML, fetchMlEstado } from '../api';

vi.mock('../api', () => ({
  fetchMlEstado: vi.fn(),
  startMlTraining: vi.fn(),
  aplicarModeloML: vi.fn(),
  fetchMlResultado: vi.fn(() => Promise.resolve(null)),
}));

const ESTADO_OK = {
  hasTextModel: true,
  hasImageModel: false,
  textMeta: null,
  training: { running: false, phase: 'idle', pct: 0, msg: '' },
  embeddings: 0,
};

beforeEach(() => {
  vi.clearAllMocks();
  document.body.innerHTML = '';
  fetchMlEstado.mockResolvedValue(ESTADO_OK);
});

/**
 * El backend rechaza `POST /api/ml/aplicar` con 409 cuando ya hay un scrape o
 * otro scoring en vuelo — el pipeline no es reentrante (comparte
 * ml_productos.json/ml_output.json en el cwd). `aplicarModeloML` devuelve
 * `null` ante cualquier respuesta no-ok, igual que `startMlTraining`.
 *
 * Sin esto el rechazo era invisible: el panel mostraba "Aplicando..." tres
 * segundos y recargaba, exactamente igual que en el camino feliz — así que un
 * guard server-side correcto se le aparecía al usuario como un no-op silencioso.
 */
describe('MlStatusPanel — un "aplicar" rechazado se ve', () => {
  it('avisa con un toast de error cuando el backend rechaza el POST', async () => {
    aplicarModeloML.mockResolvedValue(null);
    render(<MlStatusPanel />);

    await userEvent.click(await screen.findByRole('button', { name: 'Aplicar a datos' }));

    await waitFor(() => {
      expect(document.querySelector('.toast-error')).not.toBeNull();
    });
    expect(document.querySelector('.toast-error').textContent).toMatch(/aplicar/i);
  });

  it('no deja el botón clavado en "Aplicando..." tras un rechazo', async () => {
    aplicarModeloML.mockResolvedValue(null);
    render(<MlStatusPanel />);

    await userEvent.click(await screen.findByRole('button', { name: 'Aplicar a datos' }));

    // El camino feliz se queda en "Aplicando..." 3s a propósito; el rechazo no
    // tiene nada que esperar, así que vuelve enseguida.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Aplicar a datos' })).toBeEnabled();
    });
  });

  it('no muestra toast de error cuando el POST fue aceptado', async () => {
    aplicarModeloML.mockResolvedValue({ status: 'started' });
    render(<MlStatusPanel />);

    await userEvent.click(await screen.findByRole('button', { name: 'Aplicar a datos' }));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Aplicando...' })).toBeInTheDocument();
    });
    expect(document.querySelector('.toast-error')).toBeNull();
  });
});

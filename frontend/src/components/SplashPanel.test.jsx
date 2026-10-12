import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import SplashPanel from './SplashPanel';

vi.mock('../api', () => ({
  fetchSitios: vi.fn(() => Promise.resolve({ base: [], extras: [] })),
  startScrape: vi.fn(),
  limpiarCatalogo: vi.fn(),
  limpiarMl: vi.fn(),
}));
vi.mock('./MlStatusPanel', () => ({ default: () => null }));

const BASE_PROPS = {
  config: { precioMin: 0, precioMax: 5000000 },
  scrapeMsg: 'Scrapeando entreno',
  progreso: { total: 3, completados: 1, sitios: [] },
  onScrapeStart: vi.fn(),
  onWatchRun: vi.fn(),
  onGoToApp: vi.fn(),
  prods: [],
  totalProds: 0,
};

function renderSplash(props = {}) {
  return render(<SplashPanel {...BASE_PROPS} {...props} />);
}

describe('SplashPanel — an unreachable backend stops the screen claiming progress (slice 0, task 0.2)', () => {
  it('shows the run progress while the backend is answering', () => {
    renderSplash({ scrapeStatus: 'RUNNING', backendUnreachable: false });

    expect(screen.getByText('Scrapeando entreno')).toBeInTheDocument();
    expect(screen.queryByText(/no se pudo contactar/i)).not.toBeInTheDocument();
  });

  it('replaces the progress with an explicit message once the backend stops answering', () => {
    renderSplash({ scrapeStatus: 'RUNNING', backendUnreachable: true });

    // The last thing the backend said is still RUNNING — that field mirrors the
    // backend's own ScraperStatus and this component does not get to invent a
    // value for it. What must change is what the user is told: a progress bar
    // that keeps advancing on a dead backend is a screen that lies.
    expect(screen.getByText(/no se pudo contactar/i)).toBeInTheDocument();
    expect(screen.queryByText('Scrapeando entreno')).not.toBeInTheDocument();
  });
});

describe('SplashPanel — /splash siempre tiene salida al catálogo (breadcrumb)', () => {
  it('ofrece el breadcrumb cuando hay catálogo, aunque no haya corrida ni conteo', () => {
    // El caso que no tenía salida: catálogo cargado, nada corriendo, y
    // totalProds en 0 — que es como queda en reposo.
    renderSplash({ scrapeStatus: 'DONE', tieneData: true, totalProds: 0 });

    expect(screen.getByRole('navigation', { name: /migas de pan/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Catálogo' })).toBeInTheDocument();
  });

  it('lleva al catálogo al clickearlo', async () => {
    const onGoToApp = vi.fn();
    const user = userEvent.setup();
    renderSplash({ scrapeStatus: 'DONE', tieneData: true, totalProds: 0, onGoToApp });

    await user.click(screen.getByRole('button', { name: 'Catálogo' }));

    expect(onGoToApp).toHaveBeenCalled();
  });

  // Sin catálogo no hay a dónde volver: el link mandaría a una pantalla vacía.
  it('no lo ofrece cuando todavía no hay catálogo', () => {
    renderSplash({ scrapeStatus: 'IDLE', tieneData: false });

    expect(screen.queryByRole('navigation', { name: /migas de pan/i })).not.toBeInTheDocument();
  });
});

describe('SplashPanel — la barra refleja sitios terminados, no el reloj', () => {
  const sitios = (estados) => estados.map((estado, i) => ({ nombre: `s${i}`, estado }));
  const run = (estados, extra = {}) => ({
    scrapeStatus: 'RUNNING',
    progreso: { total: estados.length, completados: 0, sitios: sitios(estados), ...extra },
  });

  it('no avanza con el tiempo mientras ningún sitio termina', () => {
    vi.useFakeTimers();
    try {
      renderSplash(run(['running', 'idle', 'idle']));
      expect(screen.getByText('0%')).toBeInTheDocument();

      act(() => { vi.advanceTimersByTime(60_000); });

      expect(screen.getByText('0%')).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('muestra terminados/total y su porcentaje exacto', () => {
    renderSplash(run(['done', 'done', 'done', ...Array(7).fill('idle')]));

    expect(screen.getByText('30%')).toBeInTheDocument();
    expect(screen.getByText('3/10 sitios')).toBeInTheDocument();
  });

  it('cuenta un sitio con error como terminado', () => {
    renderSplash(run(['done', 'error', 'running', 'idle']));

    expect(screen.getByText('50%')).toBeInTheDocument();
  });

  it('baja cuando el progreso se reinicia', () => {
    const { rerender } = renderSplash(run(['done', 'done', 'idle', 'idle']));
    expect(screen.getByText('50%')).toBeInTheDocument();

    rerender(<SplashPanel {...BASE_PROPS} {...run(['done', 'idle', 'idle', 'idle'])} />);

    expect(screen.getByText('25%')).toBeInTheDocument();
  });

  it('llega a 100% sólo cuando todos los sitios terminaron', () => {
    renderSplash(run(['done', 'error']));

    expect(screen.getByText('100%')).toBeInTheDocument();
  });

  it('cae a completados/total si la lista de sitios no viene', () => {
    renderSplash({
      scrapeStatus: 'RUNNING',
      progreso: { total: 4, completados: 1 },
    });

    expect(screen.getByText('25%')).toBeInTheDocument();
    expect(screen.getByText('1/4 sitios')).toBeInTheDocument();
  });
});

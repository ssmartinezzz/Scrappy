import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import InterruptedRunBanner from './InterruptedRunBanner';

const RUN = {
  hayInterrumpida: true,
  uuid: '4f1a2b3c-0000-4000-8000-000000000001',
  startedAt: '2026-08-24T18:20:00Z',
  soloFaltaLaPasadaFinal: false,
  atendidos: ['freres', 'vcp', 'harvey'],
  pendientes: ['entreno', 'morashop'],
  salteados: [],
};

function renderBanner(props = {}) {
  return render(
    <InterruptedRunBanner
      run={RUN} busy={false} error="" onRetomar={vi.fn()} onDismiss={vi.fn()}
      {...props}
    />
  );
}

describe('InterruptedRunBanner — what it tells the operator', () => {
  it('renders nothing when there is no interrupted run', () => {
    const { container } = renderBanner({ run: null });
    expect(container).toBeEmptyDOMElement();
  });

  it('names how many sites were served and how many the resume owes', () => {
    renderBanner();
    expect(screen.getByRole('alert')).toHaveTextContent(/3 .*atendido/i);
    expect(screen.getByRole('alert')).toHaveTextContent(/2 .*pendiente/i);
  });

  it('says the run only owes its final pass, and does not promise to re-scrape sites', () => {
    // The forgotten case, and the one where re-scraping is pure wasted work:
    // every site finished and the crash landed in the trailing ML/aggregation
    // pass. Offering "2 sitios pendientes" here would be a lie about the cost.
    renderBanner({ run: { ...RUN, soloFaltaLaPasadaFinal: true, pendientes: [] } });

    expect(screen.getByRole('alert')).toHaveTextContent(/pasada final/i);
    expect(screen.getByRole('alert')).not.toHaveTextContent(/pendiente/i);
  });

  it('names the sites that left the registry instead of dropping them silently', () => {
    // A site that disappeared from a run that owed it is exactly the kind of
    // thing an operator needs told — the backend goes out of its way to
    // report them (CorridaInterrumpida.salteados), so the UI must not eat it.
    renderBanner({ run: { ...RUN, salteados: ['huoky'] } });

    expect(screen.getByRole('alert')).toHaveTextContent(/huoky/);
  });

  it('does not mention skipped sites when there are none', () => {
    renderBanner();
    expect(screen.getByRole('alert')).not.toHaveTextContent(/salieron del registro/i);
  });

  it('shows when the interrupted run started', () => {
    renderBanner();
    expect(screen.getByRole('alert')).toHaveTextContent(/24\/08\/2026/);
  });
});

describe('InterruptedRunBanner — the two actions', () => {
  it('resumes on the resume button', async () => {
    const onRetomar = vi.fn();
    renderBanner({ onRetomar });

    await userEvent.click(screen.getByRole('button', { name: /retomar/i }));

    expect(onRetomar).toHaveBeenCalledTimes(1);
  });

  it('hides on the dismiss button, and says so is only for now', async () => {
    const onDismiss = vi.fn();
    renderBanner({ onDismiss });

    const ocultar = screen.getByRole('button', { name: /ocultar/i });
    await userEvent.click(ocultar);

    // "Ocultar", never "Descartar": there is no discard endpoint, and a label
    // promising to throw the run away would describe something the button
    // cannot do.
    expect(onDismiss).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('button', { name: /descartar/i })).not.toBeInTheDocument();
  });

  it('locks both actions while the resume is in flight', () => {
    renderBanner({ busy: true });

    // /retoma/ and not /retomar/: the resume button's own label is what `busy`
    // changes ("Retomar" -> "Retomando..."), so matching the idle wording here
    // would assert the button had vanished rather than that it is locked.
    expect(screen.getByRole('button', { name: /retoma/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /ocultar/i })).toBeDisabled();
  });

  it('shows why a refused resume was refused, keeping the banner up', () => {
    renderBanner({ error: 'No hay corrida interrumpida, o ya hay un scraping en curso' });

    expect(screen.getByRole('alert')).toHaveTextContent(/ya hay un scraping en curso/i);
    expect(screen.getByRole('button', { name: /retomar/i })).toBeInTheDocument();
  });
});

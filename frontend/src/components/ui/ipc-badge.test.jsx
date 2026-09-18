import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { IpcBadge } from './ipc-badge';

const ipcObservado = {
  indice: 'IPC', ultimoValor: 156.3, ultimaFecha: '2026-08-31',
  variacionMensual: 2.4, variacionInteranual: 45.1, variacion3m: 7.2,
  confianza: 'observado', ultimos: [],
};

const usdObservado = {
  indice: 'USD_OFICIAL', ultimoValor: 1450, ultimaFecha: '2026-09-17',
  variacionMensual: 3.1, variacionInteranual: 40.0, variacion3m: 9.0,
  confianza: 'observado', ultimos: [],
};

describe('IpcBadge', () => {
  it('renders the IPC label, value and an accessible summary when data is observed', () => {
    render(<IpcBadge ipc={ipcObservado} usd={usdObservado} />);

    expect(screen.getByText('IPC')).toBeInTheDocument();
    expect(screen.getByText('2,4 %')).toBeInTheDocument();
    expect(screen.getByRole('img', {
      name: 'IPC 2,4 % mensual, dólar oficial $1.450, dato observado',
    })).toBeInTheDocument();
  });

  it('shows an amber confidence dot when the IPC value is extrapolated', () => {
    const { container } = render(
      <IpcBadge ipc={{ ...ipcObservado, confianza: 'extrapolado' }} usd={usdObservado} />
    );

    const dot = container.querySelector('[data-testid="ipc-badge-dot"]');
    expect(dot).toHaveStyle({ background: '#D08A1E' });
  });

  it('renders a dash and a grey ring/dot when there is no IPC data, but keeps the USD line', () => {
    render(
      <IpcBadge
        ipc={{ ...ipcObservado, confianza: 'sin_datos', variacionMensual: null }}
        usd={usdObservado}
      />
    );

    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.getByText('USD $1.450')).toBeInTheDocument();
    const dot = screen.getByTestId('ipc-badge-dot');
    expect(dot).toHaveStyle({ background: 'var(--t4)' });
  });

  it('renders nothing when both IPC and USD have no data', () => {
    const { container } = render(
      <IpcBadge
        ipc={{ ...ipcObservado, confianza: 'sin_datos' }}
        usd={{ ...usdObservado, confianza: 'sin_datos' }}
      />
    );

    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when neither ipc nor usd data is available yet', () => {
    const { container } = render(<IpcBadge ipc={null} usd={null} />);

    expect(container).toBeEmptyDOMElement();
  });
});

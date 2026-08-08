import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import BuySignal from '@/components/BuySignal';
import { fetchHistorial, fetchInflacion, fetchRecomendacion } from '@/api';

// Mocking the api module (not global.fetch) is deliberate: it pins the
// component to the seam that carries VITE_API_BASE_URL. A component that went
// back to a bare fetch('/api/...') would keep passing a fetch-level mock while
// being broken in any deployment where the origins differ.
vi.mock('@/api', () => ({
  fetchHistorial: vi.fn(),
  fetchInflacion: vi.fn(),
  fetchRecomendacion: vi.fn(),
}));

const senalOk = {
  senal: 'buen_momento',
  scoreCompra: 82,
  mensaje: 'Precio por debajo de su media histórica',
  puntosHistorial: 5,
};

beforeEach(() => {
  fetchHistorial.mockResolvedValue(null);
  fetchInflacion.mockResolvedValue(null);
  fetchRecomendacion.mockResolvedValue(senalOk);
});

describe('BuySignal', () => {
  it('requests the signal through the api module, not a bare relative path', async () => {
    render(<BuySignal url="https://tienda.test/p/1" />);

    await waitFor(() => expect(fetchRecomendacion).toHaveBeenCalledWith('https://tienda.test/p/1'));
    expect(fetchInflacion).toHaveBeenCalled();
  });

  it('shows the loading state before the signal resolves', async () => {
    render(<BuySignal url="https://tienda.test/p/1" />);

    expect(screen.getByText(/analizando precio/i)).toBeInTheDocument();
    // Let the pending request settle inside the test. Without this the state
    // update lands after teardown and React reports an act(...) warning — a
    // suite that warns on green gets ignored on red.
    await waitFor(() =>
      expect(screen.queryByText(/analizando precio/i)).not.toBeInTheDocument());
  });

  it('renders the score and message once loaded', async () => {
    render(<BuySignal url="https://tienda.test/p/1" />);

    expect(await screen.findByText('82/100')).toBeInTheDocument();
    expect(screen.getByText(senalOk.mensaje)).toBeInTheDocument();
  });

  it('explains the absence of history instead of rendering an empty box', async () => {
    fetchRecomendacion.mockResolvedValue({ senal: 'sin_datos' });

    render(<BuySignal url="https://tienda.test/p/1" />);

    expect(await screen.findByText(/sin historial aún/i)).toBeInTheDocument();
  });

  it('renders nothing at all when the request fails', async () => {
    fetchRecomendacion.mockRejectedValue(new Error('network down'));

    const { container } = render(<BuySignal url="https://tienda.test/p/1" />);

    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it('does not fetch a signal when it has no product url', async () => {
    render(<BuySignal url="" />);

    await waitFor(() => expect(screen.queryByText(/analizando precio/i)).not.toBeInTheDocument());
    expect(fetchRecomendacion).not.toHaveBeenCalled();
  });

  it('keeps the detail collapsed until the user asks for it', async () => {
    const user = userEvent.setup();
    render(<BuySignal url="https://tienda.test/p/1" />);

    const toggle = await screen.findByRole('button');
    const labelBefore = toggle.textContent;

    await user.click(toggle);

    expect(toggle.textContent).not.toBe(labelBefore);
  });
});

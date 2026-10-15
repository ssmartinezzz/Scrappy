import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import AvisosBajaPrecio from './AvisosBajaPrecio';
import { fetchPreferenciaAvisos, savePreferenciaAvisos } from '../api';

vi.mock('../api', () => ({
  fetchPreferenciaAvisos: vi.fn(),
  savePreferenciaAvisos: vi.fn(),
}));

const ETIQUETA = /avisarme por mail cuando un favorito baje más de 5%/i;

beforeEach(() => {
  vi.clearAllMocks();
});

describe('AvisosBajaPrecio', () => {
  it('shows the saved choice as a checkbox', async () => {
    fetchPreferenciaAvisos.mockResolvedValue({ notificarBajasPrecio: true, tieneEmail: true });

    render(<AvisosBajaPrecio />);

    expect(await screen.findByRole('checkbox', { name: ETIQUETA })).toBeChecked();
  });

  it('reflects an opted-out account as unchecked', async () => {
    fetchPreferenciaAvisos.mockResolvedValue({ notificarBajasPrecio: false, tieneEmail: true });

    render(<AvisosBajaPrecio />);

    expect(await screen.findByRole('checkbox', { name: ETIQUETA })).not.toBeChecked();
  });

  it('saves the flipped value and shows what the server answered', async () => {
    const user = userEvent.setup();
    fetchPreferenciaAvisos.mockResolvedValue({ notificarBajasPrecio: true, tieneEmail: true });
    savePreferenciaAvisos.mockResolvedValue({ notificarBajasPrecio: false, tieneEmail: true });

    render(<AvisosBajaPrecio />);
    await user.click(await screen.findByRole('checkbox', { name: ETIQUETA }));

    expect(savePreferenciaAvisos).toHaveBeenCalledExactlyOnceWith(false);
    await waitFor(() => expect(screen.getByRole('checkbox', { name: ETIQUETA })).not.toBeChecked());
  });

  it('keeps the previous choice and says so when the save is refused', async () => {
    const user = userEvent.setup();
    fetchPreferenciaAvisos.mockResolvedValue({ notificarBajasPrecio: true, tieneEmail: true });
    savePreferenciaAvisos.mockResolvedValue(null);

    render(<AvisosBajaPrecio />);
    await user.click(await screen.findByRole('checkbox', { name: ETIQUETA }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/no se pudo guardar/i);
    expect(screen.getByRole('checkbox', { name: ETIQUETA })).toBeChecked();
  });

  it('renders nothing for an account with no email: there is nothing to send to', async () => {
    fetchPreferenciaAvisos.mockResolvedValue({ notificarBajasPrecio: true, tieneEmail: false });

    const { container } = render(<AvisosBajaPrecio />);

    await waitFor(() => expect(fetchPreferenciaAvisos).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when the preference cannot be read', async () => {
    fetchPreferenciaAvisos.mockResolvedValue(null);

    const { container } = render(<AvisosBajaPrecio />);

    await waitFor(() => expect(fetchPreferenciaAvisos).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import AvisosBajaPrecio from './AvisosBajaPrecio';
import { cambiarEmailCuenta, fetchCuenta, fetchPreferenciaAvisos, savePreferenciaAvisos } from '../api';

vi.mock('../api', () => ({
  cambiarEmailCuenta: vi.fn(),
  fetchCuenta: vi.fn(),
  fetchPreferenciaAvisos: vi.fn(),
  savePreferenciaAvisos: vi.fn(),
}));

const ETIQUETA = /avisarme por mail cuando un favorito baje más de 5%/i;

beforeEach(() => {
  vi.clearAllMocks();
  fetchCuenta.mockResolvedValue({ username: 'viewer', email: 'viewer@example.com', esServicio: false });
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

  it('invites an account with no email to add one, instead of hiding the feature', async () => {
    fetchPreferenciaAvisos.mockResolvedValue({ notificarBajasPrecio: true, tieneEmail: false });
    fetchCuenta.mockResolvedValue({ username: 'viewer', email: null, esServicio: false });

    render(<AvisosBajaPrecio />);

    expect(await screen.findByText(/sumá tu email/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /agregar email/i })).toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  });

  it('adds an email with the current password, then shows the alerts checkbox', async () => {
    const user = userEvent.setup();
    fetchPreferenciaAvisos
      .mockResolvedValueOnce({ notificarBajasPrecio: true, tieneEmail: false })
      .mockResolvedValue({ notificarBajasPrecio: true, tieneEmail: true });
    cambiarEmailCuenta.mockResolvedValue({ ok: true, email: 'nuevo@example.com' });
    fetchCuenta.mockResolvedValue({ username: 'viewer', email: null, esServicio: false });

    render(<AvisosBajaPrecio />);
    await user.click(await screen.findByRole('button', { name: /agregar email/i }));
    await user.type(screen.getByLabelText(/^email$/i), 'nuevo@example.com');
    await user.type(screen.getByLabelText(/contraseña actual/i), 'una-password');
    await user.click(screen.getByRole('button', { name: /guardar/i }));

    expect(cambiarEmailCuenta).toHaveBeenCalledExactlyOnceWith({ password: 'una-password', email: 'nuevo@example.com' });
    expect(await screen.findByRole('checkbox', { name: ETIQUETA })).toBeChecked();
  });

  it('shows the server explanation and keeps the form when the change is refused', async () => {
    const user = userEvent.setup();
    fetchPreferenciaAvisos.mockResolvedValue({ notificarBajasPrecio: true, tieneEmail: false });
    cambiarEmailCuenta.mockResolvedValue({ ok: false, mensaje: 'La contraseña no es correcta.' });
    fetchCuenta.mockResolvedValue({ username: 'viewer', email: null, esServicio: false });

    render(<AvisosBajaPrecio />);
    await user.click(await screen.findByRole('button', { name: /agregar email/i }));
    await user.type(screen.getByLabelText(/^email$/i), 'nuevo@example.com');
    await user.type(screen.getByLabelText(/contraseña actual/i), 'mala');
    await user.click(screen.getByRole('button', { name: /guardar/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent('La contraseña no es correcta.');
    expect(screen.getByLabelText(/^email$/i)).toHaveValue('nuevo@example.com');
  });

  it('shows where alerts go and lets the user change or remove that address', async () => {
    const user = userEvent.setup();
    fetchPreferenciaAvisos.mockResolvedValue({ notificarBajasPrecio: true, tieneEmail: true });
    cambiarEmailCuenta.mockResolvedValue({ ok: true, email: null });

    render(<AvisosBajaPrecio />);
    expect(await screen.findByText('viewer@example.com')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /cambiar email/i }));
    await user.type(screen.getByLabelText(/contraseña actual/i), 'una-password');
    await user.click(screen.getByRole('button', { name: /quitar email/i }));

    expect(cambiarEmailCuenta).toHaveBeenCalledExactlyOnceWith({ password: 'una-password', email: null });
  });

  it('does not submit without a password', async () => {
    const user = userEvent.setup();
    fetchPreferenciaAvisos.mockResolvedValue({ notificarBajasPrecio: true, tieneEmail: false });

    render(<AvisosBajaPrecio />);
    await user.click(await screen.findByRole('button', { name: /agregar email/i }));
    await user.type(screen.getByLabelText(/^email$/i), 'nuevo@example.com');

    expect(screen.getByRole('button', { name: /guardar/i })).toBeDisabled();
  });

  it('renders nothing when the preference cannot be read', async () => {
    fetchPreferenciaAvisos.mockResolvedValue(null);

    const { container } = render(<AvisosBajaPrecio />);

    await waitFor(() => expect(fetchPreferenciaAvisos).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});

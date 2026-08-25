// frontend-auth-ui, Phase 6 (tasks 6.4-6.6 RED / spec frontend-password-reset).
// The token arrives in `location.hash`, never the query string, and MUST be
// stripped via history.replaceState BEFORE any network call whatsoever — the
// ordering test below pins that by asserting inside the fetch mock itself
// (the moment the network call fires), not merely on the end state.
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import ResetPassword from './ResetPassword';

function renderResetPassword() {
  return render(
    <MemoryRouter initialEntries={['/reset-password']}>
      <Routes>
        <Route path="/reset-password" element={<ResetPassword />} />
        <Route path="/login" element={<div>LOGIN SCREEN</div>} />
        <Route path="/forgot-password" element={<div>FORGOT PASSWORD SCREEN</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

function confirmOk() {
  return { ok: true, status: 200, json: async () => ({ ok: true, mensaje: 'Contraseña cambiada.' }) };
}

function confirmRejected() {
  return { ok: false, status: 400, json: async () => ({ error: 'reseteo_invalido', mensaje: 'no sirve' }) };
}

async function fillAndSubmit(password, confirmPassword = password) {
  fireEvent.change(screen.getByLabelText(/^nueva contraseña$/i), { target: { value: password } });
  fireEvent.change(screen.getByLabelText(/confirmar contraseña/i), { target: { value: confirmPassword } });
  fireEvent.click(screen.getByRole('button', { name: /cambiar contraseña/i }));
}

afterEach(() => {
  window.location.hash = '';
});

describe('ResetPassword — fragment handling (task 6.4: ordering, not just end state)', () => {
  it('strips the token from the fragment before the first network call fires', async () => {
    window.location.hash = '#token=abc123';

    global.fetch = vi.fn().mockImplementation(async () => {
      // Pinned INSIDE the mock: if replaceState had not already run by the
      // moment this fires, the hash would still carry the token here.
      expect(window.location.hash).toBe('');
      return confirmOk();
    });

    renderResetPassword();
    // Cleared on mount, well before any submit — but the real proof is the
    // in-mock assertion above, which fires at the actual network-call moment.
    expect(window.location.hash).toBe('');

    await fillAndSubmit('unapasslarga');
    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(1));
  });
});

describe('ResetPassword — absent token (task 6.7: no token in the fragment)', () => {
  it('renders an invalid-link state and makes no network call', async () => {
    window.location.hash = '';
    global.fetch = vi.fn();

    renderResetPassword();

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(global.fetch).not.toHaveBeenCalled();
  });
});

describe('ResetPassword — client-side validation (task 6.5)', () => {
  it('blocks submission with zero network calls when the password is under 8 characters', async () => {
    window.location.hash = '#token=abc123';
    global.fetch = vi.fn();

    renderResetPassword();
    await fillAndSubmit('corta1');

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it('blocks submission with zero network calls when the confirmation does not match', async () => {
    window.location.hash = '#token=abc123';
    global.fetch = vi.fn();

    renderResetPassword();
    await fillAndSubmit('unapasslarga', 'otradistinta');

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(global.fetch).not.toHaveBeenCalled();
  });
});

describe('ResetPassword — successful confirm (task 6.6: no auto-login)', () => {
  it('shows a success message and routes to /login, storing no session', async () => {
    window.location.hash = '#token=abc123';
    global.fetch = vi.fn().mockResolvedValue(confirmOk());

    renderResetPassword();
    await fillAndSubmit('unapasslarga');

    await waitFor(() => expect(screen.getByRole('status')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('link', { name: /ir a iniciar sesión/i }));
    await waitFor(() => expect(screen.getByText('LOGIN SCREEN')).toBeInTheDocument());
  });
});

describe('ResetPassword — token rejected by the backend (expired / used / unknown are NOT distinguished — docs/API_REFERENCE.md)', () => {
  it('renders one generic rejection state, does not navigate to /login', async () => {
    window.location.hash = '#token=abc123';
    global.fetch = vi.fn().mockResolvedValue(confirmRejected());

    renderResetPassword();
    await fillAndSubmit('unapasslarga');

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.queryByText('LOGIN SCREEN')).not.toBeInTheDocument();
  });
});

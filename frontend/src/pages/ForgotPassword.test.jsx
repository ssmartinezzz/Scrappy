// frontend-auth-ui, Phase 6 (task 6.2 RED / spec frontend-password-reset).
// The backend's POST /api/auth/password-reset/request ALWAYS answers 202
// with the same body, whatever the address (docs/API_REFERENCE.md). This
// screen must never branch on the submitted address — these tests pin that
// by submitting two different addresses and asserting byte-identical copy,
// and by asserting the copy never leaks anything about the account model
// (the bootstrap-admin-has-no-email caveat is docs-only, never here).
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import ForgotPassword from './ForgotPassword';

function acceptedResponse() {
  return {
    ok: true,
    status: 202,
    json: async () => ({
      mensaje: 'Si la dirección corresponde a una cuenta, va a recibir un enlace.',
    }),
  };
}

function renderForgotPassword() {
  return render(
    <MemoryRouter initialEntries={['/forgot-password']}>
      <Routes>
        <Route path="/forgot-password" element={<ForgotPassword />} />
        <Route path="/login" element={<div>LOGIN SCREEN</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('ForgotPassword — uniform public copy (spec: no account-specific branching)', () => {
  it('shows identical confirmation copy for an address that exists and one that does not', async () => {
    global.fetch = vi.fn().mockResolvedValue(acceptedResponse());

    const { unmount } = renderForgotPassword();
    fireEvent.change(screen.getByLabelText(/email/i), { target: { value: 'existe@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: /enviar enlace/i }));
    await waitFor(() => expect(screen.getByRole('status')).toBeInTheDocument());
    const firstMessage = screen.getByRole('status').textContent;
    unmount();

    renderForgotPassword();
    fireEvent.change(screen.getByLabelText(/email/i), { target: { value: 'no-existe@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: /enviar enlace/i }));
    await waitFor(() => expect(screen.getByRole('status')).toBeInTheDocument());
    const secondMessage = screen.getByRole('status').textContent;

    expect(secondMessage).toBe(firstMessage);
    expect(global.fetch).toHaveBeenCalledTimes(2);
  });

  it('never mentions specific accounts, "admin", or whether the address exists', async () => {
    global.fetch = vi.fn().mockResolvedValue(acceptedResponse());
    renderForgotPassword();
    fireEvent.change(screen.getByLabelText(/email/i), { target: { value: 'cualquiera@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: /enviar enlace/i }));
    await waitFor(() => expect(screen.getByRole('status')).toBeInTheDocument());

    const text = screen.getByRole('status').textContent.toLowerCase();
    expect(text).not.toMatch(/admin|bootstrap|no tiene email|no existe|encontr/);
  });
});

describe('ForgotPassword — request always fires with the submitted address', () => {
  it('posts to /api/auth/password-reset/request with { email }', async () => {
    global.fetch = vi.fn().mockResolvedValue(acceptedResponse());
    renderForgotPassword();
    fireEvent.change(screen.getByLabelText(/email/i), { target: { value: 'ana@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: /enviar enlace/i }));

    await waitFor(() => expect(global.fetch).toHaveBeenCalled());
    const [url, init] = global.fetch.mock.calls[0];
    expect(String(url)).toContain('/api/auth/password-reset/request');
    expect(JSON.parse(init.body)).toEqual({ email: 'ana@example.com' });
  });
});

describe('ForgotPassword — network error is distinguishable (mirrors Login.jsx)', () => {
  it('shows a "could not reach the backend" message on a network failure', async () => {
    global.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    renderForgotPassword();
    fireEvent.change(screen.getByLabelText(/email/i), { target: { value: 'ana@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: /enviar enlace/i }));

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/no se pudo contactar al servidor/i));
  });
});

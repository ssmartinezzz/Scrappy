import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Login from './Login';
import { AuthProvider } from '../auth/AuthProvider';
import { resetSession } from '../lib/authSession';

function jsonResponse(body, init = {}) {
  return { ok: true, status: 200, json: async () => body, ...init };
}

function refreshRejected() {
  return { ok: false, status: 401, json: async () => ({ error: 'refresh_invalido' }) };
}

function renderLogin(initialEntries = [{ pathname: '/login' }]) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/catalogo" element={<div>CATALOGO</div>} />
          <Route path="/cronjobs" element={<div>CRONJOBS</div>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  resetSession();
});

afterEach(() => {
  resetSession();
});

describe('Login — username, not email (D7)', () => {
  it('renders a username text field, never an email input', async () => {
    global.fetch = vi.fn().mockResolvedValue(refreshRejected());
    renderLogin();
    await waitFor(() => expect(screen.getByLabelText(/usuario/i)).toBeInTheDocument());

    const usernameField = screen.getByLabelText(/usuario/i);
    expect(usernameField).toHaveAttribute('type', 'text');
    expect(usernameField).toHaveAttribute('name', 'username');
    expect(screen.queryByRole('textbox', { name: /email/i })).not.toBeInTheDocument();
  });
});

describe('Login — forgot password link (task 6.1: always visible, regardless of account type)', () => {
  it('always renders a link to /forgot-password', async () => {
    global.fetch = vi.fn().mockResolvedValue(refreshRejected());
    renderLogin();
    await waitFor(() => expect(screen.getByLabelText(/usuario/i)).toBeInTheDocument());

    const link = screen.getByRole('link', { name: /olvidé mi contraseña/i });
    expect(link).toHaveAttribute('href', '/forgot-password');
  });
});

describe('Login — no OAuth affordance (spec: no functional OAuth affordance)', () => {
  it('never renders a Google/OAuth control', async () => {
    global.fetch = vi.fn().mockResolvedValue(refreshRejected());
    renderLogin();
    await waitFor(() => expect(screen.getByLabelText(/usuario/i)).toBeInTheDocument());

    expect(screen.queryByText(/google/i)).not.toBeInTheDocument();
  });
});

describe('Login — every failure is indistinguishable', () => {
  it.each([
    ['wrong password', { ok: false, status: 401, json: async () => ({ error: 'credenciales_invalidas' }) }],
    ['unknown username', { ok: false, status: 401, json: async () => ({ error: 'credenciales_invalidas' }) }],
    ['deactivated account', { ok: false, status: 401, json: async () => ({ error: 'credenciales_invalidas' }) }],
  ])('shows the exact same message for %s', async (_label, loginResponse) => {
    global.fetch = vi.fn().mockImplementation(async (url) => {
      const u = String(url);
      if (u.includes('/api/auth/login')) return loginResponse;
      return refreshRejected();
    });
    renderLogin();
    await waitFor(() => expect(screen.getByLabelText(/usuario/i)).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText(/usuario/i), { target: { value: 'alguien' } });
    fireEvent.change(screen.getByLabelText(/contraseña/i), { target: { value: 'lo-que-sea' } });
    fireEvent.click(screen.getByRole('button', { name: /ingresar/i }));

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Usuario o contraseña incorrectos.'));
  });
});

describe('Login — successful login navigates past the login route', () => {
  it('stores the session and renders the destination route', async () => {
    global.fetch = vi.fn().mockImplementation(async (url) => {
      const u = String(url);
      if (u.includes('/api/auth/login')) {
        return jsonResponse({ accessToken: 'tok', csrfNonce: 'nonce', expiresIn: 900, tokenType: 'Bearer' });
      }
      if (u.includes('/api/auth/me')) return jsonResponse({ username: 'valeria', roles: ['VIEWER'] });
      return refreshRejected();
    });
    renderLogin([{ pathname: '/login', state: { from: { pathname: '/catalogo' } } }]);
    await waitFor(() => expect(screen.getByLabelText(/usuario/i)).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText(/usuario/i), { target: { value: 'valeria' } });
    fireEvent.change(screen.getByLabelText(/contraseña/i), { target: { value: 'correcta' } });
    fireEvent.click(screen.getByRole('button', { name: /ingresar/i }));

    await waitFor(() => expect(screen.getByText('CATALOGO')).toBeInTheDocument());
  });

  it('returns to the originally attempted route after re-login', async () => {
    global.fetch = vi.fn().mockImplementation(async (url) => {
      const u = String(url);
      if (u.includes('/api/auth/login')) {
        return jsonResponse({ accessToken: 'tok', csrfNonce: 'nonce', expiresIn: 900, tokenType: 'Bearer' });
      }
      if (u.includes('/api/auth/me')) return jsonResponse({ username: 'valeria', roles: ['ADMIN'] });
      return refreshRejected();
    });
    renderLogin([{ pathname: '/login', state: { from: { pathname: '/cronjobs' } } }]);
    await waitFor(() => expect(screen.getByLabelText(/usuario/i)).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText(/usuario/i), { target: { value: 'valeria' } });
    fireEvent.change(screen.getByLabelText(/contraseña/i), { target: { value: 'correcta' } });
    fireEvent.click(screen.getByRole('button', { name: /ingresar/i }));

    await waitFor(() => expect(screen.getByText('CRONJOBS')).toBeInTheDocument());
  });
});

describe('Login — network error is distinguishable from a rejected session', () => {
  it('shows a "could not reach the backend" message, not a generic login-screen with no explanation', async () => {
    global.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    renderLogin();

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/no se pudo contactar al servidor/i));
  });
});

describe('Login — one error at a time', () => {
  // The e2e suite caught this, but only by timing: for many green runs the
  // bootstrap reached the backend before the test cut it, so the arrival banner
  // was absent and only the form error showed. When a slow start made both
  // render, Playwright's strict mode failed on a selector matching two nodes.
  // The defect was there the whole time. This test pins it deterministically:
  // two `role="alert"` nodes with identical text is a real flaw — a screen
  // reader announces it twice — not a test artefact.
  it('does not stack the arrival banner and the submit error when both blame the network', async () => {
    global.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    renderLogin();

    // The bootstrap failure has rendered the arrival banner.
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(/no se pudo contactar al servidor/i));

    fireEvent.change(screen.getByLabelText(/usuario/i), { target: { value: 'valeria' } });
    fireEvent.change(screen.getByLabelText(/contraseña/i), { target: { value: 'unapasslarga' } });
    fireEvent.click(screen.getByRole('button', { name: /ingresar/i }));

    // The submit fails the same way. Exactly one alert survives, still naming
    // the network — the fresher message wins, the duplicate does not appear.
    await waitFor(() => {
      const alerts = screen.getAllByRole('alert');
      expect(alerts).toHaveLength(1);
      expect(alerts[0]).toHaveTextContent(/no se pudo contactar al servidor/i);
    });
  });
});

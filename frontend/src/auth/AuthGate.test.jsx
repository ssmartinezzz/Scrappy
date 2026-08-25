import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Navigate, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthProvider, useAuth } from './AuthProvider';
import AuthGate from './AuthGate';
import { resetSession } from '../lib/authSession';

function jsonResponse(body, init = {}) {
  return { ok: true, status: 200, json: async () => body, ...init };
}

function refreshRejected() {
  return { ok: false, status: 401, json: async () => ({ error: 'refresh_invalido' }) };
}

function refreshOk() {
  return jsonResponse({ accessToken: 'tok', csrfNonce: 'nonce', expiresIn: 900, tokenType: 'Bearer' });
}

function TestTree({ initialPath = '/protegido' }) {
  return (
    <MemoryRouter initialEntries={[initialPath]}>
      <AuthProvider>
        <AuthGate>
          <Routes>
            <Route path="/login" element={<div>LOGIN SCREEN</div>} />
            <Route path="/protegido" element={<div>PROTECTED CONTENT</div>} />
          </Routes>
        </AuthGate>
      </AuthProvider>
    </MemoryRouter>
  );
}

beforeEach(() => {
  resetSession();
});

afterEach(() => {
  resetSession();
});

describe('AuthGate — bootstrap ordering (design D5)', () => {
  it('shows a fallback while booting, never the protected content', async () => {
    let resolveFetch;
    global.fetch = vi.fn().mockImplementation(() => new Promise(resolve => { resolveFetch = resolve; }));

    render(<TestTree />);

    expect(screen.queryByText('PROTECTED CONTENT')).not.toBeInTheDocument();
    expect(screen.queryByText('LOGIN SCREEN')).not.toBeInTheDocument();

    await waitFor(() => expect(global.fetch).toHaveBeenCalled());
    resolveFetch(refreshRejected());
    await waitFor(() => expect(screen.getByText('LOGIN SCREEN')).toBeInTheDocument());
  });

  it('cold load with no valid cookie redirects an anonymous deep link to /login, with no flash of protected content', async () => {
    global.fetch = vi.fn().mockResolvedValue(refreshRejected());

    render(<TestTree initialPath="/protegido" />);

    await waitFor(() => expect(screen.getByText('LOGIN SCREEN')).toBeInTheDocument());
    expect(screen.queryByText('PROTECTED CONTENT')).not.toBeInTheDocument();
  });

  it('cold load with a live refresh cookie renders the app with no login prompt', async () => {
    global.fetch = vi.fn().mockImplementation(async (url) => {
      const u = String(url);
      if (u.includes('/api/auth/refresh')) return refreshOk();
      if (u.includes('/api/auth/me')) return jsonResponse({ username: 'valeria', roles: ['VIEWER'] });
      throw new Error(`unexpected fetch: ${u}`);
    });

    render(<TestTree initialPath="/protegido" />);

    await waitFor(() => expect(screen.getByText('PROTECTED CONTENT')).toBeInTheDocument());
    expect(screen.queryByText('LOGIN SCREEN')).not.toBeInTheDocument();
  });

  it('anonymous visitor landing directly on /login renders it without a redirect loop', async () => {
    global.fetch = vi.fn().mockResolvedValue(refreshRejected());

    render(<TestTree initialPath="/login" />);

    await waitFor(() => expect(screen.getByText('LOGIN SCREEN')).toBeInTheDocument());
  });
});

describe('AuthGate — Phase 6 password-reset routes are public', () => {
  function ResetRoutesTree({ initialPath }) {
    return (
      <MemoryRouter initialEntries={[initialPath]}>
        <AuthProvider>
          <AuthGate>
            <Routes>
              <Route path="/login" element={<div>LOGIN SCREEN</div>} />
              <Route path="/forgot-password" element={<div>FORGOT PASSWORD SCREEN</div>} />
              <Route path="/reset-password" element={<div>RESET PASSWORD SCREEN</div>} />
            </Routes>
          </AuthGate>
        </AuthProvider>
      </MemoryRouter>
    );
  }

  it('an anonymous visitor lands directly on /forgot-password without a redirect to /login', async () => {
    global.fetch = vi.fn().mockResolvedValue(refreshRejected());

    render(<ResetRoutesTree initialPath="/forgot-password" />);

    await waitFor(() => expect(screen.getByText('FORGOT PASSWORD SCREEN')).toBeInTheDocument());
    expect(screen.queryByText('LOGIN SCREEN')).not.toBeInTheDocument();
  });

  it('an anonymous visitor lands directly on /reset-password without a redirect to /login', async () => {
    global.fetch = vi.fn().mockResolvedValue(refreshRejected());

    render(<ResetRoutesTree initialPath="/reset-password" />);

    await waitFor(() => expect(screen.getByText('RESET PASSWORD SCREEN')).toBeInTheDocument());
    expect(screen.queryByText('LOGIN SCREEN')).not.toBeInTheDocument();
  });
});

describe('AuthGate — network error vs rejected session (design D5 point 5)', () => {
  it('a network-unreachable backend still routes anonymous to /login but is distinguishable via failureReason', async () => {
    global.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));

    let capturedFailureReason;
    function Probe() {
      const { failureReason } = useAuth();
      capturedFailureReason = failureReason;
      return null;
    }

    render(
      <MemoryRouter initialEntries={['/login']}>
        <AuthProvider>
          <AuthGate>
            <Routes>
              <Route path="/login" element={<><div>LOGIN SCREEN</div><Probe /></>} />
            </Routes>
          </AuthGate>
        </AuthProvider>
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText('LOGIN SCREEN')).toBeInTheDocument());
    expect(capturedFailureReason).toBe('network_error');
  });
});

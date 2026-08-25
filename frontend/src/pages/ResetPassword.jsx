// frontend-auth-ui, Phase 6 (design D7 / spec frontend-password-reset).
// The token arrives as `…/reset-password#token=…` — the URL FRAGMENT, never
// the query string — and MUST be stripped via history.replaceState before
// ANY network call whatsoever, or it leaks via Referer to every third-party
// resource the page loads. That strip happens synchronously on mount, well
// before the confirm submit can fire the only network call this screen ever
// makes. See ResetPassword.test.jsx for the ordering pin.
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Loader2 } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';

const BASE = import.meta.env.VITE_API_BASE_URL || '';
const MIN_LENGTH = 8;

function readTokenFromFragment() {
  const hash = window.location.hash || '';
  const params = new URLSearchParams(hash.startsWith('#') ? hash.slice(1) : hash);
  return params.get('token');
}

function ResetShell({ children }) {
  return (
    <section className="bg-bg h-screen">
      <div className="flex h-full items-center justify-center">
        <div className="border-border bg-s2 flex w-full max-w-sm flex-col items-center gap-y-8 rounded-card border px-6 py-12 shadow-md">
          <div className="flex flex-col items-center gap-y-2">
            <h1 className="text-3xl font-semibold text-t1">Restablecer contraseña</h1>
          </div>
          {children}
        </div>
      </div>
    </section>
  );
}

export default function ResetPassword() {
  const [token, setToken] = useState(null);
  const [tokenRead, setTokenRead] = useState(false);
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [success, setSuccess] = useState(false);

  // Strip the fragment before this component does anything else — including
  // before it can ever reach the confirm submit's network call.
  useEffect(() => {
    const t = readTokenFromFragment();
    window.history.replaceState(null, '', window.location.pathname + window.location.search);
    setToken(t);
    setTokenRead(true);
  }, []);

  async function handleSubmit(e) {
    e.preventDefault();
    setError('');

    if (password.length < MIN_LENGTH) {
      setError(`La contraseña debe tener al menos ${MIN_LENGTH} caracteres.`);
      return;
    }
    if (password !== confirmPassword) {
      setError('Las contraseñas no coinciden.');
      return;
    }

    setSubmitting(true);
    let res;
    try {
      res = await fetch(`${BASE}/api/auth/password-reset/confirm`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, password }),
      });
    } catch {
      setSubmitting(false);
      setError('No se pudo contactar al servidor.');
      return;
    }
    setSubmitting(false);

    if (!res.ok) {
      // The backend deliberately does NOT distinguish an unknown, expired, or
      // already-used token from a too-short password — all collapse to the
      // same 400 reseteo_invalido (docs/API_REFERENCE.md: "No se distinguen").
      // This screen must not invent a distinction the backend refuses to give.
      setError('El enlace no sirve, ya fue usado o venció. Pedí uno nuevo.');
      return;
    }

    // A successful reset closes every session of that user server-side —
    // never auto-login here, always send them back to /login.
    setSuccess(true);
  }

  if (!tokenRead) return null;

  if (!token) {
    return (
      <ResetShell>
        <p role="alert" className="text-sm text-danger text-center">
          Este enlace no es válido. Pedí uno nuevo desde{' '}
          <Link to="/forgot-password" className="text-primary font-medium hover:underline">
            recuperar contraseña
          </Link>
          .
        </p>
      </ResetShell>
    );
  }

  if (success) {
    return (
      <ResetShell>
        <p role="status" className="text-sm text-t2 text-center">
          Contraseña cambiada. Todas las sesiones abiertas fueron cerradas — iniciá sesión de nuevo.
        </p>
        <Link to="/login" className="text-primary text-sm font-medium hover:underline">
          Ir a iniciar sesión
        </Link>
      </ResetShell>
    );
  }

  return (
    <ResetShell>
      <form className="flex w-full flex-col gap-8" onSubmit={handleSubmit}>
        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-2">
            <Label htmlFor="reset-password">Nueva contraseña</Label>
            <Input
              id="reset-password"
              type="password"
              name="password"
              autoComplete="new-password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              required
            />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="reset-confirm-password">Confirmar contraseña</Label>
            <Input
              id="reset-confirm-password"
              type="password"
              name="confirmPassword"
              autoComplete="new-password"
              value={confirmPassword}
              onChange={e => setConfirmPassword(e.target.value)}
              required
            />
          </div>

          {error && <p role="alert" className="text-sm text-danger">{error}</p>}

          <div className="flex flex-col gap-4">
            <Button type="submit" className="mt-2 w-full" disabled={submitting}>
              {submitting && <Loader2 className="mr-2 size-4 animate-spin" />}
              Cambiar contraseña
            </Button>
          </div>
        </div>
      </form>
    </ResetShell>
  );
}

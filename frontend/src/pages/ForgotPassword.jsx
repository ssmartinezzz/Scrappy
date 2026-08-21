// frontend-auth-ui, Phase 6 (design D7 / spec frontend-password-reset).
// POST /api/auth/password-reset/request ALWAYS answers 202 with the same
// body, whatever the address — real, unknown, malformed, or an email-less
// service/bootstrap account (docs/API_REFERENCE.md). This screen therefore
// never branches on the submitted address: there is no code path that COULD
// single one out. The bootstrap-admin-has-no-email caveat lives only in
// docs/FRONTEND_AUTH_CONTRACT.md — never here, telling an anonymous visitor
// which accounts are resettable would leak the account model.
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Loader2 } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';

const BASE = import.meta.env.VITE_API_BASE_URL || '';

const CONFIRMATION_MESSAGE =
  'Si la dirección corresponde a una cuenta, va a recibir un enlace para restablecer su contraseña.';

export default function ForgotPassword() {
  const [email, setEmail] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [networkError, setNetworkError] = useState(false);

  async function handleSubmit(e) {
    e.preventDefault();
    setSubmitting(true);
    setNetworkError(false);

    try {
      await fetch(`${BASE}/api/auth/password-reset/request`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email }),
      });
    } catch {
      setSubmitting(false);
      setNetworkError(true);
      return;
    }

    setSubmitting(false);
    // The backend's 202 body is uniform by design; this screen adds no
    // branching of its own on top of it — same message every time.
    setSubmitted(true);
  }

  return (
    <section className="bg-bg h-screen">
      <div className="flex h-full items-center justify-center">
        <div className="border-border bg-s2 flex w-full max-w-sm flex-col items-center gap-y-8 rounded-card border px-6 py-12 shadow-md">
          <div className="flex flex-col items-center gap-y-2">
            <h1 className="text-3xl font-semibold text-t1">Recuperar contraseña</h1>
          </div>

          {networkError && (
            <p role="alert" className="text-sm text-danger text-center">
              No se pudo contactar al servidor.
            </p>
          )}

          {submitted ? (
            <p role="status" className="text-sm text-t2 text-center">
              {CONFIRMATION_MESSAGE}
            </p>
          ) : (
            <form className="flex w-full flex-col gap-8" onSubmit={handleSubmit}>
              <div className="flex flex-col gap-4">
                <div className="flex flex-col gap-2">
                  <Label htmlFor="forgot-email">Email</Label>
                  <Input
                    id="forgot-email"
                    type="email"
                    name="email"
                    autoComplete="email"
                    value={email}
                    onChange={e => setEmail(e.target.value)}
                    required
                  />
                </div>

                <div className="flex flex-col gap-4">
                  <Button type="submit" className="mt-2 w-full" disabled={submitting}>
                    {submitting && <Loader2 className="mr-2 size-4 animate-spin" />}
                    Enviar enlace
                  </Button>
                </div>
              </div>
            </form>
          )}

          <Link to="/login" className="text-primary text-sm font-medium hover:underline">
            Volver a iniciar sesión
          </Link>
        </div>
      </div>
    </section>
  );
}

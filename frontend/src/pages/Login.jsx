// frontend-auth-ui, Phase 5. Ported from the shadcnblocks "login-1"
// reference (Engram #917) to plain JSX (components.json: "tsx": false — no
// TypeScript in this project) with the adaptations design D7 requires:
//   - type="email" -> type="text" name="username": login identifies by
//     username, never email — bootstrap and service accounts have no email
//     (FRONTEND_AUTH_CONTRACT.md).
//   - react-icons -> lucide-react (react-icons is not a project dependency).
//   - Google OAuth button dropped entirely: there is no OAuth provider
//     anywhere in ar.scraper.security, and rendering it disabled would still
//     imply a flow that will never exist (spec: no functional OAuth
//     affordance).
import { useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { Loader2 } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import * as authSession from '../lib/authSession';
import { useAuth } from '../auth/AuthProvider';

export default function Login() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { failureReason } = useAuth();

  async function handleSubmit(e) {
    e.preventDefault();
    setError('');
    setSubmitting(true);
    const result = await authSession.login(username, password);
    setSubmitting(false);

    if (!result.ok) {
      // Every failure looks identical by design (spec: wrong password,
      // unknown username, deactivated account, malformed body — same
      // message). A network error is the one thing kept distinguishable.
      setError(result.networkError
        ? 'No se pudo contactar al servidor.'
        : 'Usuario o contraseña incorrectos.');
      return;
    }

    const from = location.state?.from?.pathname || '/';
    navigate(from, { replace: true });
  }

  return (
    <section className="bg-bg h-screen">
      <div className="flex h-full items-center justify-center">
        <div className="border-border bg-s2 flex w-full max-w-sm flex-col items-center gap-y-8 rounded-card border px-6 py-12 shadow-md">
          <div className="flex flex-col items-center gap-y-2">
            <h1 className="text-3xl font-semibold text-t1">Iniciar sesión</h1>
          </div>

          {/*
            Two different questions, and they can be true at once: this banner
            answers "why am I on the login screen" (the session bootstrap could
            not reach the backend), while the form error below answers "why did
            my submit just fail". With the backend down, both fire with the same
            text — two `role="alert"` nodes saying one thing, which a screen
            reader announces twice. The fresh one wins: once the user has tried
            and been told, the arrival banner has nothing left to add.
          */}
          {failureReason === 'network_error' && !error && (
            <p role="alert" className="text-sm text-danger text-center">
              No se pudo contactar al servidor.
            </p>
          )}

          <form className="flex w-full flex-col gap-8" onSubmit={handleSubmit}>
            <div className="flex flex-col gap-4">
              <div className="flex flex-col gap-2">
                <Label htmlFor="login-username">Usuario</Label>
                <Input
                  id="login-username"
                  type="text"
                  name="username"
                  autoComplete="username"
                  value={username}
                  onChange={e => setUsername(e.target.value)}
                  required
                />
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="login-password">Contraseña</Label>
                <Input
                  id="login-password"
                  type="password"
                  name="password"
                  autoComplete="current-password"
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  required
                />
              </div>

              {error && (
                <p role="alert" className="text-sm text-danger">{error}</p>
              )}

              <div className="flex flex-col gap-4">
                <Button type="submit" className="mt-2 w-full" disabled={submitting}>
                  {submitting && <Loader2 className="mr-2 size-4 animate-spin" />}
                  Ingresar
                </Button>
              </div>
            </div>
          </form>

          <Link to="/forgot-password" className="text-primary text-sm font-medium hover:underline">
            Olvidé mi contraseña
          </Link>
        </div>
      </div>
    </section>
  );
}

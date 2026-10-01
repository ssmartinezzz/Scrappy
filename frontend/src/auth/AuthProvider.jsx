// frontend-auth-ui, Phase 5 (design D5). React MIRROR of authSession.js —
// authSession.js holds the truth (module scope, no React); this context only
// re-renders components when that truth changes. Never the other way
// around: nothing in this file owns state authSession.js doesn't already own.
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';

import * as authSession from '../lib/authSession';

const AuthContext = createContext(null);

function deriveState(snapshot, failureReason) {
  const roles = snapshot.identity?.roles || [];
  return {
    status: snapshot.authenticated ? 'authenticated' : 'anonymous',
    identity: snapshot.identity,
    isAdmin: roles.includes('ADMIN'),
    failureReason: snapshot.authenticated ? null : failureReason,
  };
}

export function AuthProvider({ children }) {
  const [state, setState] = useState({
    status: 'booting',
    identity: null,
    isAdmin: false,
    failureReason: null,
  });

  useEffect(() => {
    let cancelled = false;
    let booted = false;

    // Bootstrap ordering (D5): probe siblings -> ensureFreshSession (no
    // X-Refresh-CSRF, the bootstrap admission path) -> GET /me. Ignore
    // subscribe() pushes that race ahead of bootstrap's own resolution —
    // there shouldn't be any this early, but this guards against it.
    const unsubscribe = authSession.subscribe(snapshot => {
      if (!booted || cancelled) return;
      setState(deriveState(snapshot, authSession.getLastFailureReason()));
    });

    // A bootstrap that rejects must still leave `booting`: the snapshot says what the
    // session is (no token -> unauthenticated), and a stuck gate renders nothing at all.
    const settle = () => {
      booted = true;
      if (cancelled) return;
      setState(deriveState(authSession.getSnapshot(), authSession.getLastFailureReason()));
    };
    authSession.bootstrap().then(settle, settle);

    return () => {
      cancelled = true;
      unsubscribe();
    };
  }, []);

  const logout = useCallback(() => authSession.logout(), []);

  const value = useMemo(
    () => ({ ...state, authenticated: state.status === 'authenticated', logout }),
    [state, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within an AuthProvider');
  return ctx;
}

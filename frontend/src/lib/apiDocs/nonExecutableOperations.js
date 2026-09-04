// swagger-ui-admin-gated — the interactive console's try-it-out deny-list.
// Not a security boundary: an ADMIN already holds a token and curl. Binary,
// no confirmation dialog (Addendum Q1). Exactly 10 entries — the destructive
// or session-mutating ones; limb (b) (start-a-job, all cancellable) was
// released.
export function operationKey(method, path) {
  return `${String(method).toUpperCase()} ${path}`;
}

export const DENY_LIST = [
  {
    key: operationKey('DELETE', '/api/db/productos'),
    reason: 'Empties the catalog and cascades price history. No undo — the 409 guard only '
      + 'blocks this while favorites exist.',
  },
  {
    key: operationKey('DELETE', '/api/db/ml'),
    reason: 'Clears ML-derived data. Only recoverable by a full pipeline re-run.',
  },
  {
    key: operationKey('DELETE', '/api/data'),
    reason: 'Soft-deletes a shared catalog product. No UI action restores it.',
  },
  {
    key: operationKey('POST', '/api/agent/apply'),
    reason: "The LLM agent's only real write path: an UPDATE plus an audit row.",
  },
  {
    key: operationKey('POST', '/api/ml/renormalizar'),
    reason: 'Bulk-rewrites categoria/marca across the persisted catalog.',
  },
  {
    key: operationKey('DELETE', '/api/usuarios/{username}'),
    reason: 'Deactivates an account. Locking a colleague out from a documentation page is '
      + 'still not a documentation action.',
  },
  {
    key: operationKey('PUT', '/api/usuarios/{username}/rol'),
    reason: 'Replaces the role set, never accumulates — a mistyped body demotes an ADMIN.',
  },
  {
    key: operationKey('POST', '/api/auth/login'),
    reason: "Issues a new token pair and sets the refresh cookie over the caller's own live "
      + 'session.',
  },
  {
    key: operationKey('POST', '/api/auth/refresh'),
    reason: 'Not executable from here anyway: authSession.js keeps the X-Refresh-CSRF nonce '
      + 'module-private and never exports it. A live rotation also risks tripping '
      + "RefreshTokenService's reuse detection against the caller's own second tab.",
  },
  {
    key: operationKey('DELETE', '/api/auth/refresh'),
    reason: 'Same nonce requirement as POST — and it logs the caller out of their own session.',
  },
];

const DENY_KEYS = new Set(DENY_LIST.map(entry => entry.key));

export function denyReasonFor(method, path) {
  const entry = DENY_LIST.find(item => item.key === operationKey(method, path));
  return entry ? entry.reason : null;
}

export function isDenied(method, path) {
  return DENY_KEYS.has(operationKey(method, path));
}

// The interactive console's try-it-out deny-list. Not a security boundary:
// whoever can execute these already holds a token and curl. Binary, no
// confirmation dialog (Addendum Q1).
//
// apidocs-public-filtered-document shrank it from 10 entries to 3. The seven
// that left were all `x-access: ADMIN` operations, and the backend now strips
// every one of those from the document it serves: an operation that never
// reaches the page has no panel, no Execute button, and nothing to deny. What
// remains are the three that survive filtering because they are not ADMIN at
// all — the auth operations, which mutate the caller's own session.
//
// The anti-rot guard still checks these keys against the full checked-in
// `docs/openapi.yaml`, not against the filtered response: it exists to catch a
// path rename, and the complete contract is the right place to notice one.
export function operationKey(method, path) {
  return `${String(method).toUpperCase()} ${path}`;
}

export const DENY_LIST = [
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

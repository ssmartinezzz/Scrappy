// Creates the VIEWER this suite drives, through the real API.
//
// Through POST /api/usuarios as an ADMIN rather than by writing SQL: the
// endpoint is the shipped path, and a row inserted behind its back would not
// prove the path works. The account is uniquely named per run, because
// DELETE /api/usuarios/{username} DEACTIVATES rather than deletes (see
// UsuarioAdminEndpoints, "Deactivate, never delete") and a fixed name would
// collide with its own deactivated corpse on the next run.
import crypto from 'node:crypto';

import { API_ORIGIN, APP_ORIGIN, apiLogin, writeAccounts } from './accounts.js';

export default async function globalSetup() {
  const adminUsername = process.env.E2E_ADMIN_USERNAME;
  const adminPassword = process.env.E2E_ADMIN_PASSWORD;

  if (!adminUsername || !adminPassword) {
    throw new Error(
      'E2E_ADMIN_USERNAME / E2E_ADMIN_PASSWORD are unset.\n' +
      'tests/e2e/run-e2e.sh exports both from its generated, gitignored\n' +
      'tests/e2e/.e2e-secrets.env. They are deliberately not defaulted: a\n' +
      'default would be a committed password.'
    );
  }

  let vivo;
  try {
    vivo = await fetch(`${API_ORIGIN}/`);
  } catch (e) {
    throw new Error(`no backend at ${API_ORIGIN} (${e.message}). Start one with tests/e2e/run-e2e.sh.`);
  }
  if (!vivo.ok) throw new Error(`GET ${API_ORIGIN}/ answered ${vivo.status}`);

  const adminToken = await apiLogin(adminUsername, adminPassword);

  const yo = await fetch(`${API_ORIGIN}/api/auth/me`, {
    headers: { Authorization: `Bearer ${adminToken}` },
  });
  const identidad = await yo.json();
  if (!identidad.roles?.includes('ADMIN')) {
    throw new Error(
      `${adminUsername} is not an ADMIN (roles=${JSON.stringify(identidad.roles)}). ` +
      'AdminSeeder leaves an existing username alone, so a pre-existing account ' +
      'with another role would land exactly here.'
    );
  }

  const sufijo = crypto.randomUUID().slice(0, 8);
  const viewer = {
    username: `e2e-ui-viewer-${sufijo}`,
    password: `pw-${crypto.randomUUID()}`,
  };

  const creado = await fetch(`${API_ORIGIN}/api/usuarios`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` },
    body: JSON.stringify({ ...viewer, role: 'VIEWER' }),
  });
  if (creado.status !== 201) {
    throw new Error(`POST /api/usuarios answered ${creado.status}: ${await creado.text()}`);
  }

  writeAccounts({
    admin: { username: adminUsername, password: adminPassword },
    viewer,
    apiOrigin: API_ORIGIN,
    appOrigin: APP_ORIGIN,
  });
}

// Deactivates the VIEWER this run created. Best-effort: a teardown that throws
// would mask the suite's own result, and a leftover deactivated account is
// inert — it cannot log in and it owns nothing.
import fs from 'node:fs';

import { ACCOUNTS_FILE, API_ORIGIN, apiLogin, readAccounts } from './accounts.js';

export default async function globalTeardown() {
  if (!fs.existsSync(ACCOUNTS_FILE)) return;
  try {
    const { admin, viewer } = readAccounts();
    const token = await apiLogin(admin.username, admin.password);
    await fetch(`${API_ORIGIN}/api/usuarios/${viewer.username}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${token}` },
    });
  } catch (e) {
    console.warn(`[e2e] teardown could not deactivate the run's viewer: ${e.message}`);
  }
}

// Shared account bookkeeping for the browser suite.
//
// The suite needs an ADMIN and a VIEWER logged in through the real UI. The
// ADMIN is whichever account tests/e2e/run-e2e.sh seeded (`e2e-admin`), passed
// in by environment — never hardcoded, and in particular never the
// `.env.example` placeholder, which AdminSeeder refuses to seed with. The
// VIEWER is created per run through POST /api/usuarios, the same endpoint an
// operator would use, and deactivated in teardown.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));

export const APP_ORIGIN = process.env.E2E_APP_ORIGIN || 'http://localhost:5173';
export const API_ORIGIN = process.env.E2E_API_BASE_URL || 'http://localhost:3000';

export const ACCOUNTS_FILE = path.join(HERE, '.artifacts', 'accounts.json');

export function readAccounts() {
  if (!fs.existsSync(ACCOUNTS_FILE)) {
    throw new Error(
      `${ACCOUNTS_FILE} is missing — global-setup.js did not run, or it failed.`
    );
  }
  return JSON.parse(fs.readFileSync(ACCOUNTS_FILE, 'utf-8'));
}

export function writeAccounts(data) {
  fs.mkdirSync(path.dirname(ACCOUNTS_FILE), { recursive: true });
  fs.writeFileSync(ACCOUNTS_FILE, JSON.stringify(data, null, 2), { mode: 0o600 });
}

export async function apiLogin(username, password) {
  const res = await fetch(`${API_ORIGIN}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) {
    throw new Error(`login as ${username} answered ${res.status}: ${await res.text()}`);
  }
  return (await res.json()).accessToken;
}

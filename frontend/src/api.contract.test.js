import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const srcDir = dirname(fileURLToPath(import.meta.url));

function sourceFiles(dir = srcDir, acc = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) sourceFiles(full, acc);
    else if (/\.jsx?$/.test(entry) && !/\.test\.jsx?$/.test(entry)) acc.push(full);
  }
  return acc;
}

/**
 * The backend is a separate service on its own origin (design D6), so every
 * call has to carry VITE_API_BASE_URL. A bare `fetch('/api/...')` resolves
 * against the *frontend* origin, which in the Docker topology is nginx on
 * :8080 — and nginx's SPA fallback answers index.html with a 200, so `r.ok`
 * is true and only the later `r.json()` throws. Components swallow that in a
 * `.catch()`, so the feature degrades silently in production while working
 * perfectly in dev, where Vite's proxy hides the bug.
 */
describe('every API call goes through the configured base URL', () => {
  it('has no component calling a bare relative /api path', () => {
    const offenders = sourceFiles()
      .filter(file => relative(srcDir, file) !== 'api.js')
      .flatMap(file => {
        const lines = readFileSync(file, 'utf8').split('\n');
        return lines
          .map((line, i) => ({ line, n: i + 1 }))
          .filter(({ line }) => /fetch\(\s*['"`]\/api\//.test(line))
          .map(({ n, line }) => `${relative(srcDir, file)}:${n}  ${line.trim()}`);
      });

    expect(offenders).toEqual([]);
  });
});

/**
 * frontend-auth-ui design D3, amended by what the Phase 8 browser suite found:
 * `credentials: 'include'` belongs to lib/authSession.js and nowhere else, and
 * inside it there are exactly TWO cookie-bearing calls, not one.
 *
 * The design said one — the refresh — and that was wrong. The **login
 * response** is what plants the refresh cookie, and cross-origin a browser
 * discards `Set-Cookie` from a response whose request was not made in
 * credentials mode. With login left out, the cookie was never stored at all
 * and session recovery on reload could not work in any topology this project
 * ships. It passed every unit test because `vite dev` proxies /api and makes
 * login same-origin — the one topology that never ships.
 *
 * What has not changed is the part worth guarding: api.js's authedFetch() call
 * sites must never set credentials themselves. authedFetch attaches the Bearer
 * token, never cookies.
 */
describe('credentials: include is scoped to authSession.js alone', () => {
  it('api.js never mentions credentials', () => {
    const hits = readFileSync(join(srcDir, 'api.js'), 'utf8')
      .split('\n')
      .map((line, i) => ({ line, n: i + 1 }))
      .filter(({ line }) => /credentials/.test(line));

    expect(hits).toEqual([]);
  });

  it('authedFetch.js never mentions credentials', () => {
    const hits = readFileSync(join(srcDir, 'lib', 'authedFetch.js'), 'utf8')
      .split('\n')
      .map((line, i) => ({ line, n: i + 1 }))
      .filter(({ line }) => /credentials/.test(line));

    expect(hits).toEqual([]);
  });

  it("authSession.js is the only file using credentials: 'include', and uses it exactly twice", () => {
    const occurrencesOutsideAuthSession = sourceFiles()
      .filter(file => relative(srcDir, file) !== join('lib', 'authSession.js'))
      .flatMap(file => {
        const lines = readFileSync(file, 'utf8').split('\n');
        return lines.filter(line => /credentials\s*:\s*['"`]include['"`]/.test(line));
      });
    expect(occurrencesOutsideAuthSession).toEqual([]);

    // Only real code counts — the module's own comments explain the rule in
    // prose and would otherwise inflate this count.
    const codeHits = readFileSync(join(srcDir, 'lib', 'authSession.js'), 'utf8')
      .split('\n')
      .filter(line => !line.trim().startsWith('//'))
      .filter(line => /credentials\s*:\s*['"`]include['"`]/.test(line));
    // Two, and exactly two: the refresh/logout helper (refreshCookieFetch) and
    // login. A third would mean a cookie is travelling somewhere the backend's
    // credentialed-CORS mapping does not cover, which fails silently.
    expect(codeHits).toHaveLength(2);
  });
});

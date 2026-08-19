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
 * frontend-auth-ui design D3: `credentials: 'include'` may appear in exactly
 * ONE place in the whole frontend — the refresh call inside
 * lib/authSession.js. Credentialed CORS is scoped to that route alone
 * (CorsConfig.java / RefreshCookie.PATH); anywhere else it is silently
 * useless cross-origin and a needless widening same-origin. api.js's 57
 * authedFetch() call sites must never set it themselves — authedFetch
 * attaches the Bearer token, never cookies.
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

  it("authSession.js uses credentials: 'include' exactly once across the whole frontend", () => {
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
    expect(codeHits).toHaveLength(1);
  });
});

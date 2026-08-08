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

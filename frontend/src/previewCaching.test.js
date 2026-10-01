import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { preview } from 'vite';

// Runs the real `vite preview` (what the portable and POSIX installs serve) with
// this project's vite.config.js over a throwaway dist.
const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

describe('vite preview caching headers', () => {
  let dist;
  let server;
  let base;

  beforeAll(async () => {
    dist = mkdtempSync(path.join(tmpdir(), 'preview-cache-'));
    mkdirSync(path.join(dist, 'assets'));
    writeFileSync(path.join(dist, 'index.html'), '<!doctype html><title>t</title>');
    writeFileSync(path.join(dist, 'config.js'), 'window.__API_BASE__ = "";');
    writeFileSync(path.join(dist, 'assets', 'index-AbC123.js'), 'console.log(1);');
    server = await preview({
      root: frontendRoot,
      configFile: path.join(frontendRoot, 'vite.config.js'),
      logLevel: 'silent',
      build: { outDir: dist },
      preview: { port: 0, open: false },
    });
    base = `http://localhost:${server.httpServer.address().port}`;
  });

  afterAll(async () => {
    await server?.close();
    rmSync(dist, { recursive: true, force: true });
  });

  it('lets the browser keep a content-hashed asset without revalidating it', async () => {
    const res = await fetch(`${base}/assets/index-AbC123.js`);
    expect(res.status).toBe(200);
    expect(res.headers.get('cache-control')).toBe('public, max-age=31536000, immutable');
  });

  it('does not pin the SPA fallback answered for a chunk that no longer exists', async () => {
    const res = await fetch(`${base}/assets/gone-Old999.js`, { headers: { Accept: 'text/html' } });
    expect(res.headers.get('cache-control')).toBe('no-cache');
  });

  it.each(['/', '/catalogo', '/config.js'])('revalidates %s, which is not content-hashed', async url => {
    const res = await fetch(`${base}${url}`);
    expect(res.status).toBe(200);
    expect(res.headers.get('cache-control')).toBe('no-cache');
  });
});

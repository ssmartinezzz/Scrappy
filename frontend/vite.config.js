import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// decouple-services-postgres scoped correction (verify-report CRITICAL-1):
// spec "Environment-Only Configuration" requires missing required env vars to
// fail fast, not silently default. src/api.js's `BASE = VITE_API_BASE_URL ||
// ''` fallback to '' is a deliberate, safe default ONLY for `vite dev` (the
// Vite dev server proxies '/api' to the backend, see `server.proxy` below).
// For a PRODUCTION build (`vite build`), an unset VITE_API_BASE_URL would
// silently ship a build that only works when served from the same origin as
// the backend — which is no longer guaranteed now that the backend is
// API-only and the frontend is its own deployable service (design D6). Fail
// the build loudly instead.
export default defineConfig(({ command, mode }) => {
  if (command === 'build' && mode !== 'development' && !process.env.VITE_API_BASE_URL) {
    throw new Error(
      'VITE_API_BASE_URL is required for a production build (vite build) — ' +
      'unset only, `vite dev` keeps using the local proxy to localhost:3000. ' +
      'Set it to the backend service origin (see frontend/.env.example) before building.'
    );
  }

  return {
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  build: {
    // Frontend is its own deployable service now (design D6) — it no longer
    // builds into the backend's `scraper/src/main/resources/static` (the
    // backend is API-only, `SpaController`/static serving was removed). Emit to
    // the Vite-default local `dist/` (gitignored) that the frontend service serves.
    outDir:     'dist',
    emptyOutDir: true,
    sourcemap:  false,
    rollupOptions: {
      output: {
        // Chunk names determinísticos para cache del browser
        chunkFileNames:  'assets/[name]-[hash].js',
        entryFileNames:  'assets/[name]-[hash].js',
        assetFileNames:  'assets/[name]-[hash].[ext]'
      }
    }
  },
  server: {
    // En dev, proxy la API a Spring Boot
    proxy: {
      '/api': 'http://localhost:3000'
    }
  }
  };
});

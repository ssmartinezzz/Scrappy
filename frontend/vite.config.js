import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    outDir:     '../scraper/src/main/resources/static',
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
});

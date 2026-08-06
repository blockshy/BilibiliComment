import react from '@vitejs/plugin-react';
import { loadEnv, type Plugin } from 'vite';
import { defineConfig } from 'vitest/config';

function mockDownloadPlugin(enabled: boolean): Plugin {
  return {
    name: 'bilibili-comment-mock-download',
    enforce: 'pre',
    configureServer(server) {
      if (!enabled) return;
      server.middlewares.use((request, response, next) => {
        const path = request.url?.split('?', 1)[0] ?? '';
        if (request.method !== 'GET'
          || !/^\/api\/v1\/comment-exports\/[^/]+\/download$/.test(path)) {
          next();
          return;
        }
        const body = 'rpid,content\r\n9001,fixture\r\n';
        response.statusCode = 200;
        response.setHeader('Cache-Control', 'no-store');
        response.setHeader('Content-Type', 'text/csv; charset=UTF-8');
        response.setHeader('Content-Disposition', 'attachment; filename="bilibili-comments-e2e.csv"');
        response.end(body);
      });
    },
  };
}

export default defineConfig(({ mode }) => ({
  plugins: [
    mockDownloadPlugin(loadEnv(mode, '.', '').VITE_ENABLE_MOCKS === 'true'),
    react(),
  ],
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8111',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.test.{ts,tsx}'],
    testTimeout: 10_000,
    setupFiles: ['./src/test/setup.ts'],
    css: true,
    env: {
      VITE_ENABLE_MOCKS: 'true',
      VITE_APP_ENV: 'DEV',
    },
  },
}));

import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  forbidOnly: true,
  timeout: 30_000,
  expect: {
    timeout: 5_000,
  },
  reporter: 'line',
  outputDir: 'test-results',
  use: {
    baseURL: 'http://127.0.0.1:4177',
    browserName: 'chromium',
    headless: true,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npm run dev:mock -- --host 127.0.0.1 --port 4177',
    url: 'http://127.0.0.1:4177/login',
    reuseExistingServer: false,
    timeout: 30_000,
  },
});

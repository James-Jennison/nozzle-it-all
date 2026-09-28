import { defineConfig, devices } from '@playwright/test';

// End-to-end tests run against the production build (vite preview sends the same isolation headers as hosting) with
// the real slicing engine from public/engine (engine/wasm/scripts/build_engine_fork.sh). Set NOZZLE_E2E_URL to test a
// deployed copy instead (for example https://app.nozzleitall.com).
const live = process.env.NOZZLE_E2E_URL;
export default defineConfig({
  testDir: 'e2e',
  timeout: 180_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: { baseURL: live ?? 'http://127.0.0.1:4173', trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  webServer: live ? undefined : { command: 'npm run build && npx vite preview --host 127.0.0.1', url: 'http://127.0.0.1:4173', timeout: 300_000, reuseExistingServer: false },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
  ],
});

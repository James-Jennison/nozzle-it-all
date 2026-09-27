import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';

// The slicing engine uses threads, which browsers only allow on cross-origin isolated pages. The same headers must be
// sent by whatever hosts the Web App (see hosting/_headers and docs/family/WEB_APP.md).
const isolation = {
  'Cross-Origin-Opener-Policy': 'same-origin',
  // credentialless (not require-corp) so printer camera images can still be shown without the printer sending CORP.
  'Cross-Origin-Embedder-Policy': 'credentialless',
  'Content-Security-Policy': "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; worker-src 'self' blob:; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data: http: https:; connect-src 'self' http: https: ws: wss:; font-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
  'Referrer-Policy': 'no-referrer',
  'X-Content-Type-Options': 'nosniff',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
};

export default defineConfig({
  plugins: [preact()],
  server: { headers: isolation, port: 5173, strictPort: true, fs: { allow: ['..'] } },
  preview: { headers: isolation, port: 4173, strictPort: true },
  worker: { format: 'es' },
  build: { target: 'es2022', sourcemap: true },
});

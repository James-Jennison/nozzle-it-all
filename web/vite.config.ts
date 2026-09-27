import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';
import { cpSync, createReadStream, existsSync, statSync } from 'node:fs';
import { resolve, normalize, sep } from 'node:path';

// Printer profiles are shared with Android and Desktop; the Web App serves the same files under /profiles/ (fetched on
// demand) instead of keeping a copy in the repository.
const PROFILES = resolve(__dirname, '../app/src/main/assets/slicer_profiles');
const profiles = () => ({
  name: 'nozzle-profiles',
  configureServer(server: { middlewares: { use: (fn: (req: any, res: any, next: () => void) => void) => void } }) {
    server.middlewares.use((req, res, next) => {
      const url = (req.url ?? '').split('?')[0];
      if (!url.startsWith('/profiles/')) return next();
      const file = normalize(resolve(PROFILES, '.' + decodeURIComponent(url.slice('/profiles'.length))));
      if (!file.startsWith(PROFILES + sep) || !existsSync(file) || !statSync(file).isFile()) { res.statusCode = 404; return res.end(); }
      res.setHeader('Content-Type', 'application/json'); createReadStream(file).pipe(res);
    });
  },
  writeBundle(opts: { dir?: string }) { cpSync(PROFILES, resolve(opts.dir ?? 'dist', 'profiles'), { recursive: true }); },
});

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
  plugins: [preact(), profiles()],
  server: { headers: isolation, port: 5173, strictPort: true, fs: { allow: ['..'] } },
  preview: { headers: isolation, port: 4173, strictPort: true },
  worker: { format: 'es' },
  build: { target: 'es2022', sourcemap: true },
});

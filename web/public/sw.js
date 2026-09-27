// Nozzle It All Web service worker: makes the app installable and usable offline after the first visit.
// It caches only this app's own files (page, scripts, styles, fonts, icons, the slicing engine). It never caches or
// sees models, projects or printer traffic: those never go to this origin's network at all.
const VERSION = 'nozzle-web-v1';
const SHELL = ['/', '/index.html', '/manifest.webmanifest', '/brand/mark-violet.svg', '/brand/favicon.svg', '/brand/icon-192.png', '/brand/icon-512.png'];

self.addEventListener('install', (e) => { e.waitUntil(caches.open(VERSION).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting())); });
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== self.location.origin) return; // printers and the connector are never intercepted
  const isPage = e.request.mode === 'navigate';
  if (isPage) { // network first, so updates arrive; the cached shell when offline
    e.respondWith(fetch(e.request).then((r) => { const copy = r.clone(); caches.open(VERSION).then((c) => c.put('/index.html', copy)); return r; })
      .catch(() => caches.match('/index.html')));
    return;
  }
  if (/^\/(assets|engine|brand|fonts)\//.test(url.pathname)) { // fingerprinted or versioned: cache first
    e.respondWith(caches.match(e.request).then((hit) => hit || fetch(e.request).then((r) => {
      if (r.ok) { const copy = r.clone(); caches.open(VERSION).then((c) => c.put(e.request, copy)); }
      return r;
    })));
  }
});

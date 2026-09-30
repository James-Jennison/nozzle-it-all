import { useEffect } from 'preact/hooks';
import { useStore } from './store';
import { navigate, projects, store } from './state';
import { glossary } from '../design/glossary';
import { Home } from './pages/Home';
import { Prepare } from './pages/Prepare';
import { Printers } from './pages/Printers';
import { Settings } from './pages/Settings';

const places = [
  { href: '/', label: 'Projects' },
  { href: '/prepare/models', label: 'Prepare' },
  { href: '/printers', label: 'Printers' },
  { href: '/settings', label: 'Settings' },
];

export function App() {
  const s = useStore(store);
  useEffect(() => { projects.list().then((p) => store.set({ projects: p })).catch(() => undefined); projects.requestPersistence(); }, []);
  useEffect(() => {
    const title = s.route.startsWith('/prepare') ? `${s.name} · Prepare` : s.route.startsWith('/printers') ? 'Printers' : s.route.startsWith('/settings') ? 'Settings' : 'Projects';
    document.title = `${title} — ${glossary.product.web}`;
    document.getElementById('main')?.focus({ preventScroll: true });
  }, [s.route]);
  const current = (href: string) => (href === '/' ? s.route === '/' : s.route.startsWith(href.split('/').slice(0, 2).join('/')));
  let page;
  if (s.route.startsWith('/prepare')) page = <Prepare />;
  else if (s.route.startsWith('/printers')) page = <Printers />;
  else if (s.route.startsWith('/settings')) page = <Settings />;
  else page = <Home />;
  return (
    <>
      <a class="skip" href="#main" onClick={(e) => { e.preventDefault(); document.getElementById('main')?.focus(); }}>Skip to content</a>
      <header class="appbar">
        <a class="brand" href="#/" onClick={(e) => { e.preventDefault(); navigate('/'); }}>
          <img src="/brand/mark-violet.svg" alt="" /> Nozzle It All <span class="tag">Web</span>
        </a>
        <nav class="primary-nav" aria-label="Main">
          {places.map((p) => <a href={`#${p.href}`} aria-current={current(p.href) ? 'page' : undefined}>{p.label}</a>)}
        </nav>
        <span class="spacer" />
        <span class="small muted" title="Your models are sliced on this device and not uploaded.">Slices on this device</span>
      </header>
      <main id="main" tabIndex={-1}>{page}</main>
      <footer class="appfoot">
        {glossary.product.web} {'· '}
        <a href="https://nozzleitall.com/" rel="noopener">About Nozzle It All</a> · <a href="https://nozzleitall.com/privacy/" rel="noopener">Privacy</a> · Free software (AGPL-3.0)
      </footer>
    </>
  );
}

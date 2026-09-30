import { useEffect, useState } from 'preact/hooks';
import { useStore } from '../store';
import { engine, projects, savePrinters, store } from '../state';
import { Banner, Field } from '../ui';
import { engineSupport } from '../../engine/client';
import { connector } from '../../printers/connector';
import { glossary } from '../../design/glossary';

function Row({ label, value }: { label: string; value: string }) { return <><dt class="muted">{label}</dt><dd style={{ margin: 0 }}>{value}</dd></>; }

export function Settings() {
  const s = useStore(store);
  const support = engineSupport();
  const [usage, setUsage] = useState<{ used: number; quota: number } | null>(null);
  const [engineName, setEngineName] = useState<string>('');
  const [hello, setHello] = useState<{ ok: boolean; paired: boolean; app?: string; problem?: string } | null>(null);
  const [code, setCode] = useState('');
  const [pairMsg, setPairMsg] = useState<string | null>(null);
  useEffect(() => { projects.usage().then(setUsage); }, []);
  const mb = (n: number) => `${(n / 1024 / 1024).toFixed(1)} MB`;
  return (
    <>
      <div class="page-head"><div class="grow"><h1>Settings</h1><p class="muted">Everything here is stored in this browser only.</p></div></div>
      <div class="grid">
        <section class="card" aria-labelledby="storage-h">
          <h2 id="storage-h">Your projects in this browser</h2>
          <dl class="kv">
            <Row label="Stored in" value={projects.kind === 'opfs' ? "This browser's private file storage" : "This browser's database"} />
            <Row label="Space used" value={usage ? `${mb(usage.used)} of about ${mb(usage.quota)}` : 'Unknown'} />
            <Row label="Kept by the browser" value={projects.persisted ? 'Yes' : 'Only while there is space; export important projects'} />
          </dl>
          <p class="small muted">Clearing this site's data in your browser removes these projects. Export a 3MF to keep a copy.</p>
        </section>
        <section class="card" aria-labelledby="engine-h">
          <h2 id="engine-h">Slicing on this device</h2>
          <p class="small muted">{glossary.explanations['web-local']}</p>
          <dl class="kv">
            <Row label="WebAssembly" value={typeof WebAssembly !== 'undefined' ? 'Available' : 'Not available'} />
            <Row label="Parallel slicing" value={support.threads ? 'Available' : 'Not available on this page'} />
            <Row label="Engine" value={engineName || 'Loads when you first slice'} />
          </dl>
          {!support.ok && <Banner kind="warning">{support.reasons.join(' ')}</Banner>}
          <div class="btn-row"><button class="btn" onClick={async () => { try { setEngineName(await engine.warmUp()); } catch (e) { setEngineName(`Couldn't start: ${(e as Error).message}`); } }}>Check the engine</button></div>
        </section>
        <section class="card" aria-labelledby="conn-h">
          <h2 id="conn-h">Local connector</h2>
          <p class="small muted">If this browser can't reach your printer directly, Nozzle It All for Desktop can pass requests to it from this computer. It only talks to printers you added in Desktop, and nothing leaves your network.</p>
          <div class="btn-row"><button class="btn" onClick={async () => setHello(await connector.hello())}>Look for Nozzle It All for Desktop</button></div>
          {hello && (hello.ok ? <Banner kind="success">Found {hello.app}. {hello.paired ? 'This browser is paired.' : 'Enter the pairing code shown in Desktop under Settings → Pair a browser.'}</Banner> : <Banner kind="warning">{hello.problem}</Banner>)}
          {hello?.ok && !hello.paired && <>
            <Field id="pair" label="Pairing code" value={code} onInput={setCode} inputMode="numeric" />
            <div class="btn-row"><button class="btn primary" disabled={code.length < 6} onClick={async () => {
              const ok = await connector.pair(code);
              setPairMsg(ok ? 'Paired.' : "That code didn't work. Codes last two minutes; make a new one in Desktop.");
              if (ok) { setHello(await connector.hello()); const list = await connector.printers(); const existing = new Set(s.printers.map((p) => p.id)); const printers = [...s.printers, ...list.filter((p) => !existing.has(p.id))]; savePrinters(printers); store.set({ printers }); }
            }}>Pair</button></div>
            {pairMsg && <p aria-live="polite">{pairMsg}</p>}
          </>}
          {hello?.paired && <div class="btn-row"><button class="btn quiet" onClick={() => { connector.forget(); setHello(null); }}>Forget pairing</button></div>}
        </section>
        <section class="card" aria-labelledby="privacy-h">
          <h2 id="privacy-h">Privacy</h2>
          <p class="small">{glossary.explanations.cloud}</p>
          <p class="small">{glossary.explanations.paxx}</p>
          <p class="small muted">This page's files come from nozzleitall.com. After that, models, projects and printer details stay in this browser, and printer traffic goes from this browser (or your local connector) straight to your printer.</p>
        </section>
      </div>
    </>
  );
}

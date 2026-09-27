import { useEffect, useMemo, useState } from 'preact/hooks';
import { useStore } from '../store';
import { navigate, savePrinters, store } from '../state';
import { Banner, Confirm, Field, Notice, Progress, RouteBadge, StatusPill, fmtDuration } from '../ui';
import { ActionGuard, MoonrakerClient } from '../../printers/paxx';
import { Action, Capabilities, directCapabilities, PrinterStatus, SavedPrinter, allowedStates, familyDescription, familyLabel, fullSpectrumOf, readCapabilities, routeFor, stateDescription, summary } from '../../printers/model';
import { ConnectorClient, connector } from '../../printers/connector';
import { glossary } from '../../design/glossary';

export interface LivePrinter { saved: SavedPrinter; client: MoonrakerClient | ConnectorClient; guard: ActionGuard; status: PrinterStatus | null; capabilities: Capabilities }

const cache = new Map<string, { client: MoonrakerClient | ConnectorClient; guard: ActionGuard }>();
function clientFor(p: SavedPrinter) {
  const key = `${p.id}|${p.address}|${p.via}|${p.apiKey ?? ''}`;
  let c = cache.get(key);
  if (!c) { const client = p.via === 'connector' ? new ConnectorClient(p) : new MoonrakerClient(p); c = { client, guard: new ActionGuard(client) }; cache.set(key, c); }
  return c;
}

/** Live status for one saved printer, polled while the page shows it. Read-only: nothing here changes the printer. */
export function usePrinter(id: string | null): LivePrinter | null {
  const s = useStore(store);
  const saved = s.printers.find((p) => p.id === id) ?? null;
  const [status, setStatus] = useState<PrinterStatus | null>(null);
  const [caps, setCaps] = useState<Capabilities>(readCapabilities(undefined));
  const c = useMemo(() => (saved ? clientFor(saved) : null), [saved?.id, saved?.address, saved?.via, saved?.apiKey]);
  useEffect(() => {
    if (!c) return;
    let stop = false;
    // Direct printers speak Moonraker; through the connector, Desktop's adapter for the printer says what it can do.
    let known = false;
    if (!(c.client instanceof ConnectorClient)) { setCaps(directCapabilities(saved!.family)); known = true; }
    const tick = async () => {
      if (!known && c.client instanceof ConnectorClient) { const k = await c.client.capabilities(); if (k && !stop) { setCaps(k); known = true; } }
      const st = await c.client.status(); if (!stop) { setStatus(st); setTimeout(tick, st.state === 'printing' || st.state === 'paused' ? 2000 : 5000); } };
    tick();
    return () => { stop = true; };
  }, [c]);
  return saved && c ? { saved, client: c.client, guard: c.guard, status, capabilities: caps } : null;
}

export function Printers() {
  const s = useStore(store);
  const id = s.route.split('/')[2];
  if (id === 'add') return <AddPrinter />;
  if (id) return <PrinterDetail id={id} />;
  return (
    <>
      <div class="page-head">
        <div class="grow"><h1>Printers</h1><p class="muted">{glossary.explanations['remote-access']}</p></div>
        <button class="btn primary" onClick={() => navigate('/printers/add')} data-testid="add-printer">Add printer</button>
      </div>
      <Notice />
      {s.printers.length === 0 ? (
        <section class="card empty"><img src="/brand/mark-violet.svg" alt="" /><h2>No printers yet</h2>
          <p class="muted">{glossary.explanations['first-printer']}</p><button class="btn primary" onClick={() => navigate('/printers/add')}>Add printer</button></section>
      ) : <ul class="grid" style={{ listStyle: 'none', padding: 0, margin: 0 }}>{s.printers.map((p) => <PrinterCard p={p} />)}</ul>}
    </>
  );
}

function PrinterCard({ p }: { p: SavedPrinter }) {
  const live = usePrinter(p.id);
  const st = live?.status;
  return (
    <li class="card" style={{ borderTop: `4px solid var(--status-${st?.state ?? 'connecting'})` }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}><h2 style={{ flex: 1 }}>{p.name}</h2>{st ? <StatusPill state={st.state} /> : <StatusPill state="connecting" />}</div>
      <p class="small muted">{p.model} · {familyLabel(p.family)} · {p.via === 'connector' ? 'through the local connector' : 'direct'}</p>
      {st?.job ? <><p>{st.job.fileName}</p><Progress value={st.job.fraction} label="Print progress" /></> : <p class="small muted">{st?.message ?? (st ? stateDescription(st.state) : 'Connecting…')}</p>}
      {st && <RouteBadge route={st.route} />}
      <div class="btn-row"><a class="btn" href={`#/printers/${p.id}`}>Open</a></div>
    </li>
  );
}

function AddPrinter() {
  const s = useStore(store);
  const [address, setAddress] = useState('');
  const [name, setName] = useState('');
  const [key, setKey] = useState('');
  const [via, setVia] = useState<'direct' | 'connector'>(location.protocol === 'https:' ? 'connector' : 'direct');
  const [family, setFamily] = useState<string>('paxx-u1');
  const direct = DIRECT_FAMILIES.includes(family);
  const [checking, setChecking] = useState(false);
  const [result, setResult] = useState<PrinterStatus | null>(null);
  const model = family === 'klipper' ? 'Klipper printer' : 'Snapmaker U1';
  const draft = (): SavedPrinter => ({ id: crypto.randomUUID(), name: name || model, model, family, address: normalise(address), via, apiKey: key || undefined,
    profileId: family === 'klipper' ? 'generic_klipper' : 'snapmaker_u1' });
  const normalise = (a: string) => (a.includes('://') ? a.trim() : `http://${a.trim()}`);
  return (
    <>
      <div class="page-head"><div class="grow"><h1>Add printer</h1><p class="muted">Nozzle It All connects to your printer directly. Nothing goes through a Nozzle server.</p></div></div>
      <div class="card" style={{ maxWidth: 640 }}>
        <fieldset style={{ border: 0, padding: 0 }}>
          <legend class="small">Printer family</legend>
          <div class="seg" role="group">{ADDABLE_FAMILIES.map((f) => <button type="button" aria-pressed={family === f} onClick={() => setFamily(f)}>{familyLabel(f)}</button>)}</div>
          <p class="small muted">{familyDescription(family)}</p>
        </fieldset>
        {!direct ? <ConnectorOnly family={family} /> : <>
        <Field id="addr" label="Printer address" value={address} onInput={setAddress} placeholder="192.168.1.40 or u1.your-tailnet.ts.net"
          hint="At home, the printer's local address. Away from home, its address on your private network (for example Tailscale)." />
        <Field id="name" label="Name" value={name} onInput={setName} placeholder="Workshop U1" />
        <fieldset style={{ border: 0, padding: 0, display: 'grid', gap: 8 }}>
          <legend class="small">How this browser reaches it</legend>
          <label class="toggle"><input type="radio" name="via" checked={via === 'direct'} onChange={() => setVia('direct')} /> Directly</label>
          <label class="toggle"><input type="radio" name="via" checked={via === 'connector'} onChange={() => setVia('connector')} /> Through Nozzle's local connector (Nozzle It All for Desktop)</label>
          <p class="small muted">Browsers restrict secure web pages from talking to local devices. The local connector runs on your own computer and passes requests only to printers you added; nothing leaves your network.</p>
        </fieldset>
        {family === 'stock-u1' && <Banner>{glossary.explanations['stock-u1']} In this browser, Stock U1 printers can be monitored and sent jobs over your network; Snapmaker account features aren't available here.</Banner>}
        <Field id="key" label="API key (only if your printer requires one)" type="password" value={key} onInput={setKey} hint="Kept only in this browser." />
        <div class="btn-row">
          <button class="btn" disabled={!address || checking} onClick={async () => { setChecking(true); const d = draft(); const c = d.via === 'connector' ? new ConnectorClient(d) : new MoonrakerClient(d); setResult(await c.status()); setChecking(false); }}>{checking ? 'Checking…' : 'Check connection'}</button>
          <button class="btn primary" disabled={!address} data-testid="save-printer" onClick={() => { const p = draft(); const printers = [...s.printers, p]; savePrinters(printers); store.set({ printers, printerId: s.printerId ?? p.id }); navigate('/printers'); }}>Add printer</button>
        </div>
        </>}
        {result && (result.state === 'offline' ? <Banner kind="warning">{result.message}</Banner> : <Banner kind="success">Connected: the printer is {glossary.terms[`state.${result.state}` as const].label.toLowerCase()} ({result.route === 'private-network' ? 'private network' : 'local network'}).</Banner>)}
      </div>
    </>
  );
}

/** Families a browser can reach directly: Moonraker, the same local HTTP API on PAXX, Stock U1 and Klipper printers. */
const DIRECT_FAMILIES = ['paxx-u1', 'stock-u1', 'klipper'];
const ADDABLE_FAMILIES = ['paxx-u1', 'klipper', 'stock-u1', 'bambu-lab', 'prusa', 'octoprint'];

/** Other makers' printers are added in Nozzle It All for Desktop and reached from here through its local connector. */
function ConnectorOnly({ family }: { family: string }) {
  const s = useStore(store);
  const [problem, setProblem] = useState<string | null>(null);
  const [found, setFound] = useState<SavedPrinter[] | null>(null);
  return (
    <>
      <p>A browser can't talk to {familyLabel(family)} printers by itself. Add the printer in Nozzle It All for Desktop, pair this browser with it in Settings, then bring it in here.
        Printer passwords and access codes stay in Desktop; this browser never sees them.</p>
      <p class="small muted">You can still prepare and slice for any {familyLabel(family)} printer without connecting: choose its profile when you prepare a plate.</p>
      <div class="btn-row"><button class="btn" onClick={async () => {
        setProblem(null);
        try { setFound((await connector.printers()).filter((p) => p.family === family && !s.printers.some((x) => x.id === p.id))); } catch (e) { setProblem((e as Error).message); }
      }}>Find {familyLabel(family)} printers in Desktop</button></div>
      {problem && <Banner kind="warning">{problem}</Banner>}
      {found && (found.length === 0 ? <p class="muted">Desktop has no {familyLabel(family)} printers this browser hasn't added.</p> : found.map((p) => (
        <div class="btn-row"><span style={{ flex: 1 }}>{p.name} · {p.model}</span>
          <button class="btn primary" onClick={() => { const printers = [...s.printers, p]; savePrinters(printers); store.set({ printers, printerId: s.printerId ?? p.id }); navigate('/printers'); }}>Add</button></div>)))}
    </>
  );
}

function PrinterDetail({ id }: { id: string }) {
  const s = useStore(store);
  const live = usePrinter(id);
  const [pending, setPending] = useState<Action | null>(null);
  const [message, setMessage] = useState<{ text: string; kind: 'success' | 'warning' | 'danger' } | null>(null);
  const [snap, setSnap] = useState(0);
  useEffect(() => { const t = setInterval(() => setSnap((n) => n + 1), 3000); return () => clearInterval(t); }, []);
  if (!live) return <Banner kind="warning">That printer isn't saved in this browser. <a href="#/printers">Back to printers</a></Banner>;
  const st = live.status;
  const blocked = live.guard.needsReconcile;
  const k = live.capabilities;
  const supported: Record<Action['kind'], boolean> = { start: k.start_print, pause: k.pause_print, resume: k.resume_print, cancel: k.cancel_print, home: k.motion, nozzleTemperature: k.temperatures, bedTemperature: k.temperatures };
  const offer = (a: Action) => !!st && !blocked && supported[a.kind] && allowedStates[a.kind].includes(st.state);
  const term = pending ? glossary.terms[({ start: 'action.start', pause: 'action.pause', resume: 'action.resume', cancel: 'action.cancel', nozzleTemperature: 'action.heat', bedTemperature: 'action.heat', home: 'action.home' } as const)[pending.kind]] : null;
  const cam = live.saved.via === 'direct' && k.camera ? `${live.saved.address.replace(/\/+$/, '')}/webcam/snapshot.jpg?n=${snap}` : null;
  return (
    <>
      <div class="page-head">
        <div class="grow"><h1>{live.saved.name}</h1><p class="small muted">{live.saved.model} · {familyLabel(live.saved.family)} · {live.saved.address}</p></div>
        {st && <StatusPill state={st.state} />}
        <button class="btn quiet" onClick={() => { if (confirm(`Remove ${live.saved.name} from this browser?`)) { const printers = s.printers.filter((p) => p.id !== id); savePrinters(printers); store.set({ printers }); navigate('/printers'); } }}>Remove</button>
      </div>
      {message && <Banner kind={message.kind} action={blocked ? { label: 'Check printer', onClick: async () => { const r = await live.guard.reconcile(); setMessage(live.guard.needsReconcile ? { text: `The printer still can't be checked (${r.state}). Commands stay blocked.`, kind: 'warning' } : { text: `Checked: the printer is ${r.state}. You can send commands again.`, kind: 'success' }); } } : { label: 'Dismiss', onClick: () => setMessage(null) }}>{message.text}</Banner>}
      <div class="split">
        <section class="card" aria-label="Camera and job">
          <h2>Camera</h2>
          {cam ? <img src={cam} alt={`Camera view of ${live.saved.name}`} style={{ width: '100%', borderRadius: 12, background: 'var(--surface-sunken)', minHeight: 180 }} crossOrigin="anonymous" onError={(e) => ((e.target as HTMLImageElement).alt = 'Camera image unavailable in this browser. Open it on your printer or use Nozzle It All for Desktop.')} />
            : <p class="muted">Camera images through the local connector appear in Nozzle It All for Desktop.</p>}
          <h2>Current job</h2>
          {st?.job ? <><p>{st.job.fileName}</p><Progress value={st.job.fraction} label="Print progress" />
            <div class="metrics"><div><span class="small muted">Done</span><span class="metric">{Math.round(st.job.fraction * 100)}%</span></div>
              <div><span class="small muted">Elapsed</span><span class="metric">{fmtDuration(st.job.elapsed)}</span></div>
              {st.job.layers && <div><span class="small muted">Layer</span><span class="metric">{st.job.layer ?? 0} of {st.job.layers}</span></div>}</div></>
            : <p class="muted">{st?.state === 'finished' ? 'The last job finished.' : 'Nothing is printing.'}</p>}
        </section>
        <section class="card" aria-label="Status and controls">
          <h2>Status</h2>
          {st ? <><p class="small muted">{st.message ?? stateDescription(st.state)}</p><RouteBadge route={st.route} /></> : <p class="muted">Connecting…</p>}
          {st?.toolheads.length ? <><h3>Toolheads</h3>{st.toolheads.map((t) => (
            <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
              <span class="swatch" style={{ background: t.material?.colorHex ?? 'transparent' }} aria-hidden="true" />
              <span style={{ flex: 1 }}>Toolhead {t.index + 1}{t.active ? ' (active)' : ''} · {t.loaded ? `${t.material?.vendor ?? ''} ${t.material?.type ?? ''}`.trim() || 'loaded' : 'empty'}</span>
              <span class="metric-small">{t.nozzle?.toFixed(0) ?? '–'} °C</span>
            </div>))}</> : null}
          {st && fullSpectrumOf(st)?.available && <p class="small">Full Spectrum: {fullSpectrumOf(st)!.palette.length} colours available to mix.</p>}
          <h3>Controls</h3>
          {blocked && <p class="small" style={{ color: 'var(--status-paused)' }}>Commands are paused until Nozzle has checked the printer.</p>}
          {/* Only controls this printer supports are shown; the rest are absent, not greyed out. */}
          {Object.values(supported).some(Boolean) ? <div class="btn-row">
            {supported.pause && <button class="btn" disabled={!offer({ kind: 'pause' })} onClick={() => setPending({ kind: 'pause' })}>Pause</button>}
            {supported.resume && <button class="btn" disabled={!offer({ kind: 'resume' })} onClick={() => setPending({ kind: 'resume' })}>Resume</button>}
            {supported.cancel && <button class="btn danger" disabled={!offer({ kind: 'cancel' })} onClick={() => setPending({ kind: 'cancel' })}>Cancel print</button>}
            {supported.home && <button class="btn" disabled={!offer({ kind: 'home' })} onClick={() => setPending({ kind: 'home' })}>Home</button>}
            {supported.bedTemperature && <button class="btn quiet" disabled={!offer({ kind: 'bedTemperature', celsius: 0 })} onClick={() => setPending({ kind: 'bedTemperature', celsius: 0 })}>Bed heater off</button>}
          </div> : <p class="small muted">Nozzle It All can show this printer's status, but it doesn't control it.</p>}
        </section>
      </div>
      <Confirm open={!!pending} title={`${term?.confirm ?? ''} on ${live.saved.name}?`} body={pending ? summary(pending) : ''} confirmLabel={term?.confirm ?? 'Send'} destructive={(term as { destructive?: boolean } | null)?.destructive}
        detail="Nozzle checks the printer's state again before sending, and never repeats a command on its own."
        onCancel={() => setPending(null)} onConfirm={async () => {
          const a = pending!; setPending(null);
          const out = await live.guard.execute(a, st!.state, allowedStates[a.kind]);
          setMessage(out.kind === 'accepted' ? { text: `${glossary.terms['outcome.accepted'].label}: ${summary(a)}`, kind: 'success' }
            : out.kind === 'unknown' ? { text: `${glossary.terms['outcome.unknown'].label}. ${out.reason}`, kind: 'danger' } : { text: `${glossary.terms['outcome.rejected'].label}. ${out.reason}`, kind: 'warning' });
        }} />
    </>
  );
}

export { routeFor, connector };

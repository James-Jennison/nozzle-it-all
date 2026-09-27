import { useEffect, useRef, useState } from 'preact/hooks';
import { useStore } from '../store';
import { arrange, changed, chooseProfile, engine, exportProject, importFiles, itemBounds, navigate, notify, outOfBounds, saveProject, slice, store } from '../state';
import { Viewer } from '../Viewer';
import { Banner, Confirm, Field, Notice, Progress, StatusPill, fmtDuration } from '../ui';
import { GUIDED_PRESETS } from '../../project/slicing';
import { download } from '../../storage/projects';
import { usePrinter } from './Printers';
import { allowedStates, familyLabel, summary } from '../../printers/model';
import { ProfileInfo, profileIndex, searchProfiles } from '../../project/profiles';
import { glossary } from '../../design/glossary';

const steps = [
  { id: 'models', label: 'Models and plate' },
  { id: 'printer', label: 'Printer and materials' },
  { id: 'settings', label: 'Settings' },
  { id: 'slice', label: 'Slice and preview' },
  { id: 'send', label: 'Export or send' },
];

export function Prepare() {
  const s = useStore(store);
  const step = s.route.split('/')[2] || 'models';
  const colors = s.slots.map((m) => m.colorHex ?? '#A78BFA');
  const done = s.slice.kind === 'done' ? s.slice : null;
  const showPreview = step === 'slice' && !!done;
  return (
    <>
      <div class="page-head">
        <div class="grow">
          <label class="visually-hidden" for="project-name">Project name</label>
          <input id="project-name" class="field" style={{ font: 'var(--type-headline)', background: 'transparent', border: 0, color: 'var(--text)', padding: 0, width: '100%' }}
            value={s.name} onInput={(e) => store.set({ name: (e.target as HTMLInputElement).value, dirty: true })} />
          <p class="small muted" aria-live="polite">{s.dirty ? 'Unsaved changes' : s.projectId ? 'Saved in this browser' : 'Not saved yet'}</p>
        </div>
        <div class="btn-row">
          <button class="btn primary" disabled={s.items.length === 0} onClick={async () => { try { await saveProject(); notify('Saved in this browser.', 'success'); } catch (e) { notify(`Couldn't save: ${(e as Error).message}`, 'danger'); } }} data-testid="save">Save</button>
          <button class="btn" disabled={s.items.length === 0} onClick={exportProject}>Export 3MF</button>
        </div>
      </div>
      <nav aria-label="Prepare steps"><ol class="steps">
        {steps.map((st) => <li><a href={`#/prepare/${st.id}`} aria-current={st.id === step ? 'step' : undefined}>{st.label}</a></li>)}
      </ol></nav>
      <Notice />
      <div class="split">
        <section aria-label={showPreview ? 'Layer preview' : 'Build plate'}>
          <Viewer key={s.profile.id} items={s.items} selected={s.selected} colors={colors} preview={showPreview ? done!.preview : undefined} layer={s.previewLayer}
            onSelect={(id) => store.set({ selected: id })} label={showPreview ? `Layer ${s.previewLayer + 1} of ${done!.preview.layers.length}` : `Build plate with ${s.items.length} objects`} />
          {showPreview && done!.preview.layers.length > 0 && (
            <div class="field" style={{ marginTop: 12 }}>
              <label for="layer">Layer {s.previewLayer + 1} of {done!.preview.layers.length} · Z {done!.preview.layers[s.previewLayer]?.z.toFixed(2)} mm</label>
              <input id="layer" class="layer-slider" type="range" min={0} max={done!.preview.layers.length - 1} value={s.previewLayer}
                onInput={(e) => store.set({ previewLayer: Number((e.target as HTMLInputElement).value) })} />
            </div>
          )}
        </section>
        <section aria-label={steps.find((x) => x.id === step)?.label}>
          {step === 'models' && <ModelsStep />}
          {step === 'printer' && <PrinterStep />}
          {step === 'settings' && <SettingsStep />}
          {step === 'slice' && <SliceStep />}
          {step === 'send' && <SendStep />}
        </section>
      </div>
    </>
  );
}

function Next({ to, label }: { to: string; label: string }) { return <div class="btn-row"><button class="btn primary" onClick={() => navigate(`/prepare/${to}`)}>{label}</button></div>; }

function ModelsStep() {
  const s = useStore(store);
  const ref = useRef<HTMLInputElement>(null);
  const sel = s.items.find((i) => i.id === s.selected);
  const set = (patch: Partial<typeof sel>) => changed({ items: s.items.map((i) => (i.id === s.selected ? { ...i, ...patch } : i)) });
  const off = outOfBounds();
  return (
    <div class="card">
      <h2>Models on the plate</h2>
      <div class="btn-row">
        <button class="btn" onClick={() => ref.current?.click()} data-testid="add-model">Add model</button>
        <button class="btn" disabled={s.items.length === 0} onClick={arrange}>Arrange</button>
      </div>
      <input ref={ref} type="file" accept=".stl,.obj,.3mf" multiple hidden aria-label="Choose models" onChange={async (e) => { const f = (e.target as HTMLInputElement).files; if (f) await importFiles(f); (e.target as HTMLInputElement).value = ''; }} />
      {s.items.length === 0 ? <p class="muted">Add an STL, OBJ or 3MF file to start.</p> : (
        <div class="list" role="group" aria-label="Objects">
          {s.items.map((i) => { const b = itemBounds(i); return (
            <button class="item" aria-pressed={i.id === s.selected} onClick={() => store.set({ selected: i.id === s.selected ? null : i.id })}>
              <span class="swatch" style={{ background: s.slots[i.slot - 1]?.colorHex, width: 16, height: 16 }} aria-hidden="true" />
              <span style={{ flex: 1 }}>{i.name}</span><span class="metric-small muted">{b.w.toFixed(0)}×{b.d.toFixed(0)}×{b.h.toFixed(0)} mm</span>
            </button>); })}
        </div>
      )}
      {off.length > 0 && <Banner kind="warning">Off the plate: {off.map((o) => o.name).join(', ')}. Move it or use Arrange.</Banner>}
      {sel && (
        <div class="card raised" aria-label={`Placement of ${sel.name}`}>
          <h3>{sel.name}</h3>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 8 }}>
            <Field id="x" label="X mm" value={sel.x.toFixed(1)} inputMode="decimal" onInput={(v) => Number.isFinite(parseFloat(v)) && set({ x: parseFloat(v) })} />
            <Field id="y" label="Y mm" value={sel.y.toFixed(1)} inputMode="decimal" onInput={(v) => Number.isFinite(parseFloat(v)) && set({ y: parseFloat(v) })} />
            <Field id="r" label="Turn °" value={sel.rotZ.toFixed(0)} inputMode="decimal" onInput={(v) => Number.isFinite(parseFloat(v)) && set({ rotZ: parseFloat(v) })} />
            <Field id="sc" label="Size %" value={(sel.scale * 100).toFixed(0)} inputMode="decimal" onInput={(v) => parseFloat(v) > 0 && set({ scale: parseFloat(v) / 100 })} />
          </div>
          <div class="btn-row">
            <button class="btn" onClick={() => { const id = Math.max(...s.items.map((i) => i.id)) + 1; changed({ items: [...s.items, { ...sel, id, name: `${sel.name} copy`, x: sel.x + 10, y: sel.y + 10 }] }); arrange(); }}>Duplicate</button>
            <button class="btn quiet" onClick={() => changed({ items: s.items.filter((i) => i.id !== sel.id), selected: null })}>Remove</button>
          </div>
        </div>
      )}
      <Next to="printer" label="Next: printer and materials" />
    </div>
  );
}

function PrinterStep() {
  const s = useStore(store);
  const printer = usePrinter(s.printerId);
  const heads = printer?.status?.toolheads ?? [];
  return (
    <div class="card">
      <h2>Printer and materials</h2>
      {s.printers.length > 0 && (
        <div class="seg" role="group" aria-label="Printer">
          <button aria-pressed={!s.printerId} onClick={() => changed({ printerId: null })}>None (export)</button>
          {s.printers.map((p) => <button aria-pressed={(s.printerId ?? '') === p.id} onClick={() => { changed({ printerId: p.id }); if (p.profileId && p.profileId !== s.profile.id) void chooseProfile(p.profileId); }}>{p.name}</button>)}
        </div>
      )}
      <ProfilePicker />
      {heads.length > 0 && (
        <div class="btn-row"><button class="btn" onClick={() => changed({ slots: heads.map((h) => ({ slot: h.index + 1, type: h.material?.type ?? 'PLA', vendor: h.material?.vendor, subType: h.material?.subType, colorHex: h.material?.colorHex ?? '#FFFFFF', toolhead: h.index })) })}>
          Use what's loaded in {printer!.saved.name}</button></div>
      )}
      <h3>Materials</h3>
      {s.slots.map((m, idx) => (
        <div style={{ display: 'flex', gap: 10, alignItems: 'end' }}>
          <span class="swatch" style={{ background: m.colorHex }} aria-hidden="true" />
          <Field id={`slot-${m.slot}-color`} label={`Material ${m.slot} colour`} value={m.colorHex ?? ''} onInput={(v) => /^#[0-9a-fA-F]{6}$/.test(v) && changed({ slots: s.slots.map((x, j) => (j === idx ? { ...x, colorHex: v.toUpperCase() } : x)) })} />
          <Field id={`slot-${m.slot}-type`} label="Type" value={m.type ?? ''} onInput={(v) => changed({ slots: s.slots.map((x, j) => (j === idx ? { ...x, type: v.toUpperCase() } : x)) })} />
        </div>
      ))}
      {s.items.length > 0 && <>
        <h3>Which material each object uses</h3>
        {s.items.map((i) => (
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <span style={{ flex: 1 }}>{i.name}</span>
            <div class="seg" role="group" aria-label={`Material for ${i.name}`}>
              {s.slots.map((m) => <button aria-pressed={i.slot === m.slot} onClick={() => changed({ items: s.items.map((x) => (x.id === i.id ? { ...x, slot: m.slot } : x)) })}>{m.slot}</button>)}
            </div>
          </div>
        ))}
      </>}
      <Next to="settings" label="Next: settings" />
    </div>
  );
}

const ADVANCED = [
  { key: 'wall_loops', label: 'Wall count', hint: 'Walls around each layer. More is stronger.' },
  { key: 'top_shell_layers', label: 'Top layers', hint: 'Solid layers on top.' },
  { key: 'bottom_shell_layers', label: 'Bottom layers', hint: 'Solid layers underneath.' },
  { key: 'brim_width', label: 'Brim width (mm)', hint: 'Helps tall or small objects stick.' },
  { key: 'seam_position', label: 'Seam position', hint: 'aligned, nearest, back or random.' },
];

function SettingsStep() {
  const s = useStore(store);
  const [adv, setAdv] = useState(Object.keys(s.advanced).length > 0);
  return (
    <div class="card">
      <h2>How it prints</h2>
      <div class="seg" role="group" aria-label="Quality">
        {GUIDED_PRESETS.map((p) => <button aria-pressed={s.preset === p.id} onClick={() => changed({ preset: p.id })}>{p.label}</button>)}
      </div>
      <p class="small muted">{GUIDED_PRESETS.find((p) => p.id === s.preset)?.detail}</p>
      <label class="toggle"><input type="checkbox" checked={s.supports} onChange={(e) => changed({ supports: (e.target as HTMLInputElement).checked })} /> Supports under overhangs</label>
      <div class="seg" role="group" aria-label="Infill">
        {[10, 15, 25, 40].map((v) => <button aria-pressed={s.infill === v} onClick={() => changed({ infill: v })}>{v}% infill</button>)}
      </div>
      <button class="btn quiet" aria-expanded={adv} onClick={() => setAdv(!adv)}>{adv ? 'Hide' : 'Show'} advanced settings</button>
      {adv && ADVANCED.map((a) => <Field id={`adv-${a.key}`} label={a.label} hint={a.hint} value={s.advanced[a.key] ?? ''} placeholder="Printer default"
        onInput={(v) => { const next = { ...s.advanced }; if (v.trim()) next[a.key] = v.trim(); else delete next[a.key]; changed({ advanced: next }); }} />)}
      <Next to="slice" label="Next: slice" />
    </div>
  );
}

function SliceStep() {
  const s = useStore(store);
  const sl = s.slice;
  return (
    <div class="card">
      <h2>Slice and preview</h2>
      <p class="small muted">Slicing runs on this device. Your models aren't uploaded.</p>
      {(sl.kind === 'idle' || sl.kind === 'failed' || sl.kind === 'cancelled') && <>
        {sl.kind === 'failed' && <Banner kind="danger">{sl.message}</Banner>}
        {sl.kind === 'cancelled' && <p class="muted">Slicing was cancelled. Nothing changed.</p>}
        <button class="btn primary" disabled={s.items.length === 0} onClick={() => slice()} data-testid="slice">Slice</button>
      </>}
      {sl.kind === 'running' && <>
        <p aria-live="polite">{sl.stage} · {sl.percent}%</p>
        <Progress value={sl.percent / 100} label="Slicing progress" />
        <div class="btn-row"><button class="btn" onClick={() => engine.cancel()} data-testid="cancel-slice">Cancel</button></div>
      </>}
      {sl.kind === 'done' && <>
        <div class="metrics">
          <div><span class="small muted">Print time</span><span class="metric">{fmtDuration(sl.stats.seconds)}</span></div>
          <div><span class="small muted">Material</span><span class="metric">{sl.stats.grams !== undefined ? `${sl.stats.grams.toFixed(1)} g` : '–'}</span></div>
          <div><span class="small muted">Layers</span><span class="metric">{sl.stats.layers ?? sl.preview.layers.length}</span></div>
        </div>
        {sl.stats.toolChanges > 0 && <p class="small muted">{sl.stats.toolChanges} toolhead changes</p>}
        <p class="small muted">Sliced in {sl.seconds.toFixed(1)} s on this device.</p>
        <div class="btn-row"><button class="btn primary" onClick={() => navigate('/prepare/send')}>Next: export or send</button><button class="btn quiet" onClick={() => slice()}>Slice again</button></div>
      </>}
    </div>
  );
}

/** Slicing needs only a profile. Any of the shared profiles can be chosen, with or without a printer connected. */
function ProfilePicker() {
  const s = useStore(store);
  const [all, setAll] = useState<ProfileInfo[] | null>(null);
  const [query, setQuery] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  useEffect(() => { if (open && !all) profileIndex().then((i) => setAll(i.profiles), (e) => setProblem(`The printer list couldn't be loaded (${(e as Error).message}). The Snapmaker U1 profile is always available.`)); }, [open]);
  const results = all ? searchProfiles(all, query) : [];
  return (
    <div>
      <p><span class="small muted">Slicing for</span> <strong data-testid="profile-name">{s.profile.name}</strong> <span class="small muted">· {s.profile.bed[0]}×{s.profile.bed[1]}×{s.profile.height} mm · {s.profile.tools} {s.profile.tools === 1 ? 'tool' : 'tools'} · {familyLabel(s.profile.family)}</span></p>
      {!open ? <button class="btn quiet" onClick={() => setOpen(true)} data-testid="change-profile">Change printer profile</button> : <>
        <Field id="profile-search" label="Find a printer profile" value={query} onInput={setQuery} placeholder="Prusa MK4, Bambu A1, Voron…" />
        {problem && <Banner kind="warning">{problem}</Banner>}
        {all && <ul class="list" style={{ listStyle: 'none', padding: 0, margin: 0, maxHeight: 280, overflow: 'auto' }} aria-label="Printer profiles">
          {results.map((p) => <li><button class="btn quiet" style={{ width: '100%', justifyContent: 'flex-start' }} aria-pressed={p.id === s.profile.id}
            onClick={async () => { if (await chooseProfile(p.id)) setOpen(false); }}>{p.name} <span class="small muted">· {p.vendor}</span></button></li>)}
          {results.length === 0 && <li class="muted">No profile matches “{query}”.</li>}
        </ul>}
        <div class="btn-row"><button class="btn quiet" onClick={() => setOpen(false)}>Done</button></div>
      </>}
    </div>
  );
}

function SendStep() {
  const s = useStore(store);
  const printer = usePrinter(s.printerId);
  const done = s.slice.kind === 'done' ? s.slice : null;
  const [uploaded, setUploaded] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const fileName = `${(s.name || 'project').replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 60)}.gcode`;
  if (!done) return <div class="card"><h2>Export or send</h2><p class="muted">Slice the plate first.</p><Next to="slice" label="Go to slicing" /></div>;
  const start = { kind: 'start' as const, path: uploaded ?? '', toolheadMap: s.slots.length > 1 ? s.slots.map((_, i) => i) : [] };
  return (
    <div class="card">
      <h2>Export or send</h2>
      <div class="btn-row"><button class="btn" onClick={() => download(done.gcode, fileName, 'text/x.gcode')}>Download sliced file</button></div>
      {!printer ? <p class="muted">{s.printers.length ? 'No printer chosen: download the file and print it however you like.' : <>Add a printer on the <a href="#/printers">Printers</a> page to send jobs straight to it.</>}</p>
      : !printer.capabilities.upload_job ? <p class="muted">{printer.saved.name} can't receive jobs from this browser. Download the file, or send it from Nozzle It All for Desktop.</p>
      : !printer.capabilities.accepted_outputs.includes('gcode') ? <p class="muted">{printer.saved.name} needs a {printer.capabilities.accepted_outputs.join(' or ')} file, which the browser engine doesn't produce yet. Use Nozzle It All for Desktop.</p> : <>
        <h3>Send to {printer.saved.name}</h3>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>{printer.status && <StatusPill state={printer.status.state} />}</div>
        {problem && <Banner kind="warning">{problem}</Banner>}
        {!uploaded ? (
          <button class="btn primary" disabled={busy || !printer.status || printer.status.state === 'offline'} data-testid="send" onClick={async () => {
            setBusy(true); setProblem(null);
            const r = await printer.client.upload(`nozzle/${fileName}`, done.gcode);
            setBusy(false);
            if (r.ok) setUploaded(r.path); else setProblem(r.reason);
          }}>{busy ? 'Sending…' : `Send to ${printer.saved.name}`}</button>
        ) : <>
          <Banner kind="success">Sent. Start printing when the plate is clear.</Banner>
          {printer.capabilities.start_print ? <button class="btn primary" disabled={!printer.status || !allowedStates.start.includes(printer.status.state)} onClick={() => setConfirming(true)}>Start print</button>
            : <p class="small muted">Start the print on the printer.</p>}
          <Confirm open={confirming} title={`Start printing on ${printer.saved.name}?`} body={summary(start)} confirmLabel={glossary.terms['action.start'].confirm}
            detail="Nozzle checks the printer is still ready before sending, and never repeats a command on its own."
            onCancel={() => setConfirming(false)} onConfirm={async () => {
              setConfirming(false);
              const out = await printer.guard.execute(start, printer.status!.state, allowedStates.start);
              notify(out.kind === 'accepted' ? `${glossary.terms['outcome.accepted'].label}: printing started.` : `${out.kind === 'unknown' ? glossary.terms['outcome.unknown'].label : glossary.terms['outcome.rejected'].label}. ${out.reason}`,
                out.kind === 'accepted' ? 'success' : out.kind === 'unknown' ? 'danger' : 'warning');
            }} />
        </>}
      </>}
    </div>
  );
}

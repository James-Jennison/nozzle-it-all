/// <reference lib="webworker" />
// The slicing worker: runs the Nozzle engine (libslic3r compiled to WebAssembly) entirely inside the browser.
// Models and settings arrive by postMessage and stay in this worker's memory; nothing is sent over the network.
import { ENGINE_PROTOCOL, FromWorker, SliceJob, ToWorker } from './protocol';

declare const self: DedicatedWorkerGlobalScope;

interface EngineModule {
  FS: { mkdirTree(p: string): void; writeFile(p: string, d: Uint8Array | string): void; readFile(p: string): Uint8Array; unlink(p: string): void; analyzePath(p: string): { exists: boolean } };
  ccall(name: string, ret: string | null, types: string[], args: unknown[]): unknown;
  UTF8ToString(ptr: number): string;
  _nz_slice_status(): number;
  _nz_cancel(): void;
  _nz_free(p: number): void;
}

let engine: EngineModule | null = null;
let running: number | null = null;
const post = (m: FromWorker, transfer: Transferable[] = []) => self.postMessage(m, transfer);

async function load(): Promise<EngineModule> {
  if (engine) return engine;
  const url = new URL('/engine/nozzle-engine.js', self.location.origin).href;
  const mod = await import(/* @vite-ignore */ url);
  engine = (await mod.default({ locateFile: (f: string) => new URL(`/engine/${f}`, self.location.origin).href })) as EngineModule;
  return engine;
}

const stages: [number, string][] = [[0, 'Preparing the plate'], [10, 'Slicing layers'], [40, 'Generating walls and infill'], [70, 'Generating supports and paths'], [90, 'Writing instructions']];
const stageFor = (p: number) => stages.filter(([at]) => p >= at).pop()![1];

async function slice(id: number, job: SliceJob) {
  const e = await load();
  const dir = `/job-${id}`;
  e.FS.mkdirTree(dir);
  const lines: string[] = [`out\t${dir}/plate.gcode`];
  job.profiles.forEach((p, i) => { const f = `${dir}/profile-${i}-${p.name.replace(/[^a-z0-9_.-]/gi, '_')}`; e.FS.writeFile(f, p.json); lines.push(`profile\t${f}`); });
  for (const [k, v] of Object.entries(job.overrides)) {
    if (/[\t\n]/.test(k + v)) throw new Error('Settings may not contain tabs or line breaks.');
    lines.push(`set\t${k}\t${v}`);
  }
  job.objects.forEach((o, i) => { const f = `${dir}/object-${i}.stl`; e.FS.writeFile(f, o.stl); lines.push(`object\t${f}\t${o.x}\t${o.y}\t${o.rotationZ}\t${o.scale}\t${o.tool}`); });
  const started = performance.now();
  const rc = e.ccall('nz_slice_start', 'number', ['string'], [lines.join('\n')]) as number;
  if (rc !== 0) throw new Error('The engine is already slicing.');
  running = id;
  // Poll the engine's own progress; the slice runs on an engine thread so this loop stays responsive.
  for (;;) {
    await new Promise((r) => setTimeout(r, 200));
    const status = e._nz_slice_status();
    const state = Math.floor(status / 1000);
    const percent = status % 1000;
    if (state === 1) { post({ type: 'progress', id, percent, stage: stageFor(percent) }); continue; }
    const ptr = e.ccall('nz_slice_result', 'number', [], []) as number;
    const message = e.UTF8ToString(ptr); e._nz_free(ptr);
    running = null;
    if (state === 2) {
      const gcode = e.FS.readFile(`${dir}/plate.gcode`);
      const copy = gcode.slice();
      cleanup(e, dir);
      post({ type: 'done', id, gcode: copy, seconds: (performance.now() - started) / 1000 }, [copy.buffer]);
    } else if (state === 4) { cleanup(e, dir); post({ type: 'cancelled', id }); }
    else { cleanup(e, dir); post({ type: 'failed', id, message: message || 'The engine could not slice this plate.' }); }
    return;
  }
}

function cleanup(e: EngineModule, dir: string) {
  for (const f of ['plate.gcode']) { try { e.FS.unlink(`${dir}/${f}`); } catch { /* not written */ } }
}

self.onmessage = async (ev: MessageEvent<ToWorker>) => {
  const m = ev.data;
  try {
    if (m.type === 'hello') {
      if (m.protocol !== ENGINE_PROTOCOL) { post({ type: 'fatal', message: 'The page and the slicing engine are different versions. Reload the page.' }); return; }
      const e = await load();
      const v = e.ccall('nz_version', 'string', [], []) as string;
      post({ type: 'ready', protocol: ENGINE_PROTOCOL, engine: v, threads: self.crossOriginIsolated === true });
    } else if (m.type === 'slice') {
      await slice(m.id, m.job).catch((err: Error) => { running = null; post({ type: 'failed', id: m.id, message: err.message }); });
    } else if (m.type === 'cancel' && running === m.id && engine) {
      engine._nz_cancel();
    }
  } catch (err) {
    post({ type: 'fatal', message: (err as Error).message || 'The slicing engine failed to start.' });
  }
};

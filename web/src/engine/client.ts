// Page-side handle on the slicing worker. One slice at a time; cancel asks the engine to stop at its next checkpoint
// and, if it doesn't within a few seconds, terminates the worker (a fresh one is created for the next slice).
import { ENGINE_PROTOCOL, FromWorker, SliceJob } from './protocol';

export type SliceUpdate = { percent: number; stage: string };
export type SliceOutcome =
  | { kind: 'done'; gcode: Uint8Array; seconds: number }
  | { kind: 'failed'; message: string }
  | { kind: 'cancelled' };

export interface EngineSupport { ok: boolean; reasons: string[]; threads: boolean }

/** What this browser can do, stated plainly for the compatibility panel. */
export function engineSupport(): EngineSupport {
  const reasons: string[] = [];
  if (typeof WebAssembly === 'undefined') reasons.push("This browser can't run WebAssembly.");
  if (typeof Worker === 'undefined') reasons.push("This browser can't run background workers.");
  const threads = typeof SharedArrayBuffer !== 'undefined' && (globalThis as { crossOriginIsolated?: boolean }).crossOriginIsolated === true;
  if (!threads) reasons.push('Multi-threaded slicing needs a cross-origin isolated page; the engine requires it.');
  return { ok: reasons.length === 0, reasons, threads };
}

export class SlicingEngine {
  private worker: Worker | null = null;
  private ready: Promise<string> | null = null;
  private nextId = 1;
  private current: { id: number; resolve: (o: SliceOutcome) => void; onProgress: (u: SliceUpdate) => void } | null = null;
  engineName = '';

  private start(): Promise<string> {
    if (this.ready) return this.ready;
    const w = new Worker(new URL('./engine.worker.ts', import.meta.url), { type: 'module', name: 'nozzle-engine' });
    this.worker = w;
    this.ready = new Promise((resolve, reject) => {
      w.onmessage = (ev: MessageEvent<FromWorker>) => {
        const m = ev.data;
        if (m.type === 'ready') { this.engineName = m.engine; resolve(m.engine); return; }
        if (m.type === 'fatal') { reject(new Error(m.message)); this.finish({ kind: 'failed', message: m.message }); this.reset(); return; }
        if (!this.current || !('id' in m) || m.id !== this.current.id) return;
        if (m.type === 'progress') this.current.onProgress({ percent: m.percent, stage: m.stage });
        else if (m.type === 'done') this.finish({ kind: 'done', gcode: m.gcode, seconds: m.seconds });
        else if (m.type === 'failed') this.finish({ kind: 'failed', message: m.message });
        else if (m.type === 'cancelled') this.finish({ kind: 'cancelled' });
      };
      w.onerror = (e) => { const msg = e.message || 'The slicing engine stopped unexpectedly.'; reject(new Error(msg)); this.finish({ kind: 'failed', message: msg }); this.reset(); };
    });
    w.postMessage({ type: 'hello', protocol: ENGINE_PROTOCOL });
    return this.ready;
  }

  private finish(o: SliceOutcome) { const c = this.current; this.current = null; c?.resolve(o); }
  private reset() { this.worker?.terminate(); this.worker = null; this.ready = null; }

  async warmUp(): Promise<string> { return this.start(); }

  async slice(job: SliceJob, onProgress: (u: SliceUpdate) => void): Promise<SliceOutcome> {
    if (this.current || this.starting) return { kind: 'failed', message: 'A slice is already running.' };
    // Cancel works from the moment Slice is pressed, including while the engine is still loading.
    this.starting = { cancelled: false };
    const starting = this.starting;
    try { await this.start(); } catch (e) { return { kind: 'failed', message: (e as Error).message }; } finally { this.starting = null; }
    if (starting.cancelled) return { kind: 'cancelled' };
    const id = this.nextId++;
    const transfer = job.objects.map((o) => o.stl.buffer as ArrayBuffer);
    return new Promise((resolve) => {
      this.current = { id, resolve, onProgress };
      this.worker!.postMessage({ type: 'slice', id, job }, transfer);
    });
  }
  private starting: { cancelled: boolean } | null = null;

  /** Asks the engine to stop; terminates the worker if it hasn't stopped within 5 s. Never leaves a half result. */
  cancel() {
    if (this.starting) { this.starting.cancelled = true; return; }
    const c = this.current;
    if (!c) return;
    this.worker?.postMessage({ type: 'cancel', id: c.id });
    setTimeout(() => { if (this.current?.id === c.id) { this.reset(); this.finish({ kind: 'cancelled' }); } }, 5000);
  }
}

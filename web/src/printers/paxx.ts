// PAXX / U1 over Moonraker, from the browser. Port of adapter-paxx/.../U1Protocol.kt (same fields, same rules, same
// fixture in its tests). All traffic goes to the printer's own address or the user's local connector; nothing is
// relayed through Nozzle.
import { Action, FULL_SPECTRUM, FullSpectrum, Outcome, PrinterState, PrinterStatus, Route, SavedPrinter, Toolhead, routeFor } from './model';

export const PHYSICAL_TOOLHEADS = 4;
export const STATUS_QUERY: Record<string, string> = {
  webhooks: 'state,state_message', print_stats: 'state,filename,print_duration,info,message', virtual_sdcard: 'progress', toolhead: 'extruder',
  heater_bed: 'temperature,target', extruder: 'temperature,target,nozzle_diameter', extruder1: 'temperature,target,nozzle_diameter',
  extruder2: 'temperature,target,nozzle_diameter', extruder3: 'temperature,target,nozzle_diameter',
  print_task_config: 'filament_exist,filament_vendor,filament_type,filament_sub_type,filament_color_rgba,filament_official',
  // Filament changers on other Klipper printers (lanes below): AFC's lane in the toolhead, Happy Hare's gates.
  AFC: 'current_load', mmu: 'num_gates,gate_status,gate_material,gate_color,gate_temperature,tool',
};

export function mapState(webhooks?: string, print?: string): PrinterState {
  if (webhooks === undefined) return 'unknown';
  if (webhooks === 'startup') return 'starting';
  if (webhooks === 'shutdown' || webhooks === 'error') return 'error';
  if (webhooks !== 'ready') return 'starting';
  return ({ standby: 'ready', printing: 'printing', paused: 'paused', complete: 'finished', cancelled: 'cancelled', error: 'error' } as Record<string, PrinterState>)[print ?? ''] ?? 'unknown';
}

export function normalizeColor(raw?: unknown): string | undefined {
  if (typeof raw !== 'string') return undefined;
  const s = raw.trim().replace(/^#/, '');
  if ((s.length !== 6 && s.length !== 8) || !/^[0-9a-fA-F]+$/.test(s)) return undefined;
  return '#' + s.slice(0, 6).toUpperCase();
}

type J = Record<string, any>;
const str = (a: unknown[] | undefined, i: number) => { const v = a?.[i]; if (v === null || v === undefined) return undefined; const s = String(v).trim(); return s && s.toUpperCase() !== 'NONE' ? s : undefined; };
const num = (o: J | undefined, k: string) => (typeof o?.[k] === 'number' && Number.isFinite(o[k]) ? o[k] as number : undefined);

export function fullSpectrum(heads: Toolhead[], hasTaskConfig: boolean): FullSpectrum {
  if (!hasTaskConfig) return { available: false, palette: [], reason: 'This printer does not report U1 toolhead materials.' };
  const palette = heads.filter((h) => h.loaded).map((h) => h.material?.colorHex).filter((c): c is string => !!c);
  return palette.length >= 2 ? { available: true, palette } : { available: false, palette, reason: 'Load coloured material in at least two toolheads to mix colours.' };
}

const toExtension = (f: FullSpectrum) => ({ available: f.available, palette: f.palette, ...(f.reason ? { unavailableReason: f.reason } : {}) });

export function parseStatus(result: J, route: Route, observedAt = Date.now()): PrinterStatus {
  const status: J | undefined = result?.status;
  if (!status) return { state: 'unknown', route, toolheads: [], extensions: {}, message: "The printer's reply had no status.", observedAt };
  const state = mapState(status.webhooks?.state, status.print_stats?.state);
  const active: string = status.toolhead?.extruder ?? '';
  const cfg: J | undefined = status.print_task_config;
  const heads: Toolhead[] = [];
  for (let i = 0; i < PHYSICAL_TOOLHEADS; i++) {
    const name = i === 0 ? 'extruder' : `extruder${i}`;
    const ex: J | undefined = status[name];
    if (!ex) continue;
    const loaded = cfg?.filament_exist?.[i] === true;
    heads.push({ index: i, nozzle: num(ex, 'temperature'), target: num(ex, 'target'), diameter: num(ex, 'nozzle_diameter'), loaded, active: active === name,
      material: cfg && loaded ? { vendor: str(cfg.filament_vendor, i), type: str(cfg.filament_type, i)?.toUpperCase(), subType: str(cfg.filament_sub_type, i),
        colorHex: normalizeColor(str(cfg.filament_color_rgba, i)), fromTag: cfg.filament_official?.[i] === true } : undefined });
  }
  const stats: J | undefined = status.print_stats;
  const job = (state === 'printing' || state === 'paused') && stats ? {
    fileName: String(stats.filename ?? ''), fraction: Math.min(1, Math.max(0, num(status.virtual_sdcard, 'progress') ?? 0)), elapsed: num(stats, 'print_duration'),
    layer: stats.info?.current_layer > 0 ? stats.info.current_layer : undefined, layers: stats.info?.total_layer > 0 ? stats.info.total_layer : undefined } : undefined;
  const message = (state === 'error' || state === 'starting' ? status.webhooks?.state_message || stats?.message : stats?.message) || undefined;
  return { state, route, job, bed: status.heater_bed ? { current: num(status.heater_bed, 'temperature'), target: num(status.heater_bed, 'target') } : undefined,
    toolheads: heads, message, observedAt, extensions: cfg ? { [FULL_SPECTRUM]: toExtension(fullSpectrum(heads, true)) } : {} };
}

// Filament-changer lanes (AFC units such as the Elegoo CANVAS on COSMOS; Happy Hare MMUs) as material slots. Port of
// adapter-paxx/.../FilamentLanes.kt, itself ported from OrcaSlicer's MoonrakerPrinterAgent: Moonraker's lane_data
// namespace first, then Happy Hare's mmu object. A lane's slot is the tool it is mapped to ("0" is T0).
export interface Lane { tool: number; material: string; colorHex?: string; nozzleTemp?: number; name?: string }
const text = (v: unknown) => (typeof v === 'string' ? v.trim() : '');

export function lanesFromLaneData(result: J | undefined): Lane[] | undefined {
  const value = result?.value;
  if (!value || typeof value !== 'object') return undefined;
  const seen = new Set<number>();
  const lanes = Object.keys(value).sort().flatMap((key): Lane[] => {
    const l = value[key];
    if (!l || typeof l !== 'object' || typeof l.lane !== 'string' || !/^\s*\d+\s*$/.test(l.lane)) return [];
    const tool = parseInt(l.lane, 10);
    if (seen.has(tool)) return [];
    seen.add(tool);
    return [{ tool, material: text(l.material), colorHex: normalizeColor(text(l.color)), nozzleTemp: typeof l.nozzle_temp === 'number' && l.nozzle_temp > 0 ? l.nozzle_temp : undefined, name: key }];
  });
  return lanes.length ? lanes : undefined;
}

export function lanesFromHappyHare(mmu: J | undefined): Lane[] | undefined {
  const gates = typeof mmu?.num_gates === 'number' ? mmu.num_gates : 0;
  if (gates <= 0 || ![mmu!.gate_status, mmu!.gate_material, mmu!.gate_color, mmu!.gate_temperature].every(Array.isArray)) return undefined;
  const lanes: Lane[] = [];
  for (let g = 0; g < Math.min(gates, 64); g++) {
    if (!(typeof mmu!.gate_status[g] === 'number' && mmu!.gate_status[g] > 0)) continue;
    const type = text(mmu!.gate_material[g]);
    if (!type) continue;
    const t = mmu!.gate_temperature[g];
    lanes.push({ tool: g, material: type, colorHex: normalizeColor(text(mmu!.gate_color[g])), nozzleTemp: typeof t === 'number' && t > 0 ? t : undefined });
  }
  return lanes.length ? lanes : undefined;
}

/** [status] with one slot per lane; the single nozzle's reading goes on the lane feeding it (else the first lane). */
export function applyLanes(status: PrinterStatus, lanes: Lane[], currentLane?: string, currentTool?: number): PrinterStatus {
  const nozzle = status.toolheads.find((h) => h.active) ?? status.toolheads[0];
  const sorted = [...lanes].sort((a, b) => a.tool - b.tool);
  const feeding = sorted.find((l) => currentLane !== undefined && l.name === currentLane) ?? sorted.find((l) => l.tool === currentTool);
  const withTemps = feeding ?? sorted[0];
  return { ...status, toolheads: sorted.map((l) => {
    const here = l === withTemps, loaded = l.material !== '';
    return { index: l.tool, nozzle: here ? nozzle?.nozzle : undefined, target: here ? nozzle?.target : undefined, diameter: nozzle?.diameter, loaded,
      material: loaded ? { type: l.material.toUpperCase(), colorHex: l.colorHex, fromTag: false } : undefined, active: l === feeding };
  }) };
}

export interface Camera { name: string; liveUrl?: string; snapshotUrl?: string }
/** Moonraker's webcam list, same rules as U1Protocol.cameras (Kotlin): camera-streamer's MJPEG beside WebRTC, the screen mirror skipped. */
export function parseCameras(webcams: J[], base: string): Camera[] {
  const abs = (u: string) => (u ? new URL(u, base).href : undefined);
  return webcams.filter((w) => w && w.enabled !== false).flatMap((w): Camera[] => {
    const stream = String(w.stream_url ?? ''), snap = String(w.snapshot_url ?? ''), name = String(w.name ?? 'Camera');
    if (name.toLowerCase() === 'gui' || /\/screen(?:\/|$)/i.test(stream)) return [];
    if (/webrtc/i.test(stream)) return [{ name, liveUrl: abs(stream.slice(0, stream.lastIndexOf('/')) + '/stream.mjpg'), snapshotUrl: abs(snap) }];
    if (stream && /mjpeg/i.test(String(w.service ?? ''))) return [{ name, liveUrl: abs(stream), snapshotUrl: abs(snap) }];
    return snap ? [{ name, snapshotUrl: abs(snap) }] : [];
  });
}

/**
 * The first Elegoo stock-firmware command in a G-code file, if any (port of U1Protocol.stockElegooCommand). Klipper has
 * neither; OpenCentauri COSMOS 26.07+ deliberately emergency-stops on both, so such a file never goes to a Moonraker printer.
 */
export function stockElegooCommand(gcode: string): string | undefined {
  return /^[ \t]*(M729|M8213)\b/im.exec(gcode)?.[1]?.toUpperCase();
}

export function validateRemotePath(p: string): string {
  if (!p || p.length > 255 || p.startsWith('/') || p.includes('\\') || p.split('/').some((s) => s === '..' || s === '.' || s === '')) throw new Error('Invalid file name on the printer.');
  return p;
}

export function commandFor(a: Action): { path: string; query?: Record<string, string>; body?: J } {
  switch (a.kind) {
    case 'start': {
      const path = validateRemotePath(a.path);
      if (a.toolheadMap.length === 0) return { path: 'printer/print/start', query: { filename: path } };
      if (a.toolheadMap.some((t) => t !== -1 && (t < 0 || t >= PHYSICAL_TOOLHEADS))) throw new Error('A material is mapped to a toolhead the U1 does not have.');
      const pairs = a.toolheadMap.map((t, i) => [i, t]).filter(([, t]) => t >= 0).map(([i, t]) => `[${i},${t}]`).join(',');
      return { path: 'server/files/start_local_print', body: { path, print_plate: 1, options: { map_table: `[${pairs}]` } } };
    }
    case 'pause': return { path: 'printer/print/pause' };
    case 'resume': return { path: 'printer/print/resume' };
    case 'cancel': return { path: 'printer/print/cancel' };
    case 'home': return { path: 'printer/gcode/script', query: { script: 'G28' } };
    case 'nozzleTemperature':
      if (a.toolhead < 0 || a.toolhead >= PHYSICAL_TOOLHEADS) throw new Error(`The U1 has toolheads 1-${PHYSICAL_TOOLHEADS}.`);
      if (!(a.celsius >= 0 && a.celsius <= 300)) throw new Error('Nozzle temperature must be 0-300 °C.');
      return { path: 'printer/gcode/script', query: { script: `SET_HEATER_TEMPERATURE HEATER=${a.toolhead === 0 ? 'extruder' : `extruder${a.toolhead}`} TARGET=${a.celsius}` } };
    case 'bedTemperature':
      if (!(a.celsius >= 0 && a.celsius <= 110)) throw new Error('Bed temperature must be 0-110 °C.');
      return { path: 'printer/gcode/script', query: { script: `SET_HEATER_TEMPERATURE HEATER=heater_bed TARGET=${a.celsius}` } };
  }
}

/** Why a browser could not reach a printer, in words a person can act on. */
export function explainFetchFailure(p: SavedPrinter, err: unknown): string {
  const pageSecure = typeof location !== 'undefined' && location.protocol === 'https:';
  const target = p.address.startsWith('https') ? 'https' : 'http';
  if (pageSecure && target === 'http' && p.via === 'direct')
    return "Your browser won't let this secure page reach the printer's plain local address directly. Use the Nozzle local connector, or open the printer through your private network's secure address.";
  return `Nozzle couldn't reach ${p.name}. Check it's on and that this device is on the same network or your private network. (${(err as Error)?.message ?? 'no reply'})`;
}

/** A Moonraker client for one printer: GET status with a timeout, POST commands exactly once, no retries. */
export class MoonrakerClient {
  constructor(private p: SavedPrinter, private timeoutMs = 7000) {}
  private base(): string { return this.p.address.replace(/\/+$/, '') + '/'; }
  get route(): Route { try { return routeFor(new URL(this.base()).hostname); } catch { return 'lan'; } }

  private async call(path: string, init: RequestInit, query?: Record<string, string>, timeout = this.timeoutMs): Promise<any> {
    const url = new URL(path, this.base());
    if (url.origin !== new URL(this.base()).origin) throw new Error('Refusing a request to a host other than the printer.');
    for (const [k, v] of Object.entries(query ?? {})) url.searchParams.set(k, v);
    const ctl = new AbortController();
    const t = setTimeout(() => ctl.abort(), timeout);
    try {
      const headers: Record<string, string> = { ...(init.headers as Record<string, string> ?? {}) };
      if (this.p.apiKey) headers['X-Api-Key'] = this.p.apiKey;
      const r = await fetch(url, { ...init, headers, signal: ctl.signal, redirect: 'error', credentials: 'omit', cache: 'no-store' });
      const text = await r.text();
      let json: any = null; try { json = JSON.parse(text); } catch { /* not JSON */ }
      if (r.status === 401 || r.status === 403) throw Object.assign(new Error(this.p.apiKey ? 'The printer rejected the API key.' : 'The printer requires an API key.'), { rejected: true });
      if (json?.error) throw Object.assign(new Error(String(json.error.message ?? 'The printer rejected the request.').slice(0, 300)), { rejected: true });
      if (!r.ok) throw Object.assign(new Error(`The printer refused the request (HTTP ${r.status}).`), { rejected: true });
      return json?.result;
    } finally { clearTimeout(t); }
  }

  async cameras(): Promise<Camera[]> {
    try { return parseCameras((await this.call('server/webcams/list', {}))?.webcams ?? [], this.base()); } catch { return []; }
  }

  async status(): Promise<PrinterStatus> {
    try {
      const info = await this.call('server/info', {});
      if (!info?.klippy_connected || info.klippy_state !== 'ready')
        return { state: info?.klippy_state === 'shutdown' || info?.klippy_state === 'error' ? 'error' : 'starting', route: this.route, toolheads: [],
          extensions: {}, message: `The printer's firmware is ${info?.klippy_state || 'not connected'}.`, observedAt: Date.now() };
      const q = new URLSearchParams(); for (const [k, v] of Object.entries(STATUS_QUERY)) q.set(k, v);
      const result = await this.call('printer/objects/query', {}, Object.fromEntries(q));
      const parsed = parseStatus(result, this.route);
      return result?.status?.print_task_config ? parsed : await this.withLanes(parsed, result?.status);
    } catch (e) {
      return { state: (e as { rejected?: boolean }).rejected ? 'error' : 'offline', route: this.route, toolheads: [], extensions: {},
        message: explainFetchFailure(this.p, e), observedAt: Date.now() };
    }
  }

  // Most printers have no filament changer, so a missing lane_data namespace is only asked about again after a minute.
  private lanesMissingSince = 0;
  private async withLanes(status: PrinterStatus, objects: J | undefined): Promise<PrinterStatus> {
    let fromDb: Lane[] | undefined;
    if (Date.now() - this.lanesMissingSince >= 60_000) {
      try { fromDb = lanesFromLaneData(await this.call('server/database/item', {}, { namespace: 'lane_data' })); } catch { /* no lane data */ }
      if (!fromDb) this.lanesMissingSince = Date.now();
    }
    const lanes = fromDb ?? lanesFromHappyHare(objects?.mmu);
    if (!lanes) return status;
    const current = objects?.AFC?.current_load;
    return applyLanes(status, lanes, typeof current === 'string' && current ? current : undefined, typeof objects?.mmu?.tool === 'number' && objects.mmu.tool >= 0 ? objects.mmu.tool : undefined);
  }

  async upload(name: string, bytes: Uint8Array, onProgress?: (f: number) => void): Promise<{ ok: true; path: string } | { ok: false; interrupted: boolean; reason: string }> {
    const path = validateRemotePath(name);
    const stock = stockElegooCommand(new TextDecoder().decode(bytes));
    if (stock) return { ok: false, interrupted: false, reason: `This file was sliced for Elegoo's stock firmware (it uses ${stock}), which Klipper printers don't have; COSMOS stops the printer on it. Slice again with this printer's own profile.` };
    const form = new FormData();
    form.set('root', 'gcodes');
    const dir = path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : '';
    if (dir) form.set('path', dir);
    form.set('file', new Blob([bytes as BlobPart]), path.split('/').pop()!);
    onProgress?.(0);
    try {
      const r = await fetch(new URL('server/files/upload', this.base()), { method: 'POST', body: form, redirect: 'error', credentials: 'omit',
        headers: this.p.apiKey ? { 'X-Api-Key': this.p.apiKey } : {} });
      const j = await r.json().catch(() => null);
      const item = (j?.result ?? j)?.item;
      if (!r.ok || !item?.path) return { ok: false, interrupted: r.ok, reason: r.ok ? 'The printer did not confirm the upload.' : `The printer refused the upload (HTTP ${r.status}).` };
      onProgress?.(1);
      return { ok: true, path: item.path };
    } catch (e) {
      return { ok: false, interrupted: true, reason: `${(e as Error).message}. The file on the printer may be incomplete; send it again before printing.` };
    }
  }

  async perform(a: Action): Promise<Outcome> {
    let cmd;
    try { cmd = commandFor(a); } catch (e) { return { kind: 'rejected', reason: (e as Error).message }; }
    try {
      const res = await this.call(cmd.path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: cmd.body ? JSON.stringify(cmd.body) : '' }, cmd.query, 70_000);
      return res === 'ok' || (res && typeof res === 'object') ? { kind: 'accepted' } : { kind: 'unknown', reason: "The printer's acknowledgement was not recognised. Check the printer before trying again." };
    } catch (e) {
      if ((e as { rejected?: boolean }).rejected) return { kind: 'rejected', reason: (e as Error).message };
      return { kind: 'unknown', reason: `${(e as Error).message || 'No reply'}. The command may or may not have run; check the printer before trying again.` };
    }
  }
}

/**
 * Confirm-then-execute, once. Port of ActionGuard (printer-api): a stale review sends nothing; an unknown outcome
 * blocks further commands until a fresh reading taken afterwards has been seen.
 */
export class ActionGuard {
  private unresolvedSince: number | null = null;
  constructor(private client: { status(): Promise<PrinterStatus>; perform(a: Action): Promise<Outcome> }) {}
  get needsReconcile() { return this.unresolvedSince !== null; }
  async execute(a: Action, reviewedState: PrinterState, allowed: PrinterState[]): Promise<Outcome> {
    if (this.unresolvedSince !== null) return { kind: 'rejected', reason: "The last command's result is unknown. Check the printer before sending another." };
    const fresh = await this.client.status();
    if (fresh.state !== reviewedState || !allowed.includes(fresh.state))
      return { kind: 'rejected', reason: `The printer changed from ${reviewedState} to ${fresh.state} after you reviewed this. Nothing was sent; review it again.` };
    const out = await this.client.perform(a);
    if (out.kind === 'unknown') this.unresolvedSince = Date.now();
    return out;
  }
  async reconcile(): Promise<PrinterStatus> {
    const since = this.unresolvedSince;
    const s = await this.client.status();
    if (since !== null && s.observedAt >= since && s.state !== 'offline' && s.state !== 'unknown') this.unresolvedSince = null;
    return s;
  }
}

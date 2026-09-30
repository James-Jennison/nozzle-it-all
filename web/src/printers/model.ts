// The shared printer model, in the Web App. Same names and meanings as printer-api (Kotlin); labels come from the
// generated glossary so every platform says the same words.
import { glossary } from '../design/glossary';

export type PrinterState = 'offline' | 'connecting' | 'starting' | 'ready' | 'printing' | 'paused' | 'finished' | 'cancelled' | 'error' | 'unknown';
export type Route = 'lan' | 'private-network' | 'vendor-cloud' | 'none';
/** Printer family id, open-ended like PrinterFamily in printer-api: 'paxx-u1', 'stock-u1', 'bambu-lab', 'prusa', 'klipper', 'octoprint', 'export-only', or a future vendor. */
export type Family = string;
export const KNOWN_FAMILIES = ['paxx-u1', 'stock-u1', 'bambu-lab', 'prusa', 'elegoo', 'klipper', 'octoprint', 'export-only'] as const;

export const stateLabel = (s: PrinterState) => glossary.terms[`state.${s}` as keyof typeof glossary.terms].label;
export const stateDescription = (s: PrinterState) => (glossary.terms[`state.${s}` as keyof typeof glossary.terms] as { description?: string }).description ?? '';
export const routeLabel = (r: Route) => glossary.terms[`route.${r}` as keyof typeof glossary.terms].label;
export const familyLabel = (f: Family) => (glossary.terms as Record<string, { label: string; description?: string }>)[`family.${f}`]?.label ?? f;
export const familyDescription = (f: Family) => (glossary.terms as Record<string, { label: string; description?: string }>)[`family.${f}`]?.description ?? '';
export const isActiveJob = (s: PrinterState) => s === 'printing' || s === 'paused';

export interface Material { vendor?: string; type?: string; subType?: string; colorHex?: string; fromTag: boolean }
export interface Toolhead { index: number; nozzle?: number; target?: number; diameter?: number; loaded: boolean; material?: Material; active: boolean }
export interface Job { fileName: string; fraction: number; elapsed?: number; layer?: number; layers?: number }
export interface FullSpectrum { available: boolean; palette: string[]; reason?: string }
export const FULL_SPECTRUM = 'snapmaker.full-spectrum';
export interface PrinterStatus {
  state: PrinterState; route: Route; job?: Job; bed?: { current?: number; target?: number }; toolheads: Toolhead[];
  message?: string; observedAt: number;
  /** Namespaced vendor extensions (for example 'snapmaker.full-spectrum'), exactly as the shared model defines them. */
  extensions: Record<string, unknown>;
}

export const fullSpectrumOf = (s: PrinterStatus): FullSpectrum | undefined => {
  const f = s.extensions[FULL_SPECTRUM] as { available?: boolean; palette?: string[]; unavailableReason?: string } | undefined;
  return f ? { available: !!f.available, palette: f.palette ?? [], reason: f.unavailableReason } : undefined;
};

/** Capability schema 2 (printer-api Capabilities): the Web App offers only what is listed. */
export interface Capabilities {
  upload_job: boolean; upload_and_start: boolean; start_print: boolean; pause_print: boolean; resume_print: boolean; cancel_print: boolean;
  temperatures: boolean; motion: boolean; camera: boolean; material_state: boolean; material_edit: boolean; load_unload: boolean; multi_material: boolean;
  toolhead_state: boolean; bed_mesh: boolean; files: boolean; job_history: boolean; local_connection: boolean; remote_connection: boolean;
  vendor_cloud: boolean; requires_vendor_account: boolean; firmware_updates: boolean; calibration: boolean; accepted_outputs: string[]; vendor_extensions: string[];
}
export const NO_CAPABILITIES: Capabilities = { upload_job: false, upload_and_start: false, start_print: false, pause_print: false, resume_print: false, cancel_print: false,
  temperatures: false, motion: false, camera: false, material_state: false, material_edit: false, load_unload: false, multi_material: false, toolhead_state: false,
  bed_mesh: false, files: false, job_history: false, local_connection: false, remote_connection: false, vendor_cloud: false, requires_vendor_account: false,
  firmware_updates: false, calibration: false, accepted_outputs: ['gcode'], vendor_extensions: [] };
/** What this browser can do talking straight to a Moonraker printer (PAXX U1 or generic Klipper). Other families go through the connector. */
export const DIRECT_MOONRAKER: Capabilities = { ...NO_CAPABILITIES, upload_job: true, start_print: true, pause_print: true, resume_print: true, cancel_print: true,
  temperatures: true, motion: true, camera: true, toolhead_state: true, files: true, local_connection: true, remote_connection: true };
/** Direct capabilities per Moonraker family, matching the Kotlin adapters (the stock U1 camera isn't reachable). */
export const directCapabilities = (family: Family): Capabilities => (family === 'stock-u1' ? { ...DIRECT_MOONRAKER, camera: false } : DIRECT_MOONRAKER);
export const readCapabilities = (o: Record<string, unknown> | undefined): Capabilities => ({ ...NO_CAPABILITIES, ...(o ?? {}) } as Capabilities);

export interface SavedPrinter {
  id: string; name: string; model: string; family: Family; address: string;
  /** The slicing profile (independent of the connection). */
  profileId?: string;
  /** How the browser reaches it: straight to the printer, or through the local connector. */
  via: 'direct' | 'connector';
  apiKey?: string;
}

const OLD_FIRMWARE: Record<string, string> = { paxx: 'paxx-u1', 'stock-u1': 'stock-u1', klipper: 'klipper' };
/** Saved printers, including those saved before families replaced the firmware field. */
export function migratePrinter(p: SavedPrinter & { firmware?: string }): SavedPrinter {
  if (p.family) return p;
  const { firmware, ...rest } = p;
  return { ...rest, family: OLD_FIRMWARE[firmware ?? 'paxx'] ?? 'paxx-u1' };
}
export function routeFor(host: string): Route {
  const h = host.toLowerCase().replace(/^\[|\]$/g, '');
  const p = h.split('.').map(Number);
  const cgnat = p.length === 4 && p.every((n) => Number.isInteger(n)) && p[0] === 100 && p[1] >= 64 && p[1] <= 127;
  return h.endsWith('.ts.net') || h.startsWith('fd7a:115c:a1e0:') || cgnat ? 'private-network' : 'lan';
}

export type Action =
  | { kind: 'start'; path: string; toolheadMap: number[] }
  | { kind: 'pause' } | { kind: 'resume' } | { kind: 'cancel' }
  | { kind: 'nozzleTemperature'; toolhead: number; celsius: number }
  | { kind: 'bedTemperature'; celsius: number }
  | { kind: 'home' };

export const actionTerm: Record<Action['kind'], string> = {
  start: 'action.start', pause: 'action.pause', resume: 'action.resume', cancel: 'action.cancel',
  nozzleTemperature: 'action.heat', bedTemperature: 'action.heat', home: 'action.home',
};

export const allowedStates: Record<Action['kind'], PrinterState[]> = {
  start: ['ready', 'finished', 'cancelled'], pause: ['printing'], resume: ['paused'], cancel: ['printing', 'paused'],
  nozzleTemperature: ['ready', 'finished', 'cancelled', 'printing', 'paused'], bedTemperature: ['ready', 'finished', 'cancelled', 'printing', 'paused'],
  home: ['ready', 'finished', 'cancelled'],
};

export function summary(a: Action): string {
  switch (a.kind) {
    case 'start': return `Start printing ${a.path}`;
    case 'pause': return 'Pause the current print';
    case 'resume': return 'Resume the paused print';
    case 'cancel': return 'Cancel the current print. This cannot be undone.';
    case 'nozzleTemperature': return a.celsius === 0 ? `Turn off toolhead ${a.toolhead + 1} heater` : `Heat toolhead ${a.toolhead + 1} to ${a.celsius} °C`;
    case 'bedTemperature': return a.celsius === 0 ? 'Turn off the bed heater' : `Heat the bed to ${a.celsius} °C`;
    case 'home': return 'Home all axes. The toolhead and bed will move.';
  }
}

export type Outcome = { kind: 'accepted' } | { kind: 'rejected'; reason: string } | { kind: 'unknown'; reason: string };

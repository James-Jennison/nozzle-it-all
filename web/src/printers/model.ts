// The shared printer model, in the Web App. Same names and meanings as printer-api (Kotlin); labels come from the
// generated glossary so every platform says the same words.
import { glossary } from '../design/glossary';

export type PrinterState = 'offline' | 'connecting' | 'starting' | 'ready' | 'printing' | 'paused' | 'finished' | 'cancelled' | 'error' | 'unknown';
export type Route = 'lan' | 'private-network';
export type Firmware = 'paxx' | 'stock-u1' | 'klipper';

export const stateLabel = (s: PrinterState) => glossary.terms[`state.${s}` as keyof typeof glossary.terms].label;
export const stateDescription = (s: PrinterState) => (glossary.terms[`state.${s}` as keyof typeof glossary.terms] as { description?: string }).description ?? '';
export const routeLabel = (r: Route) => glossary.terms[`route.${r}` as keyof typeof glossary.terms].label;
export const firmwareLabel = (f: Firmware) => glossary.terms[`firmware.${f}` as keyof typeof glossary.terms].label;
export const isActiveJob = (s: PrinterState) => s === 'printing' || s === 'paused';

export interface Material { vendor?: string; type?: string; subType?: string; colorHex?: string; fromTag: boolean }
export interface Toolhead { index: number; nozzle?: number; target?: number; diameter?: number; loaded: boolean; material?: Material; active: boolean }
export interface Job { fileName: string; fraction: number; elapsed?: number; layer?: number; layers?: number }
export interface FullSpectrum { available: boolean; palette: string[]; reason?: string }
export interface PrinterStatus {
  state: PrinterState; route: Route; job?: Job; bed?: { current?: number; target?: number }; toolheads: Toolhead[];
  fullSpectrum: FullSpectrum; message?: string; observedAt: number;
}

export interface SavedPrinter {
  id: string; name: string; model: string; firmware: Firmware; address: string;
  /** How the browser reaches it: straight to the printer, or through the local connector. */
  via: 'direct' | 'connector';
  apiKey?: string;
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

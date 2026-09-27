// Flushing volumes between filaments, worked out from their colours by each printer's own slicer. Port of :domain
// FlushVolumes.kt (shared by the desktop and Android), itself ported from (AGPL-3.0):
//   snapmaker  Snapmaker Orca FlushVolCalc.cpp + RGB2HSV (the U1 and other Snapmaker printers)
//   orca       OrcaSlicer 824b216f FlushVolCalc.cpp + FlushVolPredictor.cpp and its measured flushes (every Orca-library profile)
//   elegoo     ElegooSlicer 2d507e39a9: orca plus its per-printer overrides (FlushVolumeRules.cpp, StandardColorMatcher.cpp)
// Single-precision steps are reproduced with Math.fround. The support rules come from Plater.cpp auto_calc_flushing_volumes,
// the minimums from get_min_flush_volumes. schemas/fixtures/flush-volumes.json (the slicers' own code, compiled) pins every value.
import dataStandard from '../../../domain/src/main/resources/flush/flush_data_standard.txt?raw';
import dataDualStandard from '../../../domain/src/main/resources/flush/flush_data_dual_standard.txt?raw';
import dataDualHighflow from '../../../domain/src/main/resources/flush/flush_data_dual_highflow.txt?raw';
import elegooRulesJson from '../../../domain/src/main/resources/flush/elegoo_flush_volumes.json?raw';

export type FlushMethod = 'snapmaker' | 'orca' | 'elegoo';
export const MIN_FROM_SUPPORT: Record<FlushMethod, number> = { snapmaker: 420, orca: 700, elegoo: 700 };
export const MAX_FLUSH: Record<FlushMethod, number> = { snapmaker: 800, orca: 20000, elegoo: 20000 };
export const TO_SUPPORT = 230;

const f = Math.fround;
const toRadians = (d: number) => f(f(d / 180) * Math.PI);
const luminance = (r: number, g: number, b: number) => f(r * 0.3 + g * 0.59 + b * 0.11);
const thirdEdge = (a: number, b: number, deg: number) => f(Math.sqrt(f(f(f(a * a) + f(b * b)) - f(f(f(2 * a) * b) * f(Math.cos(toRadians(deg)))))));

function hsv(r: number, g: number, b: number): [number, number, number] {
  const cmax = Math.max(r, g, b), cmin = Math.min(r, g, b), delta = f(cmax - cmin);
  let h: number;
  if (Math.abs(delta) < f(0.001)) h = 0;
  else if (cmax === r) h = f(60 * f(f(f(g - b) / delta) % 6));
  else if (cmax === g) h = f(60 * f(f(f(b - r) / delta) + 2));
  else h = f(60 * f(f(f(r - g) / delta) + 4));
  const s = Math.abs(cmax) < f(0.001) ? 0 : f(delta / cmax);
  return [h, s, cmax];
}

function deltaHs(h1: number, s1: number, v1: number, h2: number, s2: number, v2: number): number {
  const a = toRadians(h1), b = toRadians(h2);
  const dx = f(f(f(f(Math.cos(a)) * s1) * v1) - f(f(f(Math.cos(b)) * s2) * v2));
  const dy = f(f(f(f(Math.sin(a)) * s1) * v1) - f(f(f(Math.sin(b)) * s2) * v2));
  return Math.min(f(1.2), f(Math.sqrt(f(f(dx * dx) + f(dy * dy)))));
}

/** "#RRGGBB" or "#RRGGBBAA" to [a, r, g, b]; anything unreadable is opaque white. */
export function argb(hex?: string): [number, number, number, number] {
  const s = (hex ?? '').trim().replace(/^#/, '');
  if ((s.length !== 6 && s.length !== 8) || !/^[0-9a-fA-F]+$/.test(s)) return [255, 255, 255, 255];
  const v = (s.match(/../g) ?? []).map((x) => parseInt(x, 16));
  return [v[3] ?? 255, v[0], v[1], v[2]];
}

/** The colour formula's volume before the minimum is added (at least 60). */
function formula(r1: number, g1: number, b1: number, r2: number, g2: number, b2: number): number {
  const sr = f(r1 / 255), sg = f(g1 / 255), sb = f(b1 / 255), dr = f(r2 / 255), dg = f(g2 / 255), db = f(b2 / 255);
  const fh = hsv(sr, sg, sb), th = hsv(dr, dg, db);
  let hsDist = deltaHs(fh[0], fh[1], fh[2], th[0], th[1], th[2]);
  const fromLumi = luminance(sr, sg, sb), toLumi = luminance(dr, dg, db);
  let lumiFlush: number;
  if (toLumi >= fromLumi) lumiFlush = f(f(Math.pow(f(toLumi - fromLumi), 0.7)) * 560);
  else {
    hsDist = Math.min(f(0.67 * th[2] + 0.33 * fh[2]), hsDist);
    lumiFlush = f(f(fromLumi - toLumi) * 80);
  }
  return Math.max(thirdEdge(f(230 * hsDist), lumiFlush, 120), 60);
}

const hex2 = (n: number) => n.toString(16).toUpperCase().padStart(2, '0');

/** calc_flush_vol: the volume to flush [from] out with [to], by [method]; [dataset] is nozzle_flush_dataset, [printer] the preset name. */
export function flushVolume(from: string | undefined, to: string | undefined, minVolume: number, method: FlushMethod = 'snapmaker', dataset = 0,
                            printer?: string, maxVolume = MAX_FLUSH[method]): number {
  const a = argb(from), b = argb(to);
  if (a[0] === 0) a[1] = a[2] = a[3] = 255; // transparent materials are treated as white
  if (b[0] === 0) b[1] = b[2] = b[3] = 255;
  if (method === 'snapmaker') return Math.min(Math.trunc(f(formula(a[1], a[2], a[3], b[1], b[2], b[3]) + minVolume)), maxVolume);
  if (method === 'elegoo' && printer) {
    const v = elegooRule(printer, `#${hex2(a[1])}${hex2(a[2])}${hex2(a[3])}`, `#${hex2(b[1])}${hex2(b[2])}${hex2(b[3])}`);
    if (v !== undefined) return v;
  }
  const p = predictor(dataset);
  if (dataset !== 0) { const v = p?.predict(a[1], a[2], a[3], b[1], b[2], b[3]); if (v !== undefined) return Math.min(Math.trunc(v), maxVolume); }
  const measured = dataset === 0 ? p?.predict(a[1], a[2], a[3], b[1], b[2], b[3]) : undefined;
  let volume = measured !== undefined ? Math.trunc(measured) : Math.trunc(formula(a[1], a[2], a[3], b[1], b[2], b[3]));
  // As upstream: luminance of the raw 0-255 values against 0-1 thresholds.
  const fromDark = luminance(a[1], a[2], a[3]) > f(180 / 255), toLight = luminance(b[1], b[2], b[3]) < f(75 / 255);
  if (dataset !== 0 && fromDark && toLight) volume = f(volume * 1.3);
  return Math.min(Math.trunc(f(volume + minVolume)), maxVolume);
}

/** The whole matrix (row = from, column = to), with upstream's support-filament rules. */
export function flushMatrix(colours: (string | undefined)[], minimum: number[] = [], support: boolean[] = [], method: FlushMethod = 'snapmaker',
                            dataset = 0, printer?: string): number[] {
  const n = colours.length;
  return Array.from({ length: n * n }, (_, i) => {
    const from = Math.floor(i / n), to = i % n;
    if (from === to) return 0;
    if (support[to]) return TO_SUPPORT;
    const v = flushVolume(colours[from], colours[to], minimum[from] ?? 0, method, dataset, printer);
    return support[from] ? Math.max(v, MIN_FROM_SUPPORT[method]) : v;
  });
}

// --- FlushVolPredictor.cpp: measured flushes, used when both colours are within CIEDE2000 5 of listed ones -------------
class Predictor {
  private colours: number[][] = [];
  private flush = new Map<string, number>();
  valid = false;
  constructor(text: string) {
    const lines = text.split(/\r?\n/);
    const rgb = (h: string) => (h.length === 7 && h[0] === '#' && /^#[0-9a-fA-F]{6}$/.test(h) ? [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16)) : undefined);
    let ok = lines.length >= 2;
    for (const c of (lines[1] ?? '').trim().split(/\s+/).filter(Boolean)) { const v = rgb(c); if (v) this.colours.push(v); else ok = false; }
    for (const line of lines.slice(3)) {
      if (!line.trim()) continue;
      const [x, y, z] = line.trim().split(/\s+/);
      const a = x ? rgb(x) : undefined, b = y ? rgb(y) : undefined, v = z !== undefined ? Math.fround(Number(z)) : NaN;
      if (!a || !b || Number.isNaN(v)) { ok = false; break; }
      const k = `${a}|${b}`; if (!this.flush.has(k)) this.flush.set(k, v);
    }
    this.valid = ok;
  }
  predict(r1: number, g1: number, b1: number, r2: number, g2: number, b2: number): number | undefined {
    if (!this.valid) return undefined;
    const from = this.colours.find((c) => predictorDistance(c, [r1, g1, b1]) <= 5);
    const to = this.colours.find((c) => predictorDistance(c, [r2, g2, b2]) <= 5);
    if (!from || !to) return undefined;
    return this.flush.get(`${from}|${to}`);
  }
}
const predictors = new Map<number, Predictor | undefined>();
const DATA: Record<number, string> = { 0: dataStandard, 1: dataDualStandard, 2: dataDualHighflow };
function predictor(dataset: number): Predictor | undefined {
  if (!predictors.has(dataset)) { const p = DATA[dataset] !== undefined ? new Predictor(DATA[dataset]) : undefined; predictors.set(dataset, p?.valid ? p : undefined); }
  return predictors.get(dataset);
}

function predictorLab(c: number[]): number[] {
  const gamma = (x: number) => (x > 0.04045 ? Math.pow((x + 0.055) / 1.055, 2.4) : x / 12.92);
  const R = gamma(c[0] / 255) * 100, G = gamma(c[1] / 255) * 100, B = gamma(c[2] / 255) * 100;
  const x = 0.412453 * R + 0.357580 * G + 0.180423 * B, y = 0.212671 * R + 0.715160 * G + 0.072169 * B, z = 0.019334 * R + 0.119193 * G + 0.950227 * B;
  const threshold = Math.fround(0.008856); // a float literal in upstream's double threshold
  const fn = (t: number) => (t > threshold ? Math.pow(t, 1 / 3) : 7.787 * t + 0.137931);
  const xn = fn(x / 95.0489), yn = fn(y / 100), zn = fn(z / 108.8840);
  return [116 * yn - 16, 500 * (xn - yn), 200 * (yn - zn)];
}
const deg = (d: number) => d * Math.PI / 180;
function predictorDistance(c1: number[], c2: number[]): number {
  const l1 = predictorLab(c1), l2 = predictorLab(c2);
  const p25 = Math.pow(25, 7);
  const C1 = Math.sqrt(l1[1] * l1[1] + l1[2] * l1[2]), C2 = Math.sqrt(l2[1] * l2[1] + l2[2] * l2[2]);
  const cm7 = Math.pow((C1 + C2) / 2, 7);
  const G = 0.5 * (1 - Math.sqrt(cm7 / (cm7 + p25)));
  const a1 = (1 + G) * l1[1], a2 = (1 + G) * l2[1];
  const pc1 = Math.sqrt(a1 * a1 + l1[2] * l1[2]), pc2 = Math.sqrt(a2 * a2 + l2[2] * l2[2]);
  const hue = (a: number, b: number) => { if (a === 0 && b === 0) return 0; const h = Math.atan2(b, a); return h < 0 ? h + Math.PI * 2 : h; };
  const h1 = hue(a1, l1[2]), h2 = hue(a2, l2[2]);
  const dL = l2[0] - l1[0], dC = pc2 - pc1, cMulti = pc1 * pc2;
  let dH = 0;
  if (cMulti !== 0) { let d = h2 - h1; if (d < -Math.PI) d += 2 * Math.PI; else if (d > Math.PI) d -= 2 * Math.PI; dH = 2 * Math.sqrt(cMulti) * Math.sin(d / 2); }
  const lMean = (l1[0] + l2[0]) / 2, cMean = (pc1 + pc2) / 2, hSum = h1 + h2;
  const hMean = pc1 * pc2 === 0 ? hSum : Math.abs(h1 - h2) <= Math.PI ? hSum / 2 : hSum < 2 * Math.PI ? (hSum + 2 * Math.PI) / 2 : (hSum - 2 * Math.PI) / 2;
  const T = 1 - 0.17 * Math.cos(hMean - deg(30)) + 0.24 * Math.cos(2 * hMean) + 0.32 * Math.cos(3 * hMean + deg(6)) - 0.2 * Math.cos(4 * hMean - deg(63));
  const dTheta = deg(30) * Math.exp(-Math.pow((hMean - deg(275)) / deg(25), 2));
  const cmp7 = Math.pow(cMean, 7), RC = 2 * Math.sqrt(cmp7 / (cmp7 + p25));
  const lm2 = Math.pow(lMean - 50, 2);
  const SL = 1 + (0.015 * lm2) / Math.sqrt(20 + lm2), SC = 1 + 0.045 * cMean, SH = 1 + 0.015 * cMean * T, RT = -Math.sin(2 * dTheta) * RC;
  return Math.fround(Math.sqrt(Math.pow(dL / SL, 2) + Math.pow(dC / SC, 2) + Math.pow(dH / SH, 2) + (RT * (dC / SC) * (dH / SH))));
}

// --- ElegooSlicer's per-printer overrides (FlushVolumeRules.cpp), snapped through StandardColorMatcher.cpp -------------
const PALETTE = ['#FFFFFF', '#FFF242', '#DBF47A', '#09CC3A', '#077747', '#0B6283', '#0BE2A0', '#74D9F3', '#48A7FA', '#2850DF', '#433089', '#A03BF7',
  '#F32FF8', '#D4B1DD', '#F95D77', '#F72221', '#7C4C00', '#F88D36', '#FCEBD7', '#D2C5A3', '#AF7832', '#898989', '#BCBCBC', '#000000'];
function matcherLab(hex: string): number[] | undefined {
  const s = hex.startsWith('#') ? hex.slice(1) : hex;
  if (!/^[0-9a-fA-F]{6}$/.test(s)) return undefined;
  const g = (v: number) => (v > 0.04045 ? Math.pow((v + 0.055) / 1.055, 2.4) : v / 12.92);
  const r = g(parseInt(s.slice(0, 2), 16) / 255), gg = g(parseInt(s.slice(2, 4), 16) / 255), b = g(parseInt(s.slice(4, 6), 16) / 255);
  const x = (r * 0.4124564 + gg * 0.3575761 + b * 0.1804375) * 100, y = (r * 0.2126729 + gg * 0.7151522 + b * 0.0721750) * 100, z = (r * 0.0193339 + gg * 0.1191920 + b * 0.9503041) * 100;
  const pv = (v: number) => (v > 0.008856 ? Math.pow(v, 1 / 3) : 7.787 * v + 16 / 116);
  const fx = pv(x / 95.047), fy = pv(y / 100), fz = pv(z / 108.883);
  return [116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)];
}
function deltaE2000(l1: number[], l2: number[]): number {
  const c1 = Math.sqrt(l1[1] * l1[1] + l1[2] * l1[2]), c2 = Math.sqrt(l2[1] * l2[1] + l2[2] * l2[2]);
  const cb7 = Math.pow((c1 + c2) / 2, 7), g = 0.5 * (1 - Math.sqrt(cb7 / (cb7 + Math.pow(25, 7))));
  const a1p = (1 + g) * l1[1], a2p = (1 + g) * l2[1];
  const c1p = Math.sqrt(a1p * a1p + l1[2] * l1[2]), c2p = Math.sqrt(a2p * a2p + l2[2] * l2[2]);
  const hp = (a: number, b: number) => { const h = Math.atan2(b, a) * 180 / Math.PI; return h < 0 ? h + 360 : h; };
  const h1p = hp(a1p, l1[2]), h2p = hp(a2p, l2[2]);
  const dLp = l2[0] - l1[0], dCp = c2p - c1p, hDiff = h2p - h1p;
  let dhp = 0;
  if (c1p * c2p !== 0) dhp = Math.abs(hDiff) <= 180 ? hDiff : hDiff > 180 ? hDiff - 360 : hDiff + 360;
  const dHp = 2 * Math.sqrt(c1p * c2p) * Math.sin((dhp * Math.PI) / 360);
  const lBar = (l1[0] + l2[0]) / 2, cBar = (c1p + c2p) / 2;
  let hBar = c1p * c2p === 0 ? h1p + h2p : Math.abs(hDiff) <= 180 ? (h1p + h2p) / 2 : (h1p + h2p + 360) / 2;
  if (hBar >= 360) hBar -= 360;
  const T = 1 - 0.17 * Math.cos((hBar - 30) * Math.PI / 180) + 0.24 * Math.cos((2 * hBar) * Math.PI / 180) + 0.32 * Math.cos((3 * hBar + 6) * Math.PI / 180) - 0.20 * Math.cos((4 * hBar - 63) * Math.PI / 180);
  const dTheta = 30 * Math.exp(-Math.pow((hBar - 275) / 25, 2)), cb7p = Math.pow(cBar, 7), rC = 2 * Math.sqrt(cb7p / (cb7p + Math.pow(25, 7)));
  const sL = 1 + (0.015 * Math.pow(lBar - 50, 2)) / Math.sqrt(20 + Math.pow(lBar - 50, 2)), sC = 1 + 0.045 * cBar, sH = 1 + 0.015 * cBar * T;
  const rT = -Math.sin((2 * dTheta) * Math.PI / 180) * rC;
  return Math.sqrt(Math.pow(dLp / sL, 2) + Math.pow(dCp / sC, 2) + Math.pow(dHp / sH, 2) + rT * (dCp / sC) * (dHp / sH));
}
let paletteLab: number[][] | undefined;
function snap(hex: string): string | undefined {
  const lab = matcherLab(hex); if (!lab) return undefined;
  paletteLab ??= PALETTE.map((p) => matcherLab(p)!);
  let best = Number.MAX_VALUE, index = 0;
  paletteLab.forEach((p, i) => { const d = deltaE2000(lab, p); if (d < best) { best = d; index = i; } });
  return PALETTE[index];
}
interface Rule { printers: string[]; priority: number; entries: [string, string, number][] }
let rules: Rule[] | undefined;
function elegooRule(printer: string, from: string, to: string): number | undefined {
  rules ??= ((() => { try { return JSON.parse(elegooRulesJson).rules ?? []; } catch { return []; } })() as J[]).map((r) => ({
    printers: Array.isArray(r.printer_name) ? (r.printer_name as unknown[]).filter((x): x is string => typeof x === 'string') : [],
    priority: typeof r.priority === 'number' ? r.priority : 0,
    entries: (Array.isArray(r.flush_volume_entries) ? r.flush_volume_entries as unknown[][] : [])
      .filter((e) => Array.isArray(e) && e.length >= 3 && typeof e[0] === 'string' && typeof e[1] === 'string' && typeof e[2] === 'number')
      .flatMap((e): [string, string, number][] => { const a = snap(e[0] as string), b = snap(e[1] as string); return a && b ? [[a, b, Math.trunc(e[2] as number)]] : []; }),
  }));
  const a = snap(from), b = snap(to); if (!a || !b) return undefined;
  let best: Rule | undefined, volume = 0;
  for (const r of rules) {
    if (!r.printers.includes(printer)) continue;
    const hit = r.entries.find((e) => e[0] === a && e[1] === b); if (!hit) continue;
    if (!best || r.priority >= best.priority) { best = r; volume = hit[2]; }
  }
  return best ? volume : undefined;
}

type J = Record<string, unknown>;
const first = (o: J | undefined, k: string): string | undefined => {
  const v = o?.[k]; const s = (Array.isArray(v) ? v[0] : v);
  if (s === undefined || s === null) return undefined;
  const t = String(s).trim(); return t && t !== 'nil' ? t : undefined;
};

/** get_min_flush_volumes: per filament, the nozzle's volume less a long retraction's worth when cutting is enabled. */
export function minimumFlushVolumes(machine: J | undefined, filaments: (J | undefined)[]): number[] {
  const nozzleVolume = Math.trunc(Number(first(machine, 'nozzle_volume') ?? 0)) || 0;
  const level = Number(first(machine, 'enable_long_retraction_when_cut') ?? 0) || 0;
  const on = (v?: string) => (v === undefined ? undefined : v === '1' || v === 'true');
  const machineOn = on(first(machine, 'long_retractions_when_cut')) ?? false;
  const machineDistance = Number(first(machine, 'retraction_distances_when_cut') ?? 18);
  const machineRetract = level !== 0 && machineOn ? Math.trunc(machineDistance) : 0;
  return filaments.map((fil) => {
    const filamentOn = on(first(fil, 'filament_long_retractions_when_cut'));
    const d = first(fil, 'filament_retraction_distances_when_cut');
    const retract = filamentOn === false ? 0 : filamentOn === true && level === 2 ? Math.trunc(d !== undefined ? Number(d) : machineDistance) : machineRetract;
    return Math.trunc(nozzleVolume - Math.PI * 1.75 * 1.75 / 4 * retract);
  });
}

export const isSupportFilament = (fil: J | undefined) => first(fil, 'filament_is_support') === '1';

/** Whose calculation a printer uses, from its machine profile: its own slicer's. */
export function methodFor(machine: J | undefined): FlushMethod {
  const model = ['printer_model', 'name', 'printer_settings_id'].map((k) => machine?.[k]).find((v): v is string => typeof v === 'string' && v.trim() !== '') ?? '';
  return /^snapmaker/i.test(model) ? 'snapmaker' : /^elegoo/i.test(model) ? 'elegoo' : 'orca';
}

export interface FlushSetup { minimum: number[]; support: boolean[]; method: FlushMethod; dataset: number; printer?: string }

/** Everything the calculation needs besides the colours (FlushVolumes.setup). */
export function flushSetup(machine: J | undefined, filaments: (J | undefined)[]): FlushSetup {
  const name = machine?.name;
  return { minimum: minimumFlushVolumes(machine, filaments), support: filaments.map(isSupportFilament), method: methodFor(machine),
    dataset: Number(first(machine, 'nozzle_flush_dataset') ?? 0) || 0, printer: typeof name === 'string' && name ? name : undefined };
}

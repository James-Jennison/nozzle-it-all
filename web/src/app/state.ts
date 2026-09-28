// Application state for Nozzle It All Web. Project semantics match Desktop's PrepareState: the same placement maths,
// the same manifest fields, the same guided presets and multi-material recipe, so a project moves between platforms.
import { Store } from './store';
import { IDENTITY, Mesh, ModelObject, Project3mf, readProject, Transform, writeProject } from '../project/threemf';
import { MaterialSlot, ProjectManifest, newManifest } from '../project/manifest';
import { meshBounds, readModelFile, writeStl } from '../project/mesh';
import { GUIDED_PRESETS, gcodeStats, GcodeStats, multiToolOverrides } from '../project/slicing';
import { flushSetup } from '../project/flush';
import { SlicingEngine } from '../engine/client';
import { ProjectStore, StoredProjectInfo, download } from '../storage/projects';
import { parseGcode, Preview } from './gcode';
import { SavedPrinter, migratePrinter } from '../printers/model';
import { DEFAULT_INFO, DEFAULT_PROFILE, ProfileFiles, ProfileInfo, bedOf, loadProfile, profileIndex } from '../project/profiles';

export const APP_VERSION = '0.1.0';

export interface PlateItem { id: number; name: string; mesh: Mesh; x: number; y: number; rotZ: number; scale: number; slot: number }

export type SliceState =
  | { kind: 'idle' }
  | { kind: 'running'; percent: number; stage: string }
  | { kind: 'done'; gcode: Uint8Array; stats: GcodeStats; preview: Preview; seconds: number }
  | { kind: 'failed'; message: string }
  | { kind: 'cancelled' };

export interface AppModel {
  route: string;
  projectId: string | null;
  manifest: ProjectManifest | null;
  name: string;
  items: PlateItem[];
  selected: number | null;
  dirty: boolean;
  passthrough: Record<string, Uint8Array>;
  metadata: Record<string, string>;
  preset: string;
  supports: boolean;
  infill: number;
  advanced: Record<string, string>;
  slots: MaterialSlot[];
  printerId: string | null;
  /** The slicing profile. Independent of any printer connection: every profile can be sliced for. */
  profile: ProfileInfo;
  slice: SliceState;
  previewLayer: number;
  notice: { text: string; kind: 'info' | 'warning' | 'danger' | 'success' } | null;
  projects: StoredProjectInfo[];
  printers: SavedPrinter[];
}

/** The current profile's bed (millimetres). Updated in place when the profile changes; the plate view re-renders on it. */
export const bed = { w: 270, d: 270, h: 270 };
let profileFiles: ProfileFiles | null = null;

const SLOT_COLOURS = ['#A78BFA', '#F2754E', '#EEF2F4', '#1E2429', '#3FB68B', '#E8B931', '#4A90D9', '#D9468F'];
/** One material slot per tool of the profile (at least one). */
export const defaultSlots = (tools = DEFAULT_INFO.tools): MaterialSlot[] =>
  Array.from({ length: Math.max(1, Math.min(tools, 16)) }, (_, i) => ({ slot: i + 1, type: 'PLA', colorHex: SLOT_COLOURS[i % SLOT_COLOURS.length] }));

function loadPrinters(): SavedPrinter[] { try { return (JSON.parse(localStorage.getItem('nozzle.printers') ?? '[]') as SavedPrinter[]).map(migratePrinter); } catch { return []; } }
export function savePrinters(p: SavedPrinter[]) { try { localStorage.setItem('nozzle.printers', JSON.stringify(p)); } catch { /* storage unavailable */ } }

export const store = new Store<AppModel>({
  route: location.hash.slice(1) || '/', projectId: null, manifest: null, name: 'Untitled project', items: [], selected: null, dirty: false, passthrough: {}, metadata: {},
  preset: 'standard', supports: false, infill: 15, advanced: {}, slots: defaultSlots(), printerId: null, profile: DEFAULT_INFO, slice: { kind: 'idle' }, previewLayer: 0, notice: null, projects: [],
  printers: loadPrinters(),
});
export const projects = new ProjectStore();
export const engine = new SlicingEngine();

export function navigate(route: string) { if (location.hash.slice(1) !== route) location.hash = route; store.set({ route }); }
window.addEventListener('hashchange', () => store.set({ route: location.hash.slice(1) || '/' }));

export const notify = (text: string, kind: 'info' | 'warning' | 'danger' | 'success' = 'info') => store.set({ notice: { text, kind } });

// --- placement (identical to Desktop PrepItem.placement) ---
export function itemBounds(it: PlateItem) { const b = meshBounds(it.mesh); return { w: (b[3] - b[0]) * it.scale, d: (b[4] - b[1]) * it.scale, h: (b[5] - b[2]) * it.scale, b }; }
export function placement(it: PlateItem): Transform {
  const b = meshBounds(it.mesh);
  const mx = (b[0] + b[3]) / 2, my = (b[1] + b[4]) / 2, mz = b[2];
  const r = (it.rotZ * Math.PI) / 180, c = Math.cos(r) * it.scale, s = Math.sin(r) * it.scale;
  return [c, s, 0, -s, c, 0, 0, 0, it.scale, -(mx * c - my * s) + it.x, -(mx * s + my * c) + it.y, -mz * it.scale];
}
function fromPlacement(o: ModelObject, slot: number): PlateItem {
  const m = o.placement;
  const scale = Math.hypot(m[0], m[1]) || 1;
  const rot = (Math.atan2(m[1], m[0]) * 180) / Math.PI;
  const b = meshBounds(o.mesh);
  const cx = (b[0] + b[3]) / 2, cy = (b[1] + b[4]) / 2;
  return { id: o.id, name: o.name, mesh: o.mesh, x: cx * m[0] + cy * m[3] + m[9], y: cx * m[1] + cy * m[4] + m[10], rotZ: rot, scale, slot };
}

export function outOfBounds(items = store.get().items) {
  return items.filter((it) => { const { w, d, h } = itemBounds(it); return it.x - w / 2 < 0 || it.y - d / 2 < 0 || it.x + w / 2 > bed.w || it.y + d / 2 > bed.d || h > bed.h; });
}

export function arrange() {
  const items = store.get().items.map((i) => ({ ...i }));
  const gap = 6; let x = gap, y = gap, row = 0, fits = true;
  for (const it of [...items].sort((a, b) => itemBounds(b).d - itemBounds(a).d)) {
    const { w, d } = itemBounds(it);
    if (x + w > bed.w - gap) { x = gap; y += row + gap; row = 0; }
    it.x = x + w / 2; it.y = y + d / 2; x += w + gap; row = Math.max(row, d);
    if (y + d > bed.d - gap || w > bed.w - 2 * gap) fits = false;
  }
  const usedW = Math.max(0, ...items.map((i) => i.x + itemBounds(i).w / 2)), usedD = Math.max(0, ...items.map((i) => i.y + itemBounds(i).d / 2));
  const dx = (bed.w - usedW - gap) / 2, dy = (bed.d - usedD - gap) / 2;
  if (dx > 0 && dy > 0) items.forEach((i) => { i.x += dx; i.y += dy; });
  changed({ items });
  if (!fits) notify('Not everything fits on one plate. Remove or scale down an object before slicing.', 'warning');
}

export function changed(patch: Partial<AppModel> = {}) {
  const s = store.get();
  store.set({ ...patch, dirty: true, slice: s.slice.kind === 'running' ? s.slice : { kind: 'idle' } });
}

export async function importFiles(files: FileList | File[]) {
  const s = store.get();
  let nextId = Math.max(0, ...s.items.map((i) => i.id)) + 1;
  const items = [...s.items];
  for (const f of Array.from(files)) {
    try {
      const bytes = new Uint8Array(await f.arrayBuffer());
      for (const m of readModelFile(f.name, bytes)) items.push({ id: nextId++, name: m.name, mesh: m.mesh, x: bed.w / 2, y: bed.d / 2, rotZ: 0, scale: 1, slot: 1 });
      if (s.name === 'Untitled project' && items.length) store.set({ name: f.name.replace(/\.[^.]+$/, '') });
    } catch (e) { notify(`Couldn't open ${f.name}: ${(e as Error).message}`, 'danger'); }
  }
  store.set({ items });
  arrange();
}

export function newProject() {
  store.set({ projectId: null, manifest: null, name: 'Untitled project', items: [], selected: null, dirty: false, passthrough: {}, metadata: {}, preset: 'standard',
    supports: false, infill: 15, advanced: {}, slots: defaultSlots(), slice: { kind: 'idle' }, notice: null });
}

export function toProject(): Project3mf {
  const s = store.get();
  const base = s.manifest ?? newManifest(s.name, APP_VERSION);
  const printer = s.printers.find((p) => p.id === s.printerId);
  const presetOverrides = GUIDED_PRESETS.find((p) => p.id === s.preset)?.overrides ?? {};
  const manifest: ProjectManifest = {
    ...base, name: s.name, revision: base.revision + (s.dirty || !s.manifest ? 1 : 0), modifiedAtMillis: Date.now(),
    modifiedBy: { app: 'Nozzle It All', platform: 'web', version: APP_VERSION },
    printer: { ...(base.printer ?? {}), model: s.profile.model, profileId: s.profile.id, family: printer?.family ?? s.profile.family, printerId: printer?.id ?? base.printer?.printerId },
    // A project's plates are kept as they came (the desktop's multi-plate projects): each object stays on its plate, new ones
    // join the first. The Web App has no plate list yet: every object is on its one bed, other plates' at their Orca-layout places.
    plates: (base.plates.length ? base.plates : [{ index: 1, name: 'Plate 1', objects: [] }]).map((pl, n) => ({ ...pl, index: n + 1, name: pl.name ?? `Plate ${n + 1}`,
      objects: s.items.filter((i) => { const home = base.plates.findIndex((b) => b.objects.some((o) => o.objectId === i.id)); return (home < 0 ? 0 : home) === n; })
        .map((i) => ({ ...(pl.objects.find((o) => o.objectId === i.id) ?? {}), objectId: i.id, name: i.name, materialSlot: i.slot })) })),
    materials: s.slots,
    settings: { ...base.settings, preset: s.preset, overrides: { ...presetOverrides, sparse_infill_density: `${s.infill}%`, enable_support: s.supports ? '1' : '0', ...s.advanced } },
  };
  return { objects: s.items.map((i) => ({ id: i.id, name: i.name, mesh: i.mesh, placement: placement(i) })), metadata: { ...s.metadata, Title: s.name, Application: `Nozzle It All Web ${APP_VERSION}` }, manifest, passthrough: s.passthrough };
}

export async function saveProject(): Promise<void> {
  const p = toProject();
  const bytes = await writeProject(p);
  const s = store.get();
  const info: StoredProjectInfo = { id: p.manifest!.projectId, name: s.name, modified: Date.now(), objects: s.items.length, printer: p.manifest!.printer?.model, size: bytes.length };
  await projects.write(info, bytes);
  store.set({ projectId: info.id, manifest: p.manifest, dirty: false, projects: await projects.list() });
}

export function openBytes(bytes: Uint8Array, fallbackName: string) {
  const p = readProject(bytes);
  const slots = new Map((p.manifest?.plates.flatMap((pl) => pl.objects) ?? []).map((o) => [o.objectId, o.materialSlot ?? 1]));
  const ov = p.manifest?.settings.overrides ?? {};
  const known = new Set(['layer_height', 'sparse_infill_density', 'enable_support']);
  store.set({
    projectId: p.manifest?.projectId ?? null, manifest: p.manifest, name: p.manifest?.name ?? p.metadata.Title ?? fallbackName,
    items: p.objects.map((o) => fromPlacement(o, slots.get(o.id) ?? 1)), selected: null, dirty: false, passthrough: p.passthrough, metadata: p.metadata,
    preset: p.manifest?.settings.preset ?? 'standard', supports: ov.enable_support === '1', infill: parseInt(ov.sparse_infill_density ?? '15', 10) || 15,
    advanced: Object.fromEntries(Object.entries(ov).filter(([k]) => !known.has(k))), slots: p.manifest?.materials.length ? p.manifest.materials : defaultSlots(),
    printerId: p.manifest?.printer?.printerId && store.get().printers.some((x) => x.id === p.manifest!.printer!.printerId) ? p.manifest.printer.printerId! : store.get().printerId,
    slice: { kind: 'idle' }, notice: p.manifestProblem ? { text: p.manifestProblem, kind: 'warning' } : null,
  });
  const wanted = p.manifest?.printer?.profileId;
  if (wanted && wanted !== store.get().profile.id) void chooseProfile(wanted, { keepSlots: true });
}

/**
 * Switches the slicing profile. Loads its files (fetched on demand), resizes the bed and, unless keepSlots, gives one
 * material slot per tool. A profile that can't be loaded leaves the current one in place and says why.
 */
export async function chooseProfile(id: string, opts: { keepSlots?: boolean } = {}): Promise<boolean> {
  try {
    const info = id === DEFAULT_PROFILE ? DEFAULT_INFO : (await profileIndex()).profiles.find((p) => p.id === id);
    if (!info) throw new Error(`This browser doesn't have the printer profile "${id}".`);
    const files = await loadProfile(id);
    profileFiles = files;
    Object.assign(bed, bedOf(files.machine));
    const s = store.get();
    store.set({ profile: info, slots: opts.keepSlots ? s.slots : defaultSlots(info.tools), slice: { kind: 'idle' }, dirty: s.dirty || !opts.keepSlots });
    return true;
  } catch (e) {
    notify(`${(e as Error).message} Keeping ${store.get().profile.model}.`, 'warning');
    return false;
  }
}

export async function openStored(id: string) { const s = store.get(); const info = s.projects.find((p) => p.id === id); openBytes(await projects.read(id), info?.name ?? 'Project'); }

export async function exportProject() { const p = toProject(); download(await writeProject(p), `${store.get().name || 'project'}.3mf`, 'model/3mf'); }

/** Slices in this browser. The models go to the engine worker on this device, never to a server. */
export async function slice() {
  const s = store.get();
  if (s.items.length === 0) { store.set({ slice: { kind: 'failed', message: 'Add a model to the plate first.' } }); return; }
  const off = outOfBounds();
  if (off.length) { store.set({ slice: { kind: 'failed', message: `${off.map((o) => o.name).join(', ')} is off the plate. Move it or use Arrange.` } }); return; }
  const usedSlots = Math.max(...s.items.map((i) => i.slot));
  const slots = s.slots.slice(0, Math.max(usedSlots, 1));
  const presetOverrides = GUIDED_PRESETS.find((p) => p.id === s.preset)?.overrides ?? {};
  store.set({ slice: { kind: 'running', percent: 0, stage: 'Starting the engine' } });
  const files = profileFiles ?? await loadProfile(s.profile.id);
  // Flushing volumes are worked out the printer's own slicer's way, with its nozzle volume and support filaments (flush.ts).
  const parse = (t: string) => { try { return JSON.parse(t) as Record<string, unknown>; } catch { return undefined; } };
  const filament = parse(files.filament);
  const flush = flushSetup(parse(files.machine), slots.map(() => filament));
  const overrides = { ...presetOverrides, sparse_infill_density: `${s.infill}%`, enable_support: s.supports ? '1' : '0',
    ...(slots.length > 1 ? multiToolOverrides(1.75, slots, 210, flush) : {}), ...s.advanced };
  const out = await engine.slice({
    profiles: [{ name: 'machine.json', json: files.machine }, { name: 'process.json', json: files.process }, { name: 'filament.json', json: files.filament }],
    overrides,
    // The engine places each object relative to the bed centre (the same convention as Android's native bridge).
    objects: s.items.map((i) => ({ stl: writeStl(i.mesh), x: i.x - bed.w / 2, y: i.y - bed.d / 2, rotationZ: i.rotZ, scale: i.scale, tool: slots.length > 1 ? i.slot : 0 })),
  }, (u) => store.set({ slice: { kind: 'running', percent: u.percent, stage: u.stage } }));
  if (out.kind === 'done') {
    const text = new TextDecoder().decode(out.gcode);
    const preview = parseGcode(text);
    store.set({ slice: { kind: 'done', gcode: out.gcode, stats: gcodeStats(text), preview, seconds: out.seconds }, previewLayer: Math.max(0, preview.layers.length - 1) });
  } else if (out.kind === 'failed') store.set({ slice: { kind: 'failed', message: out.message } });
  else store.set({ slice: { kind: 'cancelled' } });
}

export { IDENTITY };

// The Nozzle project manifest, schema "nozzle.project" 1.x. Mirrors project-format/.../ProjectManifest.kt: the same
// field names, the same rule that unknown fields are kept and written back, and the same refusal of a newer major
// version. Shared fixtures in schemas/fixtures prove the two implementations agree.

export const SCHEMA = 'nozzle.project';
export const MAJOR = 1;
export const MINOR = 0;
export const ARCHIVE_PATH = 'Auxiliaries/Nozzle/project.json';
export const META_PROJECT_ID = 'nozzle:ProjectId';
export const META_REVISION = 'nozzle:Revision';
export const META_MANIFEST_SHA256 = 'nozzle:ManifestSha256';

type Json = Record<string, unknown>;

export interface Producer { app: string; platform: string; version: string }
export interface PrinterTarget { model: string; firmware?: string; printerId?: string; nozzleDiameters?: number[]; [k: string]: unknown }
export interface ObjectEntry { objectId: number; name: string; materialSlot?: number; [k: string]: unknown }
export interface PlateEntry { index: number; name: string; objects: ObjectEntry[]; [k: string]: unknown }
export interface MaterialSlot { slot: number; type?: string; vendor?: string; subType?: string; colorHex?: string; toolhead?: number; [k: string]: unknown }
export interface SettingsChoice { preset?: string; overrides: Record<string, string>; [k: string]: unknown }
export interface Attribution { title: string; creator?: string; source?: string; license?: string; [k: string]: unknown }

/**
 * The manifest as a plain object. Known fields are typed; any other field (from a newer app) stays on the object and is
 * written back unchanged, which is how unknown data survives a round trip through this version.
 */
export interface ProjectManifest {
  schema: typeof SCHEMA;
  version: [number, number];
  projectId: string;
  revision: number;
  name: string;
  createdBy: Producer;
  modifiedBy: Producer;
  modifiedAtMillis: number;
  printer?: PrinterTarget;
  plates: PlateEntry[];
  materials: MaterialSlot[];
  settings: SettingsChoice;
  attribution: Attribution[];
  extensions: Json;
  [k: string]: unknown;
}

export class ProjectFormatError extends Error {}
export class IncompatibleProjectError extends ProjectFormatError {
  constructor(public major: number, message: string) { super(message); }
}

export function parseManifest(text: string): ProjectManifest {
  let o: Json;
  try { o = JSON.parse(text); } catch { throw new ProjectFormatError('The project manifest is not valid JSON.'); }
  if (typeof o !== 'object' || o === null || o.schema !== SCHEMA) throw new ProjectFormatError('This is not a Nozzle It All project manifest.');
  const version = o.version as unknown;
  if (!Array.isArray(version)) throw new ProjectFormatError('The project manifest has no version.');
  if (version[0] !== MAJOR) throw new IncompatibleProjectError(Number(version[0]), `This project was saved by a newer Nozzle It All (format ${version[0]}). Update Nozzle It All to open it.`);
  if (typeof o.projectId !== 'string' || !o.projectId) throw new ProjectFormatError('The project manifest has no project id.');
  const m = o as unknown as ProjectManifest;
  m.plates = Array.isArray(m.plates) ? m.plates : [];
  m.materials = Array.isArray(m.materials) ? m.materials : [];
  m.settings = (m.settings && typeof m.settings === 'object') ? { ...m.settings, overrides: { ...(m.settings.overrides ?? {}) } } : { overrides: {} };
  m.attribution = Array.isArray(m.attribution) ? m.attribution : [];
  m.extensions = (m.extensions && typeof m.extensions === 'object') ? m.extensions : {};
  m.revision = Number(m.revision ?? 0);
  return m;
}

export function newManifest(name: string, version: string, id: string = crypto.randomUUID()): ProjectManifest {
  const me: Producer = { app: 'Nozzle It All', platform: 'web', version };
  return { schema: SCHEMA, version: [MAJOR, MINOR], projectId: id, revision: 1, name, createdBy: me, modifiedBy: { ...me }, modifiedAtMillis: Date.now(),
    plates: [], materials: [], settings: { overrides: {} }, attribution: [], extensions: {} };
}

/** Keeps a newer minor version number rather than downgrading it. */
export function serializeManifest(m: ProjectManifest): string {
  const out = { ...m, schema: SCHEMA, version: [MAJOR, Math.max(MINOR, m.version?.[1] ?? 0)] };
  return JSON.stringify(out, null, 2);
}

/** Canonical text: sorted keys, no whitespace. Matches ProjectManifest.canonicalText() in Kotlin for hashing. */
export function canonical(v: unknown): string {
  if (v === null || v === undefined) return 'null';
  if (Array.isArray(v)) return '[' + v.map(canonical).join(',') + ']';
  if (typeof v === 'object') return '{' + Object.keys(v as Json).sort().map((k) => JSON.stringify(k) + ':' + canonical((v as Json)[k])).join(',') + '}';
  if (typeof v === 'number') return Number.isInteger(v) ? String(v) : String(v);
  return JSON.stringify(v);
}

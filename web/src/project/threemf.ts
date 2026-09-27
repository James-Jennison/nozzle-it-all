// Canonical 3MF read/write for the Web App. Mirrors project-format/.../ThreeMf.kt: same safety limits, same flattening of
// production-extension components, same passthrough of every entry Nozzle doesn't own, same manifest placement.
import { unzipSync, zipSync, strFromU8, strToU8 } from 'fflate';
import { child, children, escapeXml, parseXml, XmlElement, XmlError } from './xml';
import { ARCHIVE_PATH, IncompatibleProjectError, META_MANIFEST_SHA256, META_PROJECT_ID, META_REVISION, ProjectFormatError, ProjectManifest, canonical, parseManifest, serializeManifest } from './manifest';

export interface Mesh { vertices: Float32Array; triangles: Uint32Array }
/** 12-number 3MF transform (row-vector convention: p' = p * M). */
export type Transform = number[];
export const IDENTITY: Transform = [1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0];
export interface ModelObject { id: number; name: string; mesh: Mesh; placement: Transform }
export interface Project3mf {
  objects: ModelObject[];
  metadata: Record<string, string>;
  manifest: ProjectManifest | null;
  passthrough: Record<string, Uint8Array>;
  manifestProblem?: string;
}
export interface ReadLimits { maxEntries: number; maxEntryBytes: number; maxTotalBytes: number; maxTriangles: number }
export const DEFAULT_LIMITS: ReadLimits = { maxEntries: 10_000, maxEntryBytes: 512 * 1024 * 1024, maxTotalBytes: 1536 * 1024 * 1024, maxTriangles: 10_000_000 };

const MODEL_PATH = '3D/3dmodel.model';
const OWNED = new Set(['[Content_Types].xml', '_rels/.rels', MODEL_PATH, ARCHIVE_PATH]);

export function validEntryName(name: string): boolean {
  return name.length > 0 && name.length <= 1024 && !name.startsWith('/') && !name.includes('\\') && !name.includes('\u0000') &&
    !name.split('/').some((p) => p === '..' || p === '.') && !/^[A-Za-z]:/.test(name);
}

export function applyTransform(t: Transform, x: number, y: number, z: number): [number, number, number] {
  return [x * t[0] + y * t[3] + z * t[6] + t[9], x * t[1] + y * t[4] + z * t[7] + t[10], x * t[2] + y * t[5] + z * t[8] + t[11]];
}
/** a followed by b. */
export function compose(a: Transform, b: Transform): Transform {
  const o = new Array(12).fill(0);
  for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) for (let k = 0; k < 3; k++) o[i * 3 + j] += a[i * 3 + k] * b[k * 3 + j];
  for (let j = 0; j < 3; j++) { o[9 + j] = b[9 + j]; for (let k = 0; k < 3; k++) o[9 + j] += a[9 + k] * b[k * 3 + j]; }
  return o;
}
function parseTransform(s?: string): Transform {
  if (!s || !s.trim()) return [...IDENTITY];
  const t = s.trim().split(/\s+/).map(Number);
  if (t.length !== 12 || t.some((v) => !Number.isFinite(v))) throw new ProjectFormatError('A 3MF transform is invalid.');
  return t;
}
const fmt = (v: number) => (Number.isInteger(v) ? String(v) : String(Number(v.toFixed(6))));

export async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const d = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes as Uint8Array<ArrayBuffer>));
  return Array.from(d, (b) => b.toString(16).padStart(2, '0')).join('');
}

export function readEntries(bytes: Uint8Array, limits = DEFAULT_LIMITS): Record<string, Uint8Array> {
  let total = 0;
  let count = 0;
  let files: Record<string, Uint8Array>;
  try {
    files = unzipSync(bytes, {
      filter: (f) => {
        if (f.name.endsWith('/')) return false;
        const name = f.name.replace(/^\//, '');
        if (!validEntryName(name)) throw new ProjectFormatError('The project contains an unsafe file path and was not opened.');
        if (++count > limits.maxEntries) throw new ProjectFormatError('The project contains too many files.');
        if (f.originalSize > limits.maxEntryBytes) throw new ProjectFormatError('The project is larger than Nozzle It All can open.');
        total += f.originalSize;
        if (total > limits.maxTotalBytes) throw new ProjectFormatError('The project is larger than Nozzle It All can open.');
        return true;
      },
    });
  } catch (e) {
    if (e instanceof ProjectFormatError) throw e;
    throw new ProjectFormatError('The project file is damaged or incomplete. It may have been only partly saved or downloaded.');
  }
  const out: Record<string, Uint8Array> = {};
  for (const [k, v] of Object.entries(files)) out[k.replace(/^\//, '')] = v;
  if (Object.keys(out).length === 0) throw new ProjectFormatError('The file is not a 3MF project.');
  return out;
}

function xml(bytes: Uint8Array | undefined, what: string): XmlElement {
  if (!bytes) throw new ProjectFormatError(`The 3MF is missing ${what}.`);
  try { return parseXml(strFromU8(bytes)); } catch (e) { throw new ProjectFormatError(e instanceof XmlError ? `Part of the 3MF is not valid: ${e.message}` : 'Part of the 3MF is not valid XML.'); }
}

export function readProject(bytes: Uint8Array, limits = DEFAULT_LIMITS): Project3mf {
  const entries = readEntries(bytes, limits);
  let root = MODEL_PATH;
  if (entries['_rels/.rels']) {
    const rels = xml(entries['_rels/.rels'], 'relationships');
    const target = children(rels, 'Relationship').find((r) => (r.attrs.Type ?? '').endsWith('/3dmodel'))?.attrs.Target?.replace(/^\//, '');
    if (target && entries[target] && validEntryName(target)) root = target;
  }
  const rootDoc = xml(entries[root], 'its model');
  const metadata: Record<string, string> = {};
  for (const m of children(rootDoc, 'metadata')) if (m.attrs.name) metadata[m.attrs.name] = m.text.trim();
  const cache = new Map<string, Map<number, XmlElement>>();
  const objectsIn = (path: string) => {
    let m = cache.get(path);
    if (!m) {
      const doc = path === root ? rootDoc : xml(entries[path], `the part ${path}`);
      m = new Map();
      const res = child(doc, 'resources');
      for (const o of res ? children(res, 'object') : []) {
        const id = Number(o.attrs.id);
        if (!Number.isInteger(id)) throw new ProjectFormatError('A 3MF object has no valid id.');
        m.set(id, o);
      }
      cache.set(path, m);
    }
    return m;
  };
  let triangles = 0;
  const flatten = (path: string, id: number, t: Transform, depth: number, vs: number[], ts: number[]) => {
    if (depth > 16) throw new ProjectFormatError('The 3MF nests components too deeply.');
    const obj = objectsIn(path).get(id);
    if (!obj) throw new ProjectFormatError(`The 3MF refers to a missing object (${id}).`);
    const mesh = child(obj, 'mesh');
    if (mesh) {
      const base = vs.length / 3;
      for (const v of children(child(mesh, 'vertices') ?? { children: [] } as unknown as XmlElement, 'vertex')) {
        const p = applyTransform(t, Number(v.attrs.x), Number(v.attrs.y), Number(v.attrs.z));
        vs.push(p[0], p[1], p[2]);
      }
      const count = vs.length / 3 - base;
      for (const tri of children(child(mesh, 'triangles') ?? { children: [] } as unknown as XmlElement, 'triangle')) {
        const a = Number(tri.attrs.v1), b = Number(tri.attrs.v2), c = Number(tri.attrs.v3);
        if (![a, b, c].every((x) => Number.isInteger(x) && x >= 0 && x < count)) throw new ProjectFormatError("A 3MF mesh refers to a vertex that doesn't exist.");
        ts.push(base + a, base + b, base + c);
        if (++triangles > limits.maxTriangles) throw new ProjectFormatError('The project has more triangles than this browser can open.');
      }
    }
    const comps = child(obj, 'components');
    for (const c of comps ? children(comps, 'component') : []) {
      const sub = (c.attrs['p:path'] ?? '').replace(/^\//, '') || path;
      if (!validEntryName(sub)) throw new ProjectFormatError('The project contains an unsafe part path.');
      flatten(sub, Number(c.attrs.objectid), compose(parseTransform(c.attrs.transform), t), depth + 1, vs, ts);
    }
  };
  const build = child(rootDoc, 'build');
  if (!build) throw new ProjectFormatError('The 3MF has no build plate contents.');
  const objects: ModelObject[] = children(build, 'item').map((item, index) => {
    const id = Number(item.attrs.objectid);
    const path = (item.attrs['p:path'] ?? '').replace(/^\//, '') || root;
    const vs: number[] = [];
    const ts: number[] = [];
    flatten(path, id, [...IDENTITY], 0, vs, ts);
    return { id, name: objectsIn(path).get(id)?.attrs.name || `Object ${index + 1}`, mesh: { vertices: new Float32Array(vs), triangles: new Uint32Array(ts) }, placement: parseTransform(item.attrs.transform) };
  });
  let manifest: ProjectManifest | null = null;
  let manifestProblem: string | undefined;
  if (entries[ARCHIVE_PATH]) {
    try {
      const m = parseManifest(strFromU8(entries[ARCHIVE_PATH]));
      const owner = metadata[META_PROJECT_ID];
      if (owner && owner !== m.projectId) manifestProblem = "The project's Nozzle details belong to a different project. Materials and printer choices were not restored.";
      else manifest = m;
    } catch (e) {
      if (e instanceof IncompatibleProjectError) throw e;
      manifestProblem = `The project's Nozzle details are damaged (${(e as Error).message}). The models opened; check materials and printer.`;
    }
  }
  const passthrough: Record<string, Uint8Array> = {};
  for (const [k, v] of Object.entries(entries)) if (!OWNED.has(k) && k !== root && !k.startsWith('3D/')) passthrough[k] = v;
  return { objects, metadata, manifest, passthrough, manifestProblem };
}

export async function writeProject(p: Project3mf): Promise<Uint8Array> {
  const meta: Record<string, string> = { ...p.metadata };
  if (p.manifest) {
    meta[META_PROJECT_ID] = p.manifest.projectId;
    meta[META_REVISION] = String(p.manifest.revision);
    meta[META_MANIFEST_SHA256] = await sha256Hex(strToU8(canonical(JSON.parse(serializeManifest(p.manifest)))));
  }
  const parts: string[] = ['<?xml version="1.0" encoding="UTF-8"?>\n<model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">\n'];
  for (const [k, v] of Object.entries(meta)) parts.push(` <metadata name="${escapeXml(k)}">${escapeXml(v)}</metadata>\n`);
  parts.push(' <resources>\n');
  for (const o of p.objects) {
    parts.push(`  <object id="${o.id}" name="${escapeXml(o.name)}" type="model">\n   <mesh>\n    <vertices>\n`);
    const v = o.mesh.vertices;
    for (let i = 0; i < v.length; i += 3) parts.push(`     <vertex x="${fmt(v[i])}" y="${fmt(v[i + 1])}" z="${fmt(v[i + 2])}"/>\n`);
    parts.push('    </vertices>\n    <triangles>\n');
    const t = o.mesh.triangles;
    for (let i = 0; i < t.length; i += 3) parts.push(`     <triangle v1="${t[i]}" v2="${t[i + 1]}" v3="${t[i + 2]}"/>\n`);
    parts.push('    </triangles>\n   </mesh>\n  </object>\n');
  }
  parts.push(' </resources>\n <build>\n');
  for (const o of p.objects) parts.push(`  <item objectid="${o.id}" transform="${o.placement.map(fmt).join(' ')}" printable="1"/>\n`);
  parts.push(' </build>\n</model>\n');
  const files: Record<string, Uint8Array> = {
    '[Content_Types].xml': strToU8('<?xml version="1.0" encoding="UTF-8"?>\n<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/><Default Extension="png" ContentType="image/png"/><Default Extension="json" ContentType="application/json"/><Default Extension="config" ContentType="text/xml"/><Default Extension="gcode" ContentType="text/x.gcode"/></Types>'),
    '_rels/.rels': strToU8(`<?xml version="1.0" encoding="UTF-8"?>\n<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/${MODEL_PATH}" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>`),
    [MODEL_PATH]: strToU8(parts.join('')),
  };
  if (p.manifest) files[ARCHIVE_PATH] = strToU8(serializeManifest(p.manifest));
  for (const [k, v] of Object.entries(p.passthrough)) if (validEntryName(k) && !OWNED.has(k)) files[k] = v;
  return zipSync(files, { level: 6 });
}

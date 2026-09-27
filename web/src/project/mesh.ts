// Model files the Web App opens (STL binary/ASCII, OBJ, 3MF), with the same limits as Desktop's MeshIO.
import { Mesh, readProject } from './threemf';
import { ProjectFormatError } from './manifest';

export const MAX_TRIANGLES = 10_000_000;

export function meshBounds(m: Mesh): [number, number, number, number, number, number] {
  const b: [number, number, number, number, number, number] = [Infinity, Infinity, Infinity, -Infinity, -Infinity, -Infinity];
  const v = m.vertices;
  for (let i = 0; i < v.length; i += 3) for (let a = 0; a < 3; a++) { b[a] = Math.min(b[a], v[i + a]); b[a + 3] = Math.max(b[a + 3], v[i + a]); }
  return b;
}

function checked(m: Mesh): Mesh {
  if (m.triangles.length === 0) throw new ProjectFormatError('The model is empty.');
  for (const x of m.vertices) if (!Number.isFinite(x)) throw new ProjectFormatError('The model contains invalid coordinates.');
  return m;
}

export function readStl(bytes: Uint8Array): Mesh {
  if (bytes.length >= 84) {
    const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const count = dv.getUint32(80, true);
    if (84 + count * 50 === bytes.length) {
      if (count > MAX_TRIANGLES) throw new ProjectFormatError('This model has more triangles than this browser can open.');
      const v = new Float32Array(count * 9);
      for (let i = 0; i < count; i++) for (let k = 0; k < 9; k++) v[i * 9 + k] = dv.getFloat32(84 + i * 50 + 12 + k * 4, true);
      const t = new Uint32Array(count * 3);
      for (let i = 0; i < t.length; i++) t[i] = i;
      return checked({ vertices: v, triangles: t });
    }
  }
  const text = new TextDecoder().decode(bytes);
  if (!text.trimStart().startsWith('solid')) throw new ProjectFormatError('This STL file is damaged or incomplete.');
  const nums: number[] = [];
  for (const m of text.matchAll(/vertex\s+(\S+)\s+(\S+)\s+(\S+)/g)) nums.push(Number(m[1]), Number(m[2]), Number(m[3]));
  if (nums.length === 0 || nums.length % 9 !== 0) throw new ProjectFormatError('This STL file has no complete triangles.');
  const t = new Uint32Array(nums.length / 3);
  for (let i = 0; i < t.length; i++) t[i] = i;
  return checked({ vertices: new Float32Array(nums), triangles: t });
}

export function readObj(text: string): Mesh {
  const v: number[] = [];
  const t: number[] = [];
  for (const raw of text.split('\n')) {
    const p = raw.trim().split(/\s+/);
    if (p[0] === 'v') v.push(Number(p[1]), Number(p[2]), Number(p[3]));
    else if (p[0] === 'f') {
      const idx = p.slice(1).map((s) => { const i = parseInt(s.split('/')[0], 10); return i < 0 ? v.length / 3 + i : i - 1; });
      for (let k = 1; k < idx.length - 1; k++) t.push(idx[0], idx[k], idx[k + 1]);
      if (t.length / 3 > MAX_TRIANGLES) throw new ProjectFormatError('This model has more triangles than this browser can open.');
    }
  }
  if (t.some((i) => !(i >= 0 && i < v.length / 3))) throw new ProjectFormatError("This OBJ file refers to points that don't exist.");
  return checked({ vertices: new Float32Array(v), triangles: new Uint32Array(t) });
}

/** Binary STL of a mesh, for handing to the engine. */
export function writeStl(m: Mesh): Uint8Array {
  const n = m.triangles.length / 3;
  const out = new Uint8Array(84 + n * 50);
  const dv = new DataView(out.buffer);
  dv.setUint32(80, n, true);
  for (let i = 0; i < n; i++) {
    const base = 84 + i * 50 + 12;
    for (let c = 0; c < 3; c++) { const vi = m.triangles[i * 3 + c] * 3; for (let a = 0; a < 3; a++) dv.setFloat32(base + (c * 3 + a) * 4, m.vertices[vi + a], true); }
  }
  return out;
}

export function readModelFile(name: string, bytes: Uint8Array): { name: string; mesh: Mesh }[] {
  const ext = name.toLowerCase().split('.').pop();
  const base = name.replace(/\.[^.]+$/, '');
  if (ext === 'stl') return [{ name: base, mesh: readStl(bytes) }];
  if (ext === 'obj') return [{ name: base, mesh: readObj(new TextDecoder().decode(bytes)) }];
  if (ext === '3mf') return readProject(bytes).objects.map((o) => ({ name: o.name, mesh: o.mesh }));
  throw new ProjectFormatError('Nozzle It All opens STL, OBJ and 3MF files.');
}

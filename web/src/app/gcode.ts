// Layered view of sliced output for the preview (port of desktop/.../GcodePreview.kt).
export interface Layer { z: number; segs: Float32Array; tools: Uint8Array }
export interface Preview { layers: Layer[]; truncated: boolean }
export const MAX_SEGMENTS = 3_000_000;

export function parseGcode(text: string): Preview {
  const layers: Layer[] = [];
  let segs: number[] = []; let tools: number[] = [];
  let x = 0, y = 0, z = 0, e = 0, relE = false, abs = true, tool = 0, layerZ = -1, total = 0, truncated = false;
  const flush = () => { if (tools.length) layers.push({ z: layerZ, segs: new Float32Array(segs), tools: new Uint8Array(tools) }); segs = []; tools = []; };
  for (const raw of text.split('\n')) {
    if (raw.startsWith(';LAYER_CHANGE') || raw.startsWith('; CHANGE_LAYER')) { flush(); layerZ = z; continue; }
    const line = raw.split(';')[0].trim();
    if (!line) continue;
    const cmd = line.split(' ')[0];
    if (cmd === 'G90') abs = true; else if (cmd === 'G91') abs = false; else if (cmd === 'M82') relE = false; else if (cmd === 'M83') relE = true;
    else if (/^T\d{1,2}$/.test(cmd)) tool = Number(cmd.slice(1));
    else if (cmd === 'G92') { for (const w of line.split(' ')) if (w[0] === 'E') e = Number(w.slice(1)) || e; }
    else if (cmd === 'G0' || cmd === 'G1') {
      let nx = x, ny = y, nz = z; let ne: number | undefined;
      for (const w of line.split(' ').slice(1)) { const v = Number(w.slice(1)); if (!Number.isFinite(v)) continue;
        if (w[0] === 'X') nx = abs ? v : x + v; else if (w[0] === 'Y') ny = abs ? v : y + v; else if (w[0] === 'Z') nz = abs ? v : z + v; else if (w[0] === 'E') ne = v; }
      const extruding = ne !== undefined && (relE ? ne > 0 : ne > e);
      if (ne !== undefined && !relE) e = ne;
      if (nz !== z) { if (layerZ < 0) layerZ = nz; z = nz; }
      if (extruding && (nx !== x || ny !== y)) { if (total < MAX_SEGMENTS) { segs.push(x, y, nx, ny); tools.push(tool); total++; } else truncated = true; }
      x = nx; y = ny;
    }
  }
  flush();
  return { layers, truncated };
}

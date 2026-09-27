// Slices a 20 mm cube with a shared printer profile in Node, using the same engine file and request format as the Web
// App's worker (web/src/engine/engine.worker.ts). Usage: node smoke_node.mjs <engine dir> [profile id]
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const engineDir = resolve(process.argv[2] ?? `${root}/web/public/engine`);
const profile = process.argv[3] ?? 'snapmaker_u1';
const mod = await import(pathToFileURL(`${engineDir}/nozzle-engine.js`).href);
const e = await mod.default({ locateFile: (f) => `${engineDir}/${f}` });
console.log('engine', e.ccall('nz_version', 'string', [], []));
const dir = '/job-1'; e.FS.mkdirTree(dir);
const lines = [`out\t${dir}/plate.gcode`];
for (const f of ['machine', 'process', 'filament']) {
  e.FS.writeFile(`${dir}/${f}.json`, readFileSync(`${root}/app/src/main/assets/slicer_profiles/${profile}/${f}.json`, 'utf8'));
  lines.push(`profile\t${dir}/${f}.json`);
}
lines.push('set\tlayer_height\t0.2', 'set\tsparse_infill_density\t15%');
e.FS.writeFile(`${dir}/cube.stl`, readFileSync(`${root}/site-src/assets/test-cube-20mm.stl`));
lines.push(`object\t${dir}/cube.stl\t0\t0\t0\t1\t0`);
const t0 = Date.now();
if (e.ccall('nz_slice_start', 'number', ['string'], [lines.join('\n')]) !== 0) throw new Error('busy');
let last = -1;
for (;;) {
  await new Promise((r) => setTimeout(r, 250));
  const s = e._nz_slice_status(), state = Math.floor(s / 1000), pct = s % 1000;
  if (state === 1) { if (pct !== last) { process.stdout.write(`${pct}% `); last = pct; } continue; }
  const p = e.ccall('nz_slice_result', 'number', [], []); const msg = e.UTF8ToString(p); e._nz_free(p);
  console.log(`\nstate ${state} after ${((Date.now() - t0) / 1000).toFixed(1)} s: ${msg}`);
  if (state !== 2) process.exit(1);
  const g = new TextDecoder().decode(e.FS.readFile(`${dir}/plate.gcode`));
  const grab = (re) => g.match(re)?.[1];
  console.log(`gcode ${g.length} bytes; layers ${grab(/; total layer number: (\d+)/)}; grams ${grab(/total filament used \[g\] = ([\d.]+)/) ?? grab(/; filament used \[g\] = ([\d., ]+)/)}; time ${grab(/estimated printing time \(normal mode\) = ([^\n]+)/) ?? grab(/total estimated time: ([^\n]+)/)}`);
  if (process.env.SAVE_GCODE) (await import("node:fs")).writeFileSync(process.env.SAVE_GCODE, g);
  process.exit(0);
}

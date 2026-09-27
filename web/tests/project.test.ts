import { describe, expect, it } from 'vitest';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { zipSync, strToU8 } from 'fflate';
import { readProject, writeProject, Project3mf, readEntries } from '../src/project/threemf';
import { newManifest, IncompatibleProjectError, ProjectFormatError, ARCHIVE_PATH, serializeManifest } from '../src/project/manifest';
import { readStl, writeStl } from '../src/project/mesh';
import { multiToolOverrides, GUIDED_PRESETS, gcodeStats } from '../src/project/slicing';

const FIX = new URL('../../schemas/fixtures/', import.meta.url);

function cube(s = 20) {
  return { vertices: new Float32Array([0, 0, 0, s, 0, 0, s, s, 0, 0, s, 0, 0, 0, s, s, 0, s, s, s, s, 0, s, s]),
    triangles: new Uint32Array([0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7]) };
}

function sample(): Project3mf {
  const m = newManifest('Two cubes', 'test', 'p-web-1');
  m.plates = [{ index: 1, name: 'Plate 1', objects: [{ objectId: 1, name: 'A', materialSlot: 2 }, { objectId: 2, name: 'B', materialSlot: 1 }] }];
  m.materials = [{ slot: 1, type: 'PLA', colorHex: '#BE38F3', toolhead: 0 }, { slot: 2, type: 'PETG', colorHex: '#FFFFFF', toolhead: 1 }];
  m.settings = { preset: 'standard', overrides: { sparse_infill_density: '20%' } };
  m.printer = { model: 'Bambu Lab A1', profileId: 'bambu_generic', family: 'bambu-lab' };
  m.modifiedAtMillis = 1; // fixed, so the committed fixture only changes when its content does
  (m as Record<string, unknown>).futureTopLevel = { x: 1 };
  m.extensions = { web: { cameraOrbit: 42 } };
  return { objects: [{ id: 1, name: 'A', mesh: cube(), placement: [1, 0, 0, 0, 1, 0, 0, 0, 1, 100, 100, 0] }, { id: 2, name: 'B', mesh: cube(10), placement: [1, 0, 0, 0, 1, 0, 0, 0, 1, 150, 120, 0] }],
    metadata: { Title: 'Two cubes', Designer: 'Someone' }, manifest: m, passthrough: { 'Metadata/project_settings.config': strToU8('{"layer_height":"0.2"}'), 'Auxiliaries/Other/notes.txt': strToU8('keep me') } };
}

describe('3MF project format (web)', () => {
  it('round-trips geometry, placement, manifest and unknown data', async () => {
    const back = readProject(await writeProject(sample()));
    expect(back.objects.length).toBe(2);
    expect(back.objects[0].mesh.triangles.length / 3).toBe(12);
    expect(back.objects[1].placement.slice(9)).toEqual([150, 120, 0]);
    expect(back.metadata.Designer).toBe('Someone');
    expect(back.metadata['nozzle:ProjectId']).toBe('p-web-1');
    expect((back.manifest as Record<string, unknown>).futureTopLevel).toEqual({ x: 1 });
    expect(back.manifest!.extensions).toEqual({ web: { cameraOrbit: 42 } });
    expect(new TextDecoder().decode(back.passthrough['Auxiliaries/Other/notes.txt'])).toBe('keep me');
  });

  it('refuses a newer major version but opens a damaged manifest from its geometry', async () => {
    const bytes = await writeProject(sample());
    const entries = readEntries(bytes);
    entries[ARCHIVE_PATH] = strToU8(JSON.stringify({ ...JSON.parse(serializeManifest(sample().manifest!)), version: [2, 0] }));
    expect(() => readProject(zipSync(entries))).toThrow(IncompatibleProjectError);
    entries[ARCHIVE_PATH] = strToU8('{not json');
    const p = readProject(zipSync(entries));
    expect(p.objects.length).toBe(2); expect(p.manifest).toBeNull(); expect(p.manifestProblem).toBeTruthy();
  });

  it('rejects unsafe archives and truncated files', async () => {
    expect(() => readProject(zipSync({ '../../etc/passwd': strToU8('x'), '3D/3dmodel.model': strToU8('<model/>') }))).toThrow(ProjectFormatError);
    expect(() => readProject(zipSync({ '3D/3dmodel.model': strToU8('<?xml version="1.0"?><!DOCTYPE m [<!ENTITY e SYSTEM "file:///etc/passwd">]><model>&e;</model>') }))).toThrow(ProjectFormatError);
    const b = await writeProject(sample());
    expect(() => readProject(b.slice(0, b.length / 2))).toThrow(ProjectFormatError);
  });

  it('writes and reads binary STL', () => {
    const m = readStl(writeStl(cube()));
    expect(m.triangles.length).toBe(36);
    expect(Math.max(...m.vertices)).toBe(20);
  });
});

describe('cross-platform fixtures', () => {
  it('reads the project Kotlin (Desktop/Android format library) wrote', () => {
    const f = new URL('kotlin-written.3mf', FIX);
    expect(existsSync(f), 'run :project-format:test to generate schemas/fixtures/kotlin-written.3mf').toBe(true);
    const p = readProject(new Uint8Array(readFileSync(f)));
    expect(p.manifest!.projectId).toBe('fixture-kotlin');
    expect(p.manifest!.plates[0].objects.map((o) => o.materialSlot)).toEqual([2, 1]);
    expect(p.objects[1].placement.slice(9)).toEqual([150, 120, 0]);
    expect((p.manifest!.extensions as Record<string, any>).android.keep).toBe(true);
    expect((p.manifest as Record<string, any>).futureTopLevel.fromKotlin).toBe(true);
    expect(p.manifest!.printer).toMatchObject({ model: 'Prusa MK4', profileId: 'prusa_generic', family: 'prusa' });
    expect(new TextDecoder().decode(p.passthrough['Metadata/project_settings.config'])).toContain('layer_height');
  });

  it('writes the fixture Kotlin checks (web-written.3mf)', async () => {
    const s = sample(); s.manifest!.projectId = 'fixture-web';
    const bytes = await writeProject(s);
    const target = new URL('web-written.3mf', FIX);
    // Keep the committed fixture stable: only rewrite when the content (not the zip timestamps) changed.
    const current = existsSync(target) ? readProject(new Uint8Array(readFileSync(target))) : null;
    if (!current || serializeManifest(current.manifest!) !== serializeManifest(readProject(bytes).manifest!)) writeFileSync(target, bytes);
    expect(readProject(new Uint8Array(readFileSync(target))).manifest!.projectId).toBe('fixture-web');
  });

  it('builds the same multi-material recipe as Android', () => {
    const f = JSON.parse(readFileSync(new URL('multitool.json', FIX), 'utf8'));
    expect(multiToolOverrides(f.input.diameter, f.input.slots, f.input.fallbackNozzleC)).toEqual(f.expected);
  });

  it('offers the shared guided presets', () => {
    expect(GUIDED_PRESETS.map((p) => [p.id, p.overrides.layer_height])).toEqual([['draft', '0.28'], ['standard', '0.2'], ['fine', '0.12']]);
  });

  it('reads slicing estimates from G-code comments', () => {
    const s = gcodeStats('; total layer number: 100\n; total filament used [g] = 3.70\n; filament used [mm] = 1239.00\n; estimated printing time (normal mode) = 9m 14s\nT0\nT0\nG1 X1\nT1\nG1 X2\nT0\n');
    expect(s).toEqual({ layers: 100, grams: 3.7, metres: 1.239, seconds: 554, toolChanges: 2 });
    expect(gcodeStats('T0\nG1 X1\nT0\n').toolChanges).toBe(0); // one material: no changes
  });
  it("reads Bambu's G-code layout (per-filament weights, time in the header)", () => {
    const s = gcodeStats('; model printing time: 8m 52s; total estimated time: 15m 47s\n; total layer number: 100\n; filament used [mm] = 1298.51\n; filament used [g] = 3.94, 0.50\n');
    expect(s.seconds).toBe(947); expect(s.grams).toBeCloseTo(4.44); expect(s.layers).toBe(100);
    // A total, when present, wins over the per-filament sum.
    expect(gcodeStats('; filament used [g] = 3.70, 3.66\n; total filament used [g] = 7.36\n').grams).toBe(7.36);
  });
});

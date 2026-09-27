import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { FULL_SPECTRUM, KNOWN_FAMILIES, familyLabel, migratePrinter, readCapabilities, NO_CAPABILITIES } from '../src/printers/model';
import { parseStatus } from '../src/printers/paxx';
import { bedOf, loadProfile, searchProfiles, ProfileIndex } from '../src/project/profiles';

const root = new URL('../../', import.meta.url);
const glossary = JSON.parse(readFileSync(new URL('design/terminology/glossary.json', root), 'utf8'));
const index: ProfileIndex = JSON.parse(readFileSync(new URL('app/src/main/assets/slicer_profiles/index.json', root), 'utf8'));

describe('multi-vendor model (same as printer-api)', () => {
  it('names every known family from the shared glossary', () => {
    const terms = JSON.stringify(glossary);
    for (const f of KNOWN_FAMILIES) { expect(terms).toContain(`family.${f}`); expect(familyLabel(f)).not.toBe(f); }
    expect(familyLabel('acme')).toBe('acme'); // an unknown future family still displays
  });
  it('reads printers saved before families existed', () => {
    expect(migratePrinter({ id: 'a', name: 'U1', model: 'Snapmaker U1', address: 'http://x', via: 'direct', firmware: 'paxx' } as any).family).toBe('paxx-u1');
    expect(migratePrinter({ id: 'b', name: 'K', model: 'K', address: 'http://x', via: 'direct', firmware: 'klipper' } as any).family).toBe('klipper');
    expect(migratePrinter({ id: 'c', name: 'B', model: 'X1C', address: '', via: 'connector', family: 'bambu-lab' }).family).toBe('bambu-lab');
    expect('firmware' in migratePrinter({ id: 'a', name: 'U1', model: 'U1', address: '', via: 'direct', firmware: 'stock-u1' } as any)).toBe(false);
  });
  it('keeps Full Spectrum as a namespaced extension, encoded as Kotlin encodes it', () => {
    const fixture = JSON.parse(readFileSync(new URL('adapter-paxx/src/test/resources/moonraker/u1_snapshot.json', root), 'utf8'));
    const s = parseStatus({ status: fixture.status }, 'lan');
    expect(FULL_SPECTRUM).toBe('snapmaker.full-spectrum');
    expect(Object.keys(s.extensions)).toEqual([FULL_SPECTRUM]);
    expect((s.extensions[FULL_SPECTRUM] as any).available).toBe(true);
    // A non-U1 Moonraker printer has no extension at all.
    const plain = { result: { status: { webhooks: { state: 'ready' }, print_stats: { state: 'standby' }, extruder: { temperature: 20 } } } };
    expect(parseStatus(plain.result, 'lan').extensions).toEqual({});
  });
  it('treats missing capabilities as unsupported', () => {
    expect(readCapabilities(undefined)).toEqual(NO_CAPABILITIES);
    const k = readCapabilities({ schema: 2, upload_and_start: true, accepted_outputs: ['gcode.3mf'] });
    expect(k.upload_and_start).toBe(true); expect(k.pause_print).toBe(false); expect(k.accepted_outputs).toEqual(['gcode.3mf']);
  });
});

describe('profiles (slicing needs no connection)', () => {
  it('has the shared index with provenance', () => {
    expect(index.source.commit).toMatch(/^[0-9a-f]{40}$/);
    expect(index.source.licence).toContain('AGPL');
    expect(index.profiles.length).toBeGreaterThan(100);
  });
  it('finds profiles from several makers', () => {
    expect(searchProfiles(index.profiles, 'prusa mk4').length).toBeGreaterThan(0);
    expect(searchProfiles(index.profiles, 'bambu').length).toBeGreaterThan(0);
    expect(searchProfiles(index.profiles, 'snapmaker u1').map((p) => p.id)).toContain('snapmaker_u1');
    expect(searchProfiles(index.profiles, 'no such printer zz')).toEqual([]);
  });
  it('reads bed sizes that match the index', () => {
    for (const id of ['snapmaker_u1', 'prusa_generic', 'bambu_generic', 'generic_klipper']) {
      const info = index.profiles.find((p) => p.id === id)!;
      const b = bedOf(readFileSync(new URL(`app/src/main/assets/slicer_profiles/${id}/machine.json`, root), 'utf8'));
      expect([b.w, b.d]).toEqual(info.bed); expect(b.h).toBe(info.height);
    }
  });
  it('loads a profile on demand and refuses unsafe names', async () => {
    const fetcher = (async (u: string) => new Response(readFileSync(new URL(`app/src/main/assets/slicer_profiles/${String(u).replace('/profiles/', '')}`, root)))) as unknown as typeof fetch;
    const f = await loadProfile('prusa_generic', fetcher);
    expect(JSON.parse(f.machine)).toBeTruthy(); expect(JSON.parse(f.process)).toBeTruthy(); expect(JSON.parse(f.filament)).toBeTruthy();
    await expect(loadProfile('../etc', fetcher)).rejects.toThrow();
  });
});

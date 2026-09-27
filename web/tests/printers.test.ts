import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { ActionGuard, commandFor, mapState, parseStatus } from '../src/printers/paxx';
import { Action, FULL_SPECTRUM, KNOWN_FAMILIES, Outcome, PrinterStatus, familyLabel, fullSpectrumOf, migratePrinter, readCapabilities, routeFor } from '../src/printers/model';

// The same recorded U1 status the Kotlin PAXX adapter is tested with.
const fixture = JSON.parse(readFileSync(new URL('../../adapter-paxx/src/test/resources/moonraker/u1_snapshot.json', import.meta.url), 'utf8'));

describe('PAXX status (web)', () => {
  it('maps the recorded U1 status exactly as the Kotlin adapter does', () => {
    const s = parseStatus({ status: fixture.status }, 'lan', 1);
    expect(s.state).toBe('printing');
    expect(s.job).toMatchObject({ fileName: 'Cube_TPU_27m50s.gcode', layer: 44, layers: 135 });
    expect(s.toolheads.length).toBe(4);
    expect(s.toolheads.find((t) => t.active)!.index).toBe(2);
    expect(s.toolheads[0].material).toEqual({ vendor: 'Polymaker', type: 'PLA', subType: 'Basic', colorHex: '#BE38F3', fromTag: true });
    expect(fullSpectrumOf(s)).toEqual({ available: true, palette: ['#BE38F3', '#E2DEDB', '#DD0000', '#000000'] });
  });

  it('uses the shared state vocabulary', () => {
    expect(mapState('ready', 'standby')).toBe('ready');
    expect(mapState('ready', 'complete')).toBe('finished');
    expect(mapState('shutdown', 'printing')).toBe('error');
    expect(mapState('startup', undefined)).toBe('starting');
    expect(mapState('ready', 'levitating')).toBe('unknown');
  });

  it('builds the same commands and validates before sending', () => {
    expect(commandFor({ kind: 'start', path: 'cube.gcode', toolheadMap: [2, 0] }).body!.options.map_table).toBe('[[0,2],[1,0]]');
    expect(commandFor({ kind: 'nozzleTemperature', toolhead: 2, celsius: 220 }).query!.script).toBe('SET_HEATER_TEMPERATURE HEATER=extruder2 TARGET=220');
    expect(() => commandFor({ kind: 'nozzleTemperature', toolhead: 4, celsius: 200 })).toThrow();
    expect(() => commandFor({ kind: 'start', path: '../x', toolheadMap: [] })).toThrow();
  });

  it('classifies private-network addresses', () => {
    expect(routeFor('192.168.1.40')).toBe('lan');
    expect(routeFor('100.101.5.6')).toBe('private-network');
    expect(routeFor('u1.tail1234.ts.net')).toBe('private-network');
  });

  it('never repeats a command whose result is unknown until the printer is re-checked', async () => {
    let state: PrinterStatus['state'] = 'printing'; let performed = 0; let now = 1000;
    const client = {
      status: async (): Promise<PrinterStatus> => ({ state, route: 'lan', toolheads: [], fullSpectrum: { available: false, palette: [] }, observedAt: now }),
      perform: async (_: Action): Promise<Outcome> => { performed++; return { kind: 'unknown', reason: 'timeout' }; },
    };
    const g = new ActionGuard(client);
    expect((await g.execute({ kind: 'pause' }, 'printing', ['printing'])).kind).toBe('unknown');
    expect((await g.execute({ kind: 'pause' }, 'printing', ['printing'])).kind).toBe('rejected');
    expect(performed).toBe(1);
    now = Date.now() + 10; state = 'paused';
    await g.reconcile();
    expect(g.needsReconcile).toBe(false);
    // Stale review: the printer changed since the user looked, so nothing is sent.
    expect((await g.execute({ kind: 'pause' }, 'printing', ['printing'])).kind).toBe('rejected');
    expect(performed).toBe(1);
  });
});

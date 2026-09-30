import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { ActionGuard, applyLanes, commandFor, lanesFromHappyHare, lanesFromLaneData, mapState, parseStatus, stockElegooCommand } from '../src/printers/paxx';
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

describe('cameras', () => {
  it('shows camera-streamer and mjpeg-streamer cameras live, and skips the screen mirror', async () => {
    const { parseCameras } = await import('../src/printers/paxx');
    const cams = parseCameras([
      { name: 'case', service: 'webrtc-camerastreamer', stream_url: '/webcam/webrtc', snapshot_url: '/webcam/snapshot.jpg' },
      { name: 'gui', stream_url: '/screen/', snapshot_url: '' },
      { name: 'webcam', service: 'mjpegstreamer-adaptive', stream_url: '/webcam/?action=stream', snapshot_url: '/webcam/?action=snapshot' },
      { name: 'still', service: 'other', stream_url: '', snapshot_url: '/snap.jpg' },
    ], 'http://192.168.1.113/');
    expect(cams).toEqual([
      { name: 'case', liveUrl: 'http://192.168.1.113/webcam/stream.mjpg', snapshotUrl: 'http://192.168.1.113/webcam/snapshot.jpg' },
      { name: 'webcam', liveUrl: 'http://192.168.1.113/webcam/?action=stream', snapshotUrl: 'http://192.168.1.113/webcam/?action=snapshot' },
      { name: 'still', snapshotUrl: 'http://192.168.1.113/snap.jpg' },
    ]);
  });
});

describe('filament-changer lanes (AFC, Happy Hare)', () => {
  // As AFC_lane.py send_lane_data writes each lane (AFC 484a09b, the commit COSMOS ships); same data as FilamentLanesTest.kt.
  const lane = (tool: string, color: string, material: string, nozzle: unknown = 220) =>
    ({ color, material, bed_temp: 60, nozzle_temp: nozzle, scan_time: '', td: '', lane: tool, extruder_index: 0, spool_id: null, weight: 1000 });
  const canvas = { CANVAS_1: lane('0', '#FF0000', 'PLA'), CANVAS_2: lane('1', '00ff00', 'PETG', 240), CANVAS_3: lane('2', '', '', ''), CANVAS_4: lane('3', '#0000FFFF', 'pla') };

  it('reads AFC lanes as slots by tool', () => {
    const lanes = lanesFromLaneData({ value: canvas })!;
    expect(lanes.map((l) => l.tool)).toEqual([0, 1, 2, 3]);
    expect(lanes.map((l) => l.colorHex)).toEqual(['#FF0000', '#00FF00', undefined, '#0000FF']);
    expect(lanes[1].nozzleTemp).toBe(240);
    expect(lanesFromLaneData({ value: { a: lane('', '#FF0000', 'PLA'), b: { ...lane('1', '#00FF00', 'PLA'), lane: 1 } } })).toBeUndefined();
  });

  it('reads Happy Hare gates', () => {
    const mmu = { num_gates: 4, gate_status: [1, 0, 2, -1], gate_material: ['PLA', 'PLA', 'ABS', 'PLA'], gate_color: ['ff8800', '000000', '#123456', 'ffffff'], gate_temperature: [210, 200, 250, 200] };
    expect(lanesFromHappyHare(mmu)!.map((l) => [l.tool, l.colorHex])).toEqual([[0, '#FF8800'], [2, '#123456']]);
    expect(lanesFromHappyHare({})).toBeUndefined();
  });

  it('puts the one nozzle on the feeding lane', () => {
    const base = { state: 'ready' as const, route: 'lan' as const, toolheads: [{ index: 0, nozzle: 215, target: 220, diameter: 0.4, loaded: false, active: true }], extensions: {}, observedAt: 1 };
    const s = applyLanes(base, lanesFromLaneData({ value: canvas })!, 'CANVAS_2');
    expect(s.toolheads.map((h) => h.nozzle)).toEqual([undefined, 215, undefined, undefined]);
    expect(s.toolheads.map((h) => h.active)).toEqual([false, true, false, false]);
    expect(s.toolheads[1].material).toEqual({ type: 'PETG', colorHex: '#00FF00', fromTag: false });
    expect(s.toolheads[2].material).toBeUndefined();
  });
});

describe('stock Elegoo guard', () => {
  it('finds M729/M8213 commands but not comments', () => {
    expect(stockElegooCommand('G28\n  m729 ; clean\nM8213\n')).toBe('M729');
    expect(stockElegooCommand('PRINT_START EXTRUDER=220\n; M729 in a comment\nM7290\n')).toBeUndefined();
  });
});

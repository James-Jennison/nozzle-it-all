// Messages between the page and the slicing worker. Versioned so a cached old worker and a new page can't
// misunderstand each other: a mismatch is reported and the worker is replaced.
export const ENGINE_PROTOCOL = 1;

export interface SliceObject {
  /** Binary STL of the object's mesh in its own coordinates. */
  stl: Uint8Array;
  x: number; y: number; rotationZ: number; scale: number;
  /** 1-based material slot; 0 = default. */
  tool: number;
}

export interface SliceJob {
  profiles: { name: string; json: string }[]; // machine, process, filament... in load order
  overrides: Record<string, string>;
  objects: SliceObject[];
}

export type ToWorker =
  | { type: 'hello'; protocol: number }
  | { type: 'slice'; id: number; job: SliceJob }
  | { type: 'cancel'; id: number };

export type FromWorker =
  | { type: 'ready'; protocol: number; engine: string; threads: boolean }
  | { type: 'progress'; id: number; percent: number; stage: string }
  | { type: 'done'; id: number; gcode: Uint8Array; seconds: number }
  | { type: 'failed'; id: number; message: string }
  | { type: 'cancelled'; id: number }
  | { type: 'fatal'; message: string };

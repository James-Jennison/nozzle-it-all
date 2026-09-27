// Slicing intent shared with Android and Desktop: the guided presets and the multi-material override recipe.
// Port of domain/.../MultiToolFilamentConfig.kt (Android) with the same keys and values; schemas/fixtures/multitool.json
// is checked by both the Kotlin and TypeScript tests.
import presets from '../../../schemas/slicing/guided-presets.json';

export interface GuidedPreset { id: string; label: string; detail: string; overrides: Record<string, string> }
export const GUIDED_PRESETS: GuidedPreset[] = presets.presets;

export interface SlotMaterial { type?: string; colorHex?: string; nozzleC?: number }

export const FLUSH_BETWEEN_TOOLS_MM3 = 84;
export const FLUSH_UNLOAD_LOAD_MM3 = 140;

const mm = (v: number) => (Number.isInteger(v) ? String(v) : String(v));

export function multiToolOverrides(baseFilamentDiameterMm: number, slots: SlotMaterial[], fallbackNozzleC = 210): Record<string, string> {
  if (slots.length === 0) throw new Error('At least one tool slot is required.');
  const n = slots.length;
  const o: Record<string, string> = {};
  o.filament_diameter = slots.map(() => mm(baseFilamentDiameterMm)).join(',');
  o.filament_colour = slots.map((s) => s.colorHex || '#FFFFFF').join(';');
  o.filament_type = slots.map((s) => s.type || 'PLA').join(';');
  o.nozzle_temperature = slots.map((s) => String(s.nozzleC ?? fallbackNozzleC)).join(',');
  o.nozzle_temperature_initial_layer = slots.map((s) => String(s.nozzleC ?? fallbackNozzleC)).join(',');
  o.flush_volumes_matrix = Array.from({ length: n * n }, (_, i) => (Math.floor(i / n) === i % n ? '0' : String(FLUSH_BETWEEN_TOOLS_MM3))).join(',');
  o.flush_volumes_vector = Array.from({ length: n * 2 }, () => String(FLUSH_UNLOAD_LOAD_MM3)).join(',');
  return o;
}

export interface GcodeStats { layers?: number; seconds?: number; grams?: number; metres?: number; toolChanges: number }

export function parseDuration(s: string): number | undefined {
  let total = 0; let any = false;
  for (const m of s.matchAll(/(\d+)([dhms])/g)) { any = true; total += Number(m[1]) * ({ d: 86400, h: 3600, m: 60, s: 1 } as Record<string, number>)[m[2]]; }
  return any ? total : undefined;
}

export function gcodeStats(text: string): GcodeStats {
  const st: GcodeStats = { toolChanges: 0 };
  // A change is a switch to a different tool. The first selection, and start G-code re-selecting the current tool, aren't.
  let tool: number | null = null;
  for (const l of text.split('\n')) {
    if (l.startsWith('; total layer number:')) st.layers = Number(l.split(':')[1]);
    else if (l.startsWith('; total filament used [g] =')) st.grams = Number(l.split('=')[1]);
    else if (l.startsWith('; filament used [mm] =')) st.metres = l.split('=')[1].split(',').reduce((a, b) => a + Number(b), 0) / 1000;
    else if (l.startsWith('; estimated printing time (normal mode) =')) st.seconds = parseDuration(l.split('=')[1]);
    else if (/^T\d{1,2}\s*$/.test(l)) { const t = Number(l.trim().slice(1)); if (tool !== null && t !== tool) st.toolChanges++; tool = t; }
  }
  return st;
}

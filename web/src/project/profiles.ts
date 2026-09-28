// Printer profiles, shared with Android and Desktop (app/src/main/assets/slicer_profiles, indexed by
// scripts/build_profile_index.py with the source commit and licence of every profile). Slicing needs only a profile,
// never a connection. The index and each profile are fetched on demand; the Snapmaker U1 profile is also bundled so the
// app can slice before anything else has been fetched, including offline on first use.
import u1Machine from '../../../app/src/main/assets/slicer_profiles/snapmaker_u1/machine.json?raw';
import u1Process from '../../../app/src/main/assets/slicer_profiles/snapmaker_u1/process.json?raw';
import u1Filament from '../../../app/src/main/assets/slicer_profiles/snapmaker_u1/filament.json?raw';
import unsupportedProfiles from '../../../engine/profiles/unsupported-profiles.json';

export interface ProfileInfo {
  id: string; vendor: string; model: string; name: string; bed: [number, number]; height: number; tools: number; nozzle?: number;
  gcodeFlavor?: string; outputs: string[]; family: string;
}
export interface ProfileIndex { version: number; source: { repository?: string; commit: string; licence: string }; profiles: ProfileInfo[] }
export interface ProfileFiles { machine: string; process: string; filament: string }

export const DEFAULT_PROFILE = 'snapmaker_u1';
export const DEFAULT_INFO: ProfileInfo = { id: DEFAULT_PROFILE, vendor: 'Snapmaker', model: 'Snapmaker U1', name: 'Snapmaker U1 (0.4 nozzle)', bed: [270, 270], height: 270,
  tools: 4, nozzle: 0.4, gcodeFlavor: 'klipper', outputs: ['gcode'], family: 'paxx-u1' };
const BUNDLED: ProfileFiles = { machine: u1Machine, process: u1Process, filament: u1Filament };

/**
 * Profiles the slicing engine (Snapmaker Orca base, like Android and Desktop) can't slice yet, with the reason
 * (engine/profiles/unsupported-profiles.json). They are left out of the index, so they can't be picked, and a project
 * saved with one keeps the current profile.
 */
export const UNSUPPORTED_PROFILES: Readonly<Record<string, string>> = unsupportedProfiles.profiles;

let index: Promise<ProfileIndex> | null = null;
export function profileIndex(fetcher: typeof fetch = fetch): Promise<ProfileIndex> {
  index ??= fetcher('/profiles/index.json').then((r) => { if (!r.ok) throw new Error(`profile index ${r.status}`); return r.json(); })
    .then((i: ProfileIndex) => ({ ...i, profiles: i.profiles.filter((p) => !(p.id in UNSUPPORTED_PROFILES)) }))
    .catch((e) => { index = null; throw e; });
  return index;
}

/** Profiles matching every word of the query (vendor, model or name), best-known makers first. */
export function searchProfiles(all: ProfileInfo[], query: string, limit = 40): ProfileInfo[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  return all.filter((p) => { const hay = `${p.vendor} ${p.model} ${p.name} ${p.id}`.toLowerCase(); return words.every((w) => hay.includes(w)); }).slice(0, limit);
}

const SAFE_ID = /^[a-z0-9_]+$/;
export async function loadProfile(id: string, fetcher: typeof fetch = fetch): Promise<ProfileFiles> {
  if (id === DEFAULT_PROFILE) return BUNDLED;
  if (!SAFE_ID.test(id)) throw new Error('That printer profile name is not valid.');
  if (id in UNSUPPORTED_PROFILES) throw new Error(`The ${id} printer profile can't be sliced by this version yet.`);
  const get = async (f: string) => { const r = await fetcher(`/profiles/${id}/${f}.json`); if (!r.ok) throw new Error(`Couldn't load the ${f} profile for ${id} (${r.status}).`); return r.text(); };
  const [machine, process, filament] = await Promise.all([get('machine'), get('process'), get('filament')]);
  return { machine, process, filament };
}

/** Bed size from a machine profile's printable_area ("0x0", "270x0", ...), with the printable height. */
export function bedOf(machineJson: string): { w: number; d: number; h: number } {
  try {
    const m = JSON.parse(machineJson);
    const pts = (m.printable_area as string[]).map((s) => s.split('x').map(Number));
    const xs = pts.map((p) => p[0]), ys = pts.map((p) => p[1]);
    const h = Number(Array.isArray(m.printable_height) ? m.printable_height[0] : m.printable_height);
    return { w: Math.max(...xs) - Math.min(...xs), d: Math.max(...ys) - Math.min(...ys), h: Number.isFinite(h) && h > 0 ? h : 250 };
  } catch { return { w: 270, d: 270, h: 270 }; }
}

// The optional local connector (nozzle-connector 1.x, docs/protocols/LOCAL_CONNECTOR.md): Nozzle It All for Desktop
// listening on this computer (127.0.0.1) so a secure web page can reach printers the browser itself may not.
// It is paired with a one-time code shown in Desktop, only answers Nozzle's own web origins, and only talks to
// printers the user added in Desktop. Nothing is relayed through Nozzle.
import { Action, Capabilities, Outcome, PrinterStatus, Route, SavedPrinter, readCapabilities } from './model';

export const CONNECTOR_URL = 'http://127.0.0.1:47321';
export const CONNECTOR_PROTOCOL = 'nozzle-connector';
const TOKEN_KEY = 'nozzle.connector.token';

async function call(path: string, init: RequestInit = {}, timeout = 8000): Promise<Response> {
  const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), timeout);
  const token = (() => { try { return localStorage.getItem(TOKEN_KEY); } catch { return null; } })();
  try {
    return await fetch(`${CONNECTOR_URL}${path}`, { ...init, signal: ctl.signal, credentials: 'omit', cache: 'no-store', redirect: 'error',
      // Chrome asks the user before a web page reaches a local address; this names that request as intentional.
      targetAddressSpace: 'loopback',
      headers: { ...(init.headers as Record<string, string> ?? {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) } } as RequestInit);
  } finally { clearTimeout(t); }
}

export const connector = {
  async hello(): Promise<{ ok: boolean; paired: boolean; app?: string; problem?: string }> {
    try {
      const r = await call('/v1/hello');
      const j = await r.json();
      if (j.protocol !== CONNECTOR_PROTOCOL) return { ok: false, paired: false, problem: "Something else is answering on the connector's port." };
      if (j.version?.[0] !== 1) return { ok: false, paired: false, problem: 'Nozzle It All for Desktop and this web app are different versions. Update both.' };
      return { ok: true, paired: !!j.paired, app: j.app };
    } catch {
      return { ok: false, paired: false, problem: "Nozzle It All for Desktop isn't running on this computer, or your browser blocked the local connection." };
    }
  },
  async pair(code: string): Promise<boolean> {
    const r = await call('/v1/pair', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code: code.trim() }) });
    if (!r.ok) return false;
    const j = await r.json();
    try { localStorage.setItem(TOKEN_KEY, j.token); } catch { return false; }
    return true;
  },
  forget() { try { localStorage.removeItem(TOKEN_KEY); } catch { /* ignore */ } },
  async printers(): Promise<SavedPrinter[]> {
    const r = await call('/v1/printers'); if (!r.ok) throw new Error('The connector refused the request. Pair this browser again.');
    return ((await r.json()).printers as { id: string; name: string; model: string; family: string; address: string; profileId?: string }[]).map((p) => ({
      id: p.id, name: p.name, model: p.model, family: p.family, address: p.address, profileId: p.profileId, via: 'connector' as const }));
  },
};

const STATE_BY_NAME: Record<string, PrinterStatus['state']> = { OFFLINE: 'offline', CONNECTING: 'connecting', STARTING: 'starting', READY: 'ready', PRINTING: 'printing',
  PAUSED: 'paused', FINISHED: 'finished', CANCELLED: 'cancelled', ERROR: 'error', UNKNOWN: 'unknown' };

/** Same interface as MoonrakerClient, through the connector. The printer id is Desktop's id for it. */
export class ConnectorClient {
  constructor(private p: SavedPrinter) {}
  /** Capabilities as Desktop's adapter for this printer reports them; null until Desktop has connected to it. */
  async capabilities(): Promise<Capabilities | null> {
    try { const r = await call(`/v1/printers/${encodeURIComponent(this.p.id)}/capabilities`); return r.ok ? readCapabilities(await r.json()) : null; } catch { return null; }
  }
  async status(): Promise<PrinterStatus> {
    try {
      const r = await call(`/v1/printers/${encodeURIComponent(this.p.id)}/status`);
      if (!r.ok) throw new Error(r.status === 401 ? 'This browser is no longer paired with Nozzle It All for Desktop.' : `The connector answered ${r.status}.`);
      const j = await r.json();
      const heads = (j.toolheads ?? []).map((t: any) => ({ index: t.index, nozzle: t.nozzleTemperature, target: t.nozzleTarget, diameter: t.nozzleDiameterMm, loaded: !!t.loaded, active: !!t.active,
        material: t.material ? { vendor: t.material.vendor, type: t.material.type, subType: t.material.subType, colorHex: t.material.colorHex, fromTag: !!t.material.fromTag } : undefined }));
      return { state: STATE_BY_NAME[j.state] ?? 'unknown', route: ({ PRIVATE_NETWORK: 'private-network', VENDOR_CLOUD: 'vendor-cloud', NONE: 'none' } as Record<string, Route>)[j.route] ?? 'lan',
        job: j.job ? { fileName: j.job.fileName, fraction: j.job.fraction, elapsed: j.job.elapsedSeconds, layer: j.job.currentLayer, layers: j.job.totalLayers } : undefined,
        bed: j.bed ? { current: j.bed.current, target: j.bed.target } : undefined, toolheads: heads,
        extensions: j.extensions ?? {}, message: j.message ?? undefined, observedAt: j.observedAtMillis ?? Date.now() };
    } catch (e) {
      return { state: 'offline', route: 'lan', toolheads: [], extensions: {}, message: (e as Error).message || 'The local connector did not answer.', observedAt: Date.now() };
    }
  }
  async upload(name: string, bytes: Uint8Array): Promise<{ ok: true; path: string } | { ok: false; interrupted: boolean; reason: string }> {
    try {
      const r = await call(`/v1/printers/${encodeURIComponent(this.p.id)}/upload?name=${encodeURIComponent(name)}`, { method: 'POST', body: bytes as BodyInit, headers: { 'Content-Type': 'application/octet-stream' } }, 30 * 60_000);
      const j = await r.json();
      return j.result === 'uploaded' ? { ok: true, path: j.remotePath } : { ok: false, interrupted: j.result === 'interrupted', reason: j.reason ?? 'The upload failed.' };
    } catch (e) { return { ok: false, interrupted: true, reason: `${(e as Error).message}. The file on the printer may be incomplete.` }; }
  }
  async perform(a: Action): Promise<Outcome> {
    try {
      const r = await call(`/v1/printers/${encodeURIComponent(this.p.id)}/action`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action: a }) }, 75_000);
      const j = await r.json();
      return j.outcome === 'accepted' ? { kind: 'accepted' } : j.outcome === 'rejected' ? { kind: 'rejected', reason: j.reason } : { kind: 'unknown', reason: j.reason ?? 'No reply.' };
    } catch (e) { return { kind: 'unknown', reason: `${(e as Error).message || 'No reply from the connector'}. Check the printer before trying again.` }; }
  }
}

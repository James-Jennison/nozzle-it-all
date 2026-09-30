// Projects live in this browser only: the origin-private file system (OPFS) where available, IndexedDB otherwise.
// Each project is the same canonical 3MF + Nozzle manifest used by Desktop and Android, so export is a straight copy.
// Nothing is uploaded anywhere.

export interface StoredProjectInfo { id: string; name: string; modified: number; objects: number; printer?: string; size: number }

interface Backend {
  list(): Promise<StoredProjectInfo[]>;
  read(id: string): Promise<Uint8Array>;
  write(info: StoredProjectInfo, bytes: Uint8Array): Promise<void>;
  remove(id: string): Promise<void>;
  kind: 'opfs' | 'indexeddb';
}

const INDEX = 'index.json';

class OpfsBackend implements Backend {
  kind = 'opfs' as const;
  private dir: Promise<FileSystemDirectoryHandle>;
  constructor() { this.dir = navigator.storage.getDirectory().then((root) => root.getDirectoryHandle('projects', { create: true })); }
  private async index(): Promise<StoredProjectInfo[]> {
    try { const f = await (await (await this.dir).getFileHandle(INDEX)).getFile(); return JSON.parse(await f.text()); } catch { return []; }
  }
  private async writeFile(name: string, data: Uint8Array | string) {
    const d = await this.dir;
    // Write to a temporary name then swap the index entry, so an interrupted save never replaces a good project.
    const tmp = await d.getFileHandle(name + '.part', { create: true });
    const w = await tmp.createWritable();
    await w.write(data as FileSystemWriteChunkType); await w.close();
    const final = await d.getFileHandle(name, { create: true });
    const w2 = await final.createWritable();
    await w2.write(await (await tmp.getFile()).arrayBuffer()); await w2.close();
    await d.removeEntry(name + '.part');
  }
  async list() { return (await this.index()).sort((a, b) => b.modified - a.modified); }
  async read(id: string) { const f = await (await (await this.dir).getFileHandle(`${id}.3mf`)).getFile(); return new Uint8Array(await f.arrayBuffer()); }
  async write(info: StoredProjectInfo, bytes: Uint8Array) {
    await this.writeFile(`${info.id}.3mf`, bytes);
    const idx = (await this.index()).filter((p) => p.id !== info.id); idx.push(info);
    await this.writeFile(INDEX, JSON.stringify(idx));
  }
  async remove(id: string) {
    const d = await this.dir;
    await d.removeEntry(`${id}.3mf`).catch(() => undefined);
    await this.writeFile(INDEX, JSON.stringify((await this.index()).filter((p) => p.id !== id)));
  }
}

class IdbBackend implements Backend {
  kind = 'indexeddb' as const;
  private db = new Promise<IDBDatabase>((resolve, reject) => {
    const r = indexedDB.open('nozzle-projects', 1);
    r.onupgradeneeded = () => { r.result.createObjectStore('files'); r.result.createObjectStore('info', { keyPath: 'id' }); };
    r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error);
  });
  private async tx<T>(stores: string[], mode: IDBTransactionMode, fn: (t: IDBTransaction) => IDBRequest<T> | void): Promise<T> {
    const db = await this.db;
    return new Promise((resolve, reject) => {
      const t = db.transaction(stores, mode); const req = fn(t);
      t.oncomplete = () => resolve(req ? req.result : (undefined as T)); t.onerror = () => reject(t.error); t.onabort = () => reject(t.error);
    });
  }
  async list() { return ((await this.tx(['info'], 'readonly', (t) => t.objectStore('info').getAll())) as StoredProjectInfo[]).sort((a, b) => b.modified - a.modified); }
  async read(id: string) { const v = await this.tx(['files'], 'readonly', (t) => t.objectStore('files').get(id)); if (!v) throw new Error('Project not found.'); return new Uint8Array(v as ArrayBuffer); }
  async write(info: StoredProjectInfo, bytes: Uint8Array) {
    await this.tx(['files', 'info'], 'readwrite', (t) => { t.objectStore('files').put(bytes.slice().buffer, info.id); t.objectStore('info').put(info); });
  }
  async remove(id: string) { await this.tx(['files', 'info'], 'readwrite', (t) => { t.objectStore('files').delete(id); t.objectStore('info').delete(id); }); }
}

export class ProjectStore {
  private backend: Backend;
  persisted = false;
  constructor() {
    const opfs = typeof navigator !== 'undefined' && !!navigator.storage?.getDirectory && typeof (globalThis as { FileSystemFileHandle?: { prototype: object } }).FileSystemFileHandle !== 'undefined' &&
      'createWritable' in (globalThis as unknown as { FileSystemFileHandle: { prototype: object } }).FileSystemFileHandle.prototype;
    this.backend = opfs ? new OpfsBackend() : new IdbBackend();
  }
  get kind() { return this.backend.kind; }
  /** Asks the browser not to evict projects under storage pressure. Some browsers decide on their own. */
  async requestPersistence(): Promise<boolean> { try { this.persisted = (await navigator.storage?.persist?.()) ?? false; } catch { this.persisted = false; } return this.persisted; }
  async usage(): Promise<{ used: number; quota: number } | null> { try { const e = await navigator.storage.estimate(); return { used: e.usage ?? 0, quota: e.quota ?? 0 }; } catch { return null; } }
  list() { return this.backend.list(); }
  read(id: string) { return this.backend.read(id); }
  write(info: StoredProjectInfo, bytes: Uint8Array) { return this.backend.write(info, bytes); }
  remove(id: string) { return this.backend.remove(id); }
}

export function download(bytes: Uint8Array, name: string, type = 'application/octet-stream') {
  const url = URL.createObjectURL(new Blob([bytes as BlobPart], { type }));
  const a = document.createElement('a'); a.href = url; a.download = name; a.rel = 'noopener';
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

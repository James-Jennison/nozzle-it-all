import { useRef, useState } from 'preact/hooks';
import { useStore } from '../store';
import { importFiles, navigate, newProject, notify, openBytes, openStored, projects, saveProject, store } from '../state';
import { engineSupport } from '../../engine/client';
import { Banner, Notice } from '../ui';
import { glossary } from '../../design/glossary';

export function Home() {
  const s = useStore(store);
  const fileRef = useRef<HTMLInputElement>(null);
  const openRef = useRef<HTMLInputElement>(null);
  const support = engineSupport();
  const [trash, setTrash] = useState<{ id: string; name: string; bytes: Uint8Array } | null>(null);
  return (
    <>
      <div class="page-head">
        <div class="grow">
          <h1>Projects</h1>
          <p class="muted">{glossary.explanations['web-local']} Projects are kept in this browser; export them to keep a copy or move them to Desktop or Android.</p>
        </div>
        <div class="btn-row">
          <button class="btn" onClick={() => openRef.current?.click()}>Open a project file</button>
          <button class="btn primary" onClick={() => { newProject(); fileRef.current?.click(); }} data-testid="new-project">New project from a model</button>
        </div>
        <input ref={fileRef} type="file" accept=".stl,.obj,.3mf" multiple hidden aria-label="Choose models"
          onChange={async (e) => { const f = (e.target as HTMLInputElement).files; if (f?.length) { await importFiles(f); navigate('/prepare/models'); } (e.target as HTMLInputElement).value = ''; }} />
        <input ref={openRef} type="file" accept=".3mf" hidden aria-label="Choose a project file"
          onChange={async (e) => { const f = (e.target as HTMLInputElement).files?.[0]; if (!f) return;
            try { openBytes(new Uint8Array(await f.arrayBuffer()), f.name.replace(/\.3mf$/i, '')); await saveProject(); navigate('/prepare/models'); }
            catch (err) { notify(`Couldn't open ${f.name}: ${(err as Error).message}`, 'danger'); }
            (e.target as HTMLInputElement).value = ''; }} />
      </div>
      <Notice />
      {!support.ok && <Banner kind="warning">This browser can't run Nozzle's slicing engine: {support.reasons.join(' ')} You can still open, arrange and export projects.</Banner>}
      {trash && <Banner action={{ label: 'Undo', onClick: async () => { await projects.write({ id: trash.id, name: trash.name, modified: Date.now(), objects: 0, size: trash.bytes.length }, trash.bytes); store.set({ projects: await projects.list() }); setTrash(null); } }}>{trash.name} was removed.</Banner>}
      {s.projects.length === 0 ? (
        <section class="card empty" aria-labelledby="empty-title">
          <img src="/brand/mark-violet.svg" alt="" />
          <h2 id="empty-title">Start with a model</h2>
          <p class="muted">Choose an STL, OBJ or 3MF file. It opens here, in your browser, and is sliced on this device. Nothing is uploaded.</p>
          <button class="btn primary" onClick={() => { newProject(); fileRef.current?.click(); }}>Choose a model</button>
        </section>
      ) : (
        <ul class="grid" style={{ listStyle: 'none', padding: 0, margin: 0 }} aria-label="Your projects">
          {s.projects.map((p) => (
            <li class="card">
              <h2>{p.name}</h2>
              <p class="small muted">{p.objects} object{p.objects === 1 ? '' : 's'}{p.printer ? ` · for ${p.printer}` : ''} · changed {new Date(p.modified).toLocaleString()}</p>
              <div class="btn-row">
                <button class="btn primary" onClick={async () => { try { await openStored(p.id); navigate('/prepare/models'); } catch (e) { notify(`Couldn't open ${p.name}: ${(e as Error).message}`, 'danger'); } }}>Open</button>
                <button class="btn quiet" onClick={async () => { const bytes = await projects.read(p.id); await projects.remove(p.id); setTrash({ id: p.id, name: p.name, bytes }); store.set({ projects: await projects.list() }); }}>Remove</button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

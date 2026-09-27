import { ComponentChildren } from 'preact';
import { useEffect, useRef } from 'preact/hooks';
import { PrinterState, Route, routeLabel, stateLabel } from '../printers/model';
import { store } from './state';

export function StatusPill({ state }: { state: PrinterState }) {
  return <span class={`pill ${state}`} style={{ color: `var(--status-${state})` }} aria-label={`Printer state: ${stateLabel(state)}`}>{stateLabel(state)}</span>;
}

export function RouteBadge({ route }: { route: Route }) {
  return <span class="small muted">{route === 'private-network' ? '🔒 ' : ''}{routeLabel(route)}</span>;
}

export function Progress({ value, label }: { value: number; label: string }) {
  const pct = Math.round(Math.min(1, Math.max(0, value)) * 100);
  return <div class="progress" role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}><span style={{ width: `${pct}%` }} /></div>;
}

export function Banner({ kind = 'info', children, action }: { kind?: 'info' | 'warning' | 'danger' | 'success'; children: ComponentChildren; action?: { label: string; onClick: () => void } }) {
  return <div class={`banner ${kind}`} role={kind === 'danger' ? 'alert' : 'status'}><p>{children}</p>{action && <button class="btn" onClick={action.onClick}>{action.label}</button>}</div>;
}

export function Notice() {
  const n = store.get().notice;
  if (!n) return null;
  return <Banner kind={n.kind} action={{ label: 'Dismiss', onClick: () => store.set({ notice: null }) }}>{n.text}</Banner>;
}

export function Field(p: { id: string; label: string; value: string; onInput: (v: string) => void; hint?: string; error?: string; type?: string; placeholder?: string; inputMode?: string; autoComplete?: string }) {
  return (
    <div class="field">
      <label for={p.id}>{p.label}</label>
      <input id={p.id} type={p.type ?? 'text'} value={p.value} placeholder={p.placeholder} inputMode={p.inputMode as never} autoComplete={p.autoComplete ?? 'off'}
        aria-describedby={p.hint || p.error ? `${p.id}-d` : undefined} aria-invalid={p.error ? 'true' : undefined} onInput={(e) => p.onInput((e.target as HTMLInputElement).value)} />
      {(p.error || p.hint) && <span id={`${p.id}-d`} class={p.error ? 'error' : 'hint'}>{p.error ?? p.hint}</span>}
    </div>
  );
}

/** Native <dialog>: focus is trapped by the browser, Escape cancels, focus starts on the safe choice. */
export function Confirm({ open, title, body, detail, confirmLabel, destructive, onConfirm, onCancel }: {
  open: boolean; title: string; body: string; detail?: string; confirmLabel: string; destructive?: boolean; onConfirm: () => void; onCancel: () => void }) {
  const ref = useRef<HTMLDialogElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const d = ref.current; if (!d) return;
    if (open && !d.open) { d.showModal(); cancelRef.current?.focus(); }
    if (!open && d.open) d.close();
  }, [open]);
  return (
    <dialog ref={ref} aria-labelledby="confirm-title" onCancel={(e) => { e.preventDefault(); onCancel(); }}>
      <h2 id="confirm-title">{title}</h2>
      <p>{body}</p>
      {detail && <p class="small muted">{detail}</p>}
      <div class="btn-row">
        <button ref={cancelRef} class="btn" onClick={onCancel}>Don't send</button>
        <button class={`btn ${destructive ? 'danger' : 'primary'}`} onClick={onConfirm} data-testid="confirm-action">{confirmLabel}</button>
      </div>
    </dialog>
  );
}

export const fmtDuration = (s?: number) => (s === undefined || !Number.isFinite(s) ? '–' : s >= 3600 ? `${Math.floor(s / 3600)} h ${Math.floor((s % 3600) / 60)} min` : `${Math.max(1, Math.round(s / 60))} min`);

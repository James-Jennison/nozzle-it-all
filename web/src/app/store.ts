// A tiny observable store: the app's state lives in one place, components subscribe with useStore.
import { useEffect, useState } from 'preact/hooks';

export class Store<T extends object> {
  private listeners = new Set<() => void>();
  constructor(private state: T) {}
  get(): T { return this.state; }
  set(patch: Partial<T> | ((s: T) => Partial<T>)) {
    const p = typeof patch === 'function' ? patch(this.state) : patch;
    this.state = { ...this.state, ...p };
    this.listeners.forEach((l) => l());
  }
  subscribe(l: () => void) { this.listeners.add(l); return () => { this.listeners.delete(l); }; }
}

export function useStore<T extends object>(store: Store<T>): T {
  const [, force] = useState(0);
  useEffect(() => store.subscribe(() => force((n) => n + 1)), [store]);
  return store.get();
}

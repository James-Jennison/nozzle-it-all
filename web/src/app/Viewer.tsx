// The plate in 3D (three.js, WebGL2). Shows the U1 bed, each object in its material colour, and, after slicing, the
// toolpaths layer by layer. Drag to orbit, right-drag or Shift-drag to pan, wheel or pinch to zoom; arrow keys orbit.
import { useEffect, useRef } from 'preact/hooks';
import * as THREE from 'three';
import { PlateItem, bed, placement } from './state';
import { Preview } from './gcode';

interface Props { items: PlateItem[]; selected: number | null; colors: string[]; preview?: Preview; layer?: number; onSelect?: (id: number | null) => void; label: string }

function colorOf(css: string) { return new THREE.Color(css); }

export function Viewer({ items, selected, colors, preview, layer = 0, onSelect, label }: Props) {
  const host = useRef<HTMLDivElement>(null);
  const ctx = useRef<{ renderer: THREE.WebGLRenderer; scene: THREE.Scene; camera: THREE.PerspectiveCamera; content: THREE.Group; orbit: { yaw: number; pitch: number; dist: number; tx: number; ty: number }; render: () => void } | null>(null);

  useEffect(() => {
    const el = host.current!;
    let renderer: THREE.WebGLRenderer;
    try { renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true }); }
    catch { el.insertAdjacentHTML('beforeend', '<p class="hint">3D view unavailable: this browser has WebGL turned off. Slicing and export still work.</p>'); return; }
    renderer.setPixelRatio(Math.min(2, devicePixelRatio));
    el.appendChild(renderer.domElement);
    renderer.domElement.setAttribute('role', 'img');
    renderer.domElement.tabIndex = 0;
    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(40, 1, 1, 5000);
    scene.add(new THREE.HemisphereLight(0xffffff, 0x223344, 1.1));
    const sun = new THREE.DirectionalLight(0xffffff, 1.2); sun.position.set(-200, -300, 500); scene.add(sun);
    const styles = getComputedStyle(document.documentElement);
    const bedMat = new THREE.MeshBasicMaterial({ color: colorOf(styles.getPropertyValue('--surface').trim() || '#14191D') });
    const bedMesh = new THREE.Mesh(new THREE.PlaneGeometry(bed.w, bed.d), bedMat); bedMesh.position.set(bed.w / 2, bed.d / 2, -0.01); scene.add(bedMesh);
    const grid = new THREE.GridHelper(Math.max(bed.w, bed.d), Math.round(Math.max(bed.w, bed.d) / 25), colorOf(styles.getPropertyValue('--line-strong').trim() || '#4A545B'), colorOf(styles.getPropertyValue('--line').trim() || '#2A3138'));
    grid.rotation.x = Math.PI / 2; grid.position.set(bed.w / 2, bed.d / 2, 0); scene.add(grid);
    const content = new THREE.Group(); scene.add(content);
    const orbit = { yaw: -0.6, pitch: 0.95, dist: 520, tx: 0, ty: 0 };
    const render = () => {
      const w = el.clientWidth, h = el.clientHeight;
      renderer.setSize(w, h, false); camera.aspect = w / Math.max(1, h); camera.updateProjectionMatrix();
      const cx = bed.w / 2 + orbit.tx, cy = bed.d / 2 + orbit.ty;
      camera.up.set(0, 0, 1);
      camera.position.set(cx + orbit.dist * Math.sin(orbit.pitch) * Math.sin(orbit.yaw), cy - orbit.dist * Math.sin(orbit.pitch) * Math.cos(orbit.yaw), orbit.dist * Math.cos(orbit.pitch));
      camera.lookAt(cx, cy, 0);
      renderer.render(scene, camera);
    };
    ctx.current = { renderer, scene, camera, content, orbit, render };
    let drag: { x: number; y: number; pan: boolean; moved: boolean } | null = null;
    const pointers = new Map<number, { x: number; y: number }>();
    const onDown = (e: PointerEvent) => { renderer.domElement.setPointerCapture(e.pointerId); pointers.set(e.pointerId, { x: e.clientX, y: e.clientY }); drag = { x: e.clientX, y: e.clientY, pan: e.button === 2 || e.shiftKey, moved: false }; };
    const onMove = (e: PointerEvent) => {
      const prev = pointers.get(e.pointerId); if (!prev || !drag) return;
      if (pointers.size === 2) { // pinch zoom
        const [a, b] = [...pointers.values()]; const before = Math.hypot(a.x - b.x, a.y - b.y);
        pointers.set(e.pointerId, { x: e.clientX, y: e.clientY }); const [c, d] = [...pointers.values()]; const after = Math.hypot(c.x - d.x, c.y - d.y);
        if (before > 0) orbit.dist = Math.min(2500, Math.max(80, orbit.dist * before / Math.max(1, after))); render(); return;
      }
      const dx = e.clientX - prev.x, dy = e.clientY - prev.y; pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
      if (Math.abs(dx) + Math.abs(dy) > 2) drag.moved = true;
      if (drag.pan) { orbit.tx -= dx * orbit.dist / 900; orbit.ty += dy * orbit.dist / 900; } else { orbit.yaw -= dx * 0.008; orbit.pitch = Math.min(1.5, Math.max(0.05, orbit.pitch - dy * 0.006)); }
      render();
    };
    const onUp = (e: PointerEvent) => {
      pointers.delete(e.pointerId);
      if (drag && !drag.moved && onSelect) {
        const r = renderer.domElement.getBoundingClientRect();
        const ray = new THREE.Raycaster(); ray.setFromCamera(new THREE.Vector2(((e.clientX - r.left) / r.width) * 2 - 1, -((e.clientY - r.top) / r.height) * 2 + 1), camera);
        const hit = ray.intersectObjects(content.children, false)[0];
        onSelect(hit ? (hit.object.userData.id as number) ?? null : null);
      }
      drag = null;
    };
    const onWheel = (e: WheelEvent) => { e.preventDefault(); orbit.dist = Math.min(2500, Math.max(80, orbit.dist * (1 + Math.sign(e.deltaY) * 0.1))); render(); };
    const onKey = (e: KeyboardEvent) => {
      const k: Record<string, () => void> = { ArrowLeft: () => (orbit.yaw += 0.1), ArrowRight: () => (orbit.yaw -= 0.1), ArrowUp: () => (orbit.pitch = Math.max(0.05, orbit.pitch - 0.08)),
        ArrowDown: () => (orbit.pitch = Math.min(1.5, orbit.pitch + 0.08)), '+': () => (orbit.dist = Math.max(80, orbit.dist * 0.9)), '-': () => (orbit.dist = Math.min(2500, orbit.dist * 1.1)) };
      if (k[e.key]) { e.preventDefault(); k[e.key](); render(); }
    };
    const c = renderer.domElement;
    c.addEventListener('pointerdown', onDown); c.addEventListener('pointermove', onMove); c.addEventListener('pointerup', onUp);
    c.addEventListener('wheel', onWheel, { passive: false }); c.addEventListener('keydown', onKey); c.addEventListener('contextmenu', (e) => e.preventDefault());
    const ro = new ResizeObserver(render); ro.observe(el);
    render();
    return () => { ro.disconnect(); renderer.dispose(); c.remove(); ctx.current = null; };
  }, []);

  useEffect(() => {
    const c = ctx.current; if (!c) return;
    c.renderer.domElement.setAttribute('aria-label', label);
    c.content.children.forEach((o) => { (o as THREE.Mesh).geometry?.dispose(); });
    c.content.clear();
    if (preview && preview.layers.length) {
      const upTo = Math.min(layer, preview.layers.length - 1);
      for (let li = Math.max(0, upTo - 80); li <= upTo; li++) {
        const L = preview.layers[li]; const current = li === upTo;
        const byTool = new Map<number, number[]>();
        const stride = current ? 1 : Math.max(1, Math.floor(L.tools.length / 4000));
        for (let i = 0; i < L.tools.length; i += stride) { const arr = byTool.get(L.tools[i]) ?? []; arr.push(L.segs[i * 4], L.segs[i * 4 + 1], L.z, L.segs[i * 4 + 2], L.segs[i * 4 + 3], L.z); byTool.set(L.tools[i], arr); }
        for (const [tool, pts] of byTool) {
          const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(pts, 3));
          c.content.add(new THREE.LineSegments(g, new THREE.LineBasicMaterial({ color: colorOf(colors[tool] ?? '#A78BFA'), transparent: !current, opacity: current ? 1 : 0.25 })));
        }
      }
    } else {
      for (const it of items) {
        const g = new THREE.BufferGeometry();
        g.setAttribute('position', new THREE.BufferAttribute(it.mesh.vertices, 3)); g.setIndex(new THREE.BufferAttribute(it.mesh.triangles, 1)); g.computeVertexNormals();
        const col = colorOf(colors[it.slot - 1] ?? '#A78BFA'); if (it.id === selected) col.lerp(new THREE.Color('#ffffff'), 0.2);
        const mesh = new THREE.Mesh(g, new THREE.MeshStandardMaterial({ color: col, flatShading: true, roughness: 0.7 }));
        const t = placement(it);
        mesh.matrixAutoUpdate = false;
        mesh.matrix.set(t[0], t[3], t[6], t[9], t[1], t[4], t[7], t[10], t[2], t[5], t[8], t[11], 0, 0, 0, 1);
        mesh.userData.id = it.id;
        c.content.add(mesh);
      }
    }
    c.render();
  }, [items, selected, colors.join(), preview, layer, label]);

  return <div class="viewer" ref={host}><p class="hint" aria-hidden="true">Drag to orbit · Shift-drag to pan · Scroll or pinch to zoom · Arrow keys when focused</p></div>;
}

import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { FCS, km, nearestFc } from "../api/geo";
import type { Dot, Region } from "../api/types";

const COLORS: Record<Region, string> = {
  "us-east": "#1f4bd8",
  "us-central": "#0b8043",
  "us-south": "#f2a900",
  "us-west": "#8e3ccb",
};


function useWidth(min: number) {
  const ref = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(min * 2);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setW(Math.max(min, e.contentRect.width)));
    ro.observe(el);
    return () => ro.disconnect();
  }, [min]);
  return [ref, w] as const;
}

/** Couriers grouped by the FC they drive out of. */
export function groupByFc(dots: Dot[]) {
  const m = new Map<string, Dot[]>(FCS.map((f) => [f.id, []]));
  for (const d of dots) m.get(nearestFc(d.lat, d.lon).id)!.push(d);
  return m;
}

/**
 * The network as a transit diagram, not a basemap. Each region is a line through the FCs that
 * serve it first (inventory-svc reserves from the customer's region before any other), drawn
 * on a 45°/90° grid in rough geographic order. A grey trunk joins the regions: when a region
 * runs short, stock is allocated from another one, at a four-day promise instead of two.
 * Station legends carry each FC's live courier count.
 */
const LAYOUT: Record<string, [number, number]> = {
  // grid units, x right, y down; schematic positions in geographic order
  "west-2": [0, 0],
  "west-1": [0, 3],
  "central-1": [5, 1],
  "south-1": [4, 4],
  "east-1": [8, 0.5],
  "east-2": [8, 2],
};
// Narrow screens: the same network cascading down the page, legends to the right.
const NARROW: Record<string, [number, number]> = {
  "west-2": [0, 0],
  "west-1": [0, 1.6],
  "south-1": [1, 3.4],
  "central-1": [2, 5.2],
  "east-1": [3, 7],
  "east-2": [3, 8.6],
};
export function NetworkMap({ groups }: { groups: Map<string, Dot[]> }) {
  const [box, w] = useWidth(300);
  const narrow = w < 560;
  const layout = narrow ? NARROW : LAYOUT;
  const padX = narrow ? 24 : 64;
  const ux = narrow ? Math.max(24, (w - padX * 2 - 150) / 3) : (w - padX * 2 - 150) / 8;
  const uy = narrow ? 44 : 56;
  const rows = narrow ? 8.6 : 4;
  const h = Math.round(uy * rows + (narrow ? 84 : 104));
  const P = (id: string) => ({ x: padX + layout[id][0] * ux, y: (narrow ? 26 : 52) + layout[id][1] * uy });
  const fc = (id: string) => FCS.find((f) => f.id === id)!;
  // 45° connector between two points: horizontal, then diagonal, then horizontal.
  const octo = (a: { x: number; y: number }, b: { x: number; y: number }) => {
    const dy = b.y - a.y;
    const dx = b.x - a.x;
    if (Math.abs(dx) < Math.abs(dy)) {
      const s = Math.sign(dy) * Math.abs(dx);
      const mid = a.y + (dy - s) / 2;
      return `M${a.x},${a.y} V${mid} L${b.x},${mid + s} V${b.y}`;
    }
    const s = Math.sign(dx) * Math.abs(dy);
    const mid = a.x + (dx - s) / 2;
    return `M${a.x},${a.y} H${mid} L${mid + s},${b.y} H${b.x}`;
  };
  const trunk = ["west-1", "south-1", "central-1", "east-2"];
  const regionLines: [string, string][] = [
    ["west-2", "west-1"],
    ["east-1", "east-2"],
  ];
  return (
    <div ref={box} className="netmap">
      <svg width={w} height={h} role="img" aria-label={`Network diagram: ${FCS.map((f) => `${f.city} ${groups.get(f.id)?.length ?? 0} couriers`).join(", ")}.`}>
        {trunk.slice(1).map((id, i) => (
          <path key={id} d={octo(P(trunk[i]), P(id))} fill="none" stroke="var(--unreached)" strokeWidth="3" strokeLinejoin="round" />
        ))}
        <path d={octo(P("west-2"), P("central-1"))} fill="none" stroke="var(--unreached)" strokeWidth="3" strokeLinejoin="round" />
        <path d={octo(P("central-1"), P("east-1"))} fill="none" stroke="var(--unreached)" strokeWidth="3" strokeLinejoin="round" />
        {regionLines.map(([a, b]) => (
          <path key={a} d={octo(P(a), P(b))} fill="none" stroke={COLORS[fc(a).region]} strokeWidth="var(--line)" strokeLinecap="round" strokeLinejoin="round" />
        ))}
        {FCS.map((f) => {
          const p = P(f.id);
          const n = groups.get(f.id)?.length ?? 0;
          // a short region stub through single-FC regions, so every FC sits on its line
          const stub = f.id === "central-1" || f.id === "south-1";
          return (
            <g key={f.id} transform={`translate(${p.x},${p.y})`}>
              {stub && <line x1={-16} x2={16} y1="0" y2="0" stroke={COLORS[f.region]} strokeWidth="var(--line)" strokeLinecap="round" />}
              <circle r="10" fill="var(--surface)" stroke="var(--ink)" strokeWidth="3.5" />
              {/* legends sit above their station (wide) or to its right (narrow), clear of the trunk */}
              <g transform={narrow ? "translate(18,-3)" : "translate(14,-30)"}>
                <text className="nm-label">{f.city.toUpperCase()}</text>
                <text y="15" className="nm-sub">
                  {f.id} · {n} courier{n === 1 ? "" : "s"}
                </text>
              </g>
            </g>
          );
        })}
        <g transform={`translate(${padX},${h - 18})`} className="nm-sub">
          <line x1="0" x2="26" y1="-4" y2="-4" stroke="var(--unreached)" strokeWidth="3" />
          <text x="34" y="0" className="nm-sub">
            cross-region allocation (4-day promise)
          </text>
        </g>
      </svg>
    </div>
  );
}

/** One FC's delivery area, 25 km around it, couriers as dots on a canvas. */
export function MetroInset({ fc, dots }: { fc: (typeof FCS)[number]; dots: Dot[] }) {
  const [box, w] = useWidth(140);
  const h = Math.round(w * 0.78);
  const canvas = useRef<HTMLCanvasElement>(null);
  const radiusKm = 25;
  const scale = Math.min(w, h) / 2 / radiusKm; // px per km
  const kx = Math.cos((fc.lat * Math.PI) / 180) * 111;
  useEffect(() => {
    const c = canvas.current;
    if (!c) return;
    const dpr = window.devicePixelRatio || 1;
    c.width = w * dpr;
    c.height = h * dpr;
    const g = c.getContext("2d")!;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);
    g.fillStyle = COLORS[fc.region];
    for (const d of dots) {
      const x = w / 2 + (d.lon - fc.lon) * kx * scale;
      const y = h / 2 - (d.lat - fc.lat) * 111 * scale;
      g.beginPath();
      g.arc(x, y, 2.6, 0, Math.PI * 2);
      g.fill();
    }
  }, [dots, w, h, fc, kx, scale]);
  const farthest = dots.reduce((m, d) => Math.max(m, km(fc, d)), 0);
  return (
    <figure className="inset">
      <div ref={box} className="inset-map" style={{ height: h }}>
        <svg width={w} height={h} aria-hidden="true">
          {[5, 10, 20].map((k) => (
            <circle key={k} cx={w / 2} cy={h / 2} r={k * scale} fill="none" stroke="var(--rule)" strokeWidth="1" />
          ))}
          <text x={w / 2 + 20 * scale * 0.71 + 4} y={h / 2 - 20 * scale * 0.71} className="nm-sub">
            20 km
          </text>
        </svg>
        <canvas ref={canvas} style={{ width: w, height: h }} />
        <svg width={w} height={h} aria-hidden="true">
          <circle cx={w / 2} cy={h / 2} r="7" fill="var(--surface)" stroke="var(--ink)" strokeWidth="3" />
        </svg>
      </div>
      <figcaption>
        <strong>{fc.city}</strong>
        <span className="muted">{fc.id}</span>
        <span className="num">
          {dots.length} · farthest {farthest.toFixed(1)} km
        </span>
      </figcaption>
    </figure>
  );
}

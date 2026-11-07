import { useLayoutEffect, useRef, useState } from "react";
import { km, nearestFc } from "../api/geo";
import type { Region, Report } from "../api/types";
import { REGION_LETTER, REGION_VAR, clock, day } from "../ui/kit";

type Pt = { x: number; y: number };

/**
 * The courier's run as a route diagram, the way a transit map draws a line: the reported track
 * is simplified to its legs and each leg snapped to 45° or 90°, keeping its length. The FC is
 * the interchange where Picked and Loaded were scanned; the courier is the line's bullet; the
 * door is a terminus ahead, dashed because the platform does not know the address, carrying
 * the promised date. Distances along the line are true; directions are schematic.
 */
export function RouteMap({
  positions,
  scans,
  region,
  delivered,
  promised,
}: {
  positions: Report[];
  scans: Report[];
  region: Region;
  delivered: boolean;
  promised: string | null;
}) {
  const box = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(900);
  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setW(Math.max(280, e.contentRect.width)));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const pts = positions.filter((p) => p.lat !== null && p.lon !== null) as (Report & { lat: number; lon: number })[];
  const color = REGION_VAR[region];
  const ink = region === "us-south" ? "var(--ink)" : "#fff";
  const scanAt = (t: string) => scans.find((s) => s.scanType === t);

  if (pts.length === 0) {
    const out = scanAt("OUT_FOR_DELIVERY");
    return (
      <div ref={box} className="routemap routemap-empty">
        <svg width="72" height="24" aria-hidden="true">
          <line x1="12" x2="60" y1="12" y2="12" stroke="var(--unreached)" strokeWidth="2" strokeDasharray="2 6" strokeLinecap="round" />
          <circle cx="12" cy="12" r="8" fill="var(--surface)" stroke="var(--ink)" strokeWidth="3" />
          <rect x="54" y="6" width="12" height="12" fill="var(--unreached)" />
        </svg>
        <p>
          {out
            ? "Out for delivery. The route draws itself from the courier’s first position report."
            : "The route appears once the courier leaves the fulfillment center with the package."}
        </p>
      </div>
    );
  }

  const fc = nearestFc(pts[0].lat, pts[0].lon);
  const kx = Math.cos((fc.lat * Math.PI) / 180) * 111;
  const toKm = (p: { lat: number; lon: number }): Pt => ({ x: (p.lon - fc.lon) * kx, y: -(p.lat - fc.lat) * 111 });
  // 1. legs: merge consecutive moves while the heading stays within 25°
  const raw = [{ x: 0, y: 0 }, ...pts.map(toKm)];
  const legs: { len: number; ang: number }[] = [];
  for (let i = 1; i < raw.length; i++) {
    const dx = raw[i].x - raw[i - 1].x;
    const dy = raw[i].y - raw[i - 1].y;
    const len = Math.hypot(dx, dy);
    if (len < 1e-6) continue;
    const ang = Math.atan2(dy, dx);
    const last = legs[legs.length - 1];
    if (last && Math.abs(Math.atan2(Math.sin(ang - last.ang), Math.cos(ang - last.ang))) < (25 * Math.PI) / 180) {
      const lx = last.len * Math.cos(last.ang) + dx;
      const ly = last.len * Math.sin(last.ang) + dy;
      last.len = Math.hypot(lx, ly);
      last.ang = Math.atan2(ly, lx);
    } else legs.push({ len, ang });
  }
  // 2. snap each leg to the octilinear grid, keeping its length
  const snap = (a: number) => Math.round(a / (Math.PI / 4)) * (Math.PI / 4);
  const track: Pt[] = [{ x: 0, y: 0 }];
  for (const l of legs) {
    const p = track[track.length - 1];
    track.push({ x: p.x + l.len * Math.cos(snap(l.ang)), y: p.y + l.len * Math.sin(snap(l.ang)) });
  }
  const head = legs.length ? snap(legs[legs.length - 1].ang) : 0;
  const travelled = pts.slice(1).reduce((s, p, i) => s + km(pts[i], p), km(fc, pts[0]));
  const tip = track[track.length - 1];
  const aheadKm = delivered ? 0 : Math.max(1.5, travelled * 0.35);
  const door = delivered ? tip : { x: tip.x + aheadKm * Math.cos(head), y: tip.y + aheadKm * Math.sin(head) };

  // 3. fit into the box
  const all = [...track, door];
  const minX = Math.min(...all.map((p) => p.x));
  const maxX = Math.max(...all.map((p) => p.x));
  const minY = Math.min(...all.map((p) => p.y));
  const maxY = Math.max(...all.map((p) => p.y));
  const side = w < 520 ? 110 : 190; // room for labels on either side
  const padY = 56;
  const spanX = Math.max(maxX - minX, 0.5);
  const spanY = Math.max(maxY - minY, 0.5);
  const scale = Math.min((w - 2 * side) / spanX, 300 / spanY, 160);
  const h = Math.round(spanY * scale + padY * 2);
  const offX = (w - spanX * scale) / 2; // centre the drawing
  const X = (p: Pt) => offX + (p.x - minX) * scale;
  const Y = (p: Pt) => padY + (p.y - minY) * scale;
  const line = track.map((p) => `${X(p)},${Y(p)}`).join(" ");
  const out = scanAt("OUT_FOR_DELIVERY");
  const picked = scanAt("PICKED");
  const loaded = scanAt("LOADED");
  const first = track[Math.min(1, track.length - 1)];
  const doorLeft = door.x < first.x - 0.01 || X(door) > w - side;
  // FC label on the side the first leg leaves free
  const fcLeft = first.x > 0.01;

  return (
    <div ref={box} className="routemap">
      <svg width={w} height={h} role="img" aria-label={`Courier route from ${fc.city} (${fc.id}): ${travelled.toFixed(1)} km travelled${delivered ? ", delivered" : promised ? `, arriving by ${day(promised)}` : ""}.`}>
        {!delivered && (
          <line x1={X(tip)} y1={Y(tip)} x2={X(door)} y2={Y(door)} stroke="var(--unreached)" strokeWidth="2.5" strokeDasharray="2 7" strokeLinecap="round" />
        )}
        <polyline points={line} fill="none" stroke={color} strokeWidth="var(--line)" strokeLinejoin="round" strokeLinecap="round" />
        {out && track.length > 1 && (
          <g transform={`translate(${(X(track[0]) + X(first)) / 2},${(Y(track[0]) + Y(first)) / 2})`}>
            <circle r="5" fill="var(--surface)" stroke="var(--ink)" strokeWidth="2.5" />
          </g>
        )}
        <g transform={`translate(${X(track[0])},${Y(track[0])})`}>
          <circle r="13" fill="var(--surface)" stroke="var(--ink)" strokeWidth="3.5" />
          <circle r="4" fill="var(--ink)" />
          <text x={fcLeft ? -20 : 20} y="-6" textAnchor={fcLeft ? "end" : "start"} className="rm-label">
            {fc.city.toUpperCase()}
          </text>
          <text x={fcLeft ? -20 : 20} y="11" textAnchor={fcLeft ? "end" : "start"} className="rm-sub">
            FC {fc.id}
          </text>
        </g>
        <g transform={`translate(${X(door)},${Y(door)})`}>
          <rect x="-9" y="-9" width="18" height="18" fill={delivered ? "var(--ink)" : "var(--surface)"} stroke={delivered ? "none" : "var(--ink)"} strokeWidth="2.5" />
          <text x={doorLeft ? -18 : 18} y="-3" textAnchor={doorLeft ? "end" : "start"} className="rm-label">
            {delivered ? "DELIVERED" : "YOUR DOOR"}
          </text>
          <text x={doorLeft ? -18 : 18} y="15" textAnchor={doorLeft ? "end" : "start"} className="rm-sub">
            {delivered ? clock(scanAt("DELIVERED")!.recordedAt) : promised ? `by ${day(promised)}, 9 pm` : "distance unknown"}
          </text>
        </g>
        {!delivered && (
          <g className="courier" style={{ transform: `translate(${X(tip)}px, ${Y(tip)}px)` }}>
            <circle r="20" className="courier-ring" fill="none" stroke={color} strokeWidth="2" />
            <circle r="13" fill={color} stroke="var(--surface)" strokeWidth="2.5" />
            <text y="5" textAnchor="middle" className="ld-train" fill={ink}>
              {REGION_LETTER[region]}
            </text>
          </g>
        )}
      </svg>
      <p className="rm-caption num">
        {[picked && `picked ${clock(picked.recordedAt)}`, loaded && `loaded ${clock(loaded.recordedAt)}`].filter(Boolean).join(" · ")}
        {picked || loaded ? ` at ${fc.id} · ` : ""}
        {travelled.toFixed(1)} km travelled · {pts.length} position reports · directions schematic, distances true
      </p>
    </div>
  );
}

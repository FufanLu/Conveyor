import { useLayoutEffect, useRef, useState } from "react";
import type { Region } from "../api/types";
import { REGION_LETTER, REGION_VAR, clock } from "../ui/kit";

export interface Station {
  key: string;
  label: string;
  at: string | null; // when it was reached; null = not yet, or reached at an unknown time
  reached: boolean;
}

/**
 * The order's line. Reached segments are as long as the real time between their stations
 * (with a floor, so a 40 ms step stays readable); stations ahead are evenly spaced and grey.
 * A failure leaves the line at 45° and ends in a vermilion terminus. Wide screens run the line
 * left to right; narrow ones run it top to bottom, like a strip map in a carriage.
 */
export function LineDiagram({
  stations,
  region,
  failure,
  live,
}: {
  stations: Station[];
  region: Region;
  failure: { from: number; label: string; reason: string | null } | null;
  live: boolean;
}) {
  const box = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(960);
  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setW(Math.max(280, e.contentRect.width)));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const vertical = w < 640;
  const color = REGION_VAR[region];
  const lastReached = stations.reduce((k, s, i) => (s.reached ? i : k), 0);
  const shown = failure ? failure.from + 1 : stations.length;
  const ahead = failure ? 0 : stations.length - 1 - lastReached;

  // Positions along the track, in px from its start.
  const length = vertical ? 92 * (stations.length - 1) : w - 112;
  const minSeg = vertical ? 56 : 64;
  const aheadSeg = vertical ? 52 : 96;
  const gaps: number[] = [];
  for (let i = 1; i <= lastReached; i++) {
    const a = stations[i - 1].at;
    const b = stations[i].at;
    gaps.push(a && b ? Math.max(0, (new Date(b).getTime() - new Date(a).getTime()) / 1000) : 0);
  }
  const totalGap = gaps.reduce((s, g) => s + g, 0);
  const t: number[] = [0];
  // Two stations reached at the same instant (CONFIRMED and READY come from one event) stack
  // as one interchange instead of getting a fake gap.
  const same = (i: number) => stations[i].at !== null && stations[i].at === stations[i + 1].at;
  const realSegs = gaps.filter((_, i) => !same(i)).length || 1;
  const flex2 = Math.max(0, length - ahead * aheadSeg - realSegs * minSeg);
  gaps.forEach((g, i) =>
    t.push(same(i) ? t[i] : t[i] + minSeg + flex2 * (totalGap > 0 ? g / totalGap : 1 / realSegs)),
  );
  for (let i = t.length; i < shown; i++) t.push(t[i - 1] + aheadSeg);
  if (lastReached === 0 && !failure) for (let i = 0; i < stations.length; i++) t[i] = (length * i) / (stations.length - 1);

  const origin = vertical ? { x: 76, y: 28 } : { x: 56, y: failure ? 64 : 84 };
  const at = (i: number) => (vertical ? { x: origin.x, y: origin.y + t[i] } : { x: origin.x + t[i], y: origin.y });
  const end = at(failure ? failure.from : stations.length - 1);
  const reachedEnd = at(lastReached);
  const failStart = failure ? at(failure.from) : null;
  // A failure runs on along the line for a short stub, then leaves it at 45°, clear of the
  // station's own label.
  const failLen = vertical ? 56 : 72;
  const stub = vertical ? 44 : 84;
  const failMid = failStart ? (vertical ? { x: failStart.x, y: failStart.y + stub } : { x: failStart.x + stub, y: failStart.y }) : null;
  const failEnd = failMid ? { x: failMid.x + failLen, y: failMid.y + failLen } : null;
  const width = w;
  const height = vertical
    ? (failEnd ? failEnd.y + 52 : end.y + 32)
    : failEnd
      ? failEnd.y + 58
      : origin.y + 96;
  const train = at(lastReached);
  // Stations closer than a label's width alternate their labels above the line.
  const above: boolean[] = [];
  for (let i = 0; i < shown; i++) above.push(!vertical && i > 0 && t[i] - t[i - 1] < 132 && !above[i - 1]);
  // vertical: a stacked pair shares one row, so push the second label down a line
  const stackedBelow = (i: number) => vertical && i > 0 && t[i] === t[i - 1];

  return (
    <div ref={box} className="linediagram">
      <svg width={width} height={height} role="img" aria-label={describe(stations, failure)}>
        {!failure && lastReached < stations.length - 1 && (
          <line x1={reachedEnd.x} y1={reachedEnd.y} x2={end.x} y2={end.y} stroke="var(--unreached)" strokeWidth="2" strokeDasharray="2 6" strokeLinecap="round" />
        )}
        {lastReached > 0 && (
          <line className="travelled" x1={origin.x} y1={origin.y} x2={reachedEnd.x} y2={reachedEnd.y} stroke={color} strokeWidth="var(--line)" strokeLinecap="round" />
        )}
        {failure && failStart && failEnd && (
          <>
            <polyline points={`${failStart.x},${failStart.y} ${failMid!.x},${failMid!.y} ${failEnd.x},${failEnd.y}`} strokeLinejoin="round" fill="none" stroke="var(--fail)" strokeWidth="var(--line)" strokeLinecap="round" />
            <rect x={failEnd.x - 9} y={failEnd.y - 9} width="18" height="18" fill="var(--fail)" />
            <text x={failEnd.x + 18} y={failEnd.y + 6} className="ld-label" fill="var(--fail)">
              {failure.label.toUpperCase()}
            </text>
            {failure.reason && (
              <text x={failEnd.x + 18} y={failEnd.y + 26} className="ld-time">
                {failure.reason}
              </text>
            )}
          </>
        )}
        {gaps.map((g, i) => {
          if (!stations[i].at || !stations[i + 1].at || stations[i].at === stations[i + 1].at) return null;
          if ((above[i + 1] || above[i]) && Math.abs(t[i + 1] - t[i]) < 170) return null;
          const a = at(i);
          const b = at(i + 1);
          return vertical ? (
            <text key={i} x={a.x - 18} y={(a.y + b.y) / 2 + 4} textAnchor="end" className="ld-gap">
              {fmtGap(g)}
            </text>
          ) : (
            <text key={i} x={(a.x + b.x) / 2} y={a.y - 18} textAnchor="middle" className="ld-gap">
              {fmtGap(g)}
            </text>
          );
        })}
        {stations.slice(0, shown).map((s, i) => {
          const p = at(i);
          const when = s.reached ? (s.at ? clock(s.at) : "reached") : "ahead";
          return (
            <g key={s.key} transform={`translate(${p.x},${p.y})`}>
              <circle r={s.reached ? 9 : 6} fill="var(--surface)" stroke={s.reached ? "var(--ink)" : "var(--unreached)"} strokeWidth={s.reached ? 3 : 2} />
              {vertical ? (
                <>
                  <text x="22" y={stackedBelow(i) ? 38 : 1} className="ld-label" fill={s.reached ? "var(--ink)" : "var(--ink-3)"}>
                    {s.label.toUpperCase()}
                  </text>
                  <text x="22" y={stackedBelow(i) ? 56 : 19} className="ld-time">
                    {when}
                  </text>
                </>
              ) : (
                <>
                  <text y={above[i] ? -40 : 30} textAnchor="middle" className="ld-label" fill={s.reached ? "var(--ink)" : "var(--ink-3)"}>
                    {s.label.toUpperCase()}
                  </text>
                  <text y={above[i] ? -22 : 50} textAnchor="middle" className="ld-time">
                    {when}
                  </text>
                </>
              )}
            </g>
          );
        })}
        {!failure && (
          <g className="train" style={{ transform: vertical ? `translate(${train.x - 0}px, ${train.y}px)` : `translate(${train.x}px, ${train.y + (above[lastReached] ? 34 : -34)}px)` }}>
            {vertical ? null : above[lastReached] ? <line y1="-12" y2="-25" stroke="var(--ink)" strokeWidth="2" /> : <line y1="12" y2="25" stroke="var(--ink)" strokeWidth="2" />}
            {live && <circle r="15" className="train-ring" fill="none" stroke={color} strokeWidth="2" />}
            <circle r="12" fill={color} stroke="var(--surface)" strokeWidth={vertical ? 3 : 0} />
            <text y="5.5" textAnchor="middle" className="ld-train" fill={region === "us-south" ? "var(--ink)" : "#fff"}>
              {REGION_LETTER[region]}
            </text>
          </g>
        )}
      </svg>
    </div>
  );
}

function fmtGap(s: number) {
  if (s < 1) return `+${Math.round(s * 1000)} ms`;
  if (s < 90) return `+${s.toFixed(1)} s`;
  if (s < 5400) return `+${Math.round(s / 60)} min`;
  return `+${(s / 3600).toFixed(1)} h`;
}

function describe(stations: Station[], failure: { label: string; reason: string | null } | null) {
  const reached = stations.filter((s) => s.reached).map((s) => s.label);
  if (failure) return `Order line: ${reached.join(", ")}, then ${failure.label}${failure.reason ? `: ${failure.reason}` : ""}.`;
  const next = stations.find((s) => !s.reached);
  return `Order line: reached ${reached.join(", ")}${next ? `; next stop ${next.label}` : ""}.`;
}

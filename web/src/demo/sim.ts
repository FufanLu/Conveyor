import { FCS } from "../api/geo";
import {
  Problem,
  type Backend,
  type Dot,
  type OpsOrder,
  type Order,
  type Region,
  type Report,
  type Shipment,
  type StreamState,
} from "../api/types";
import { bySku, search } from "./catalog";

// An in-browser stand-in for the backend, for reviewing the UI without the full stack. It
// follows the real rules: totals ending in .13 are declined, .37 wait for a payment callback,
// out-of-stock lines are rejected; confirmed orders get a courier who scans and drives.

const SPEED = 1; // seconds of simulated time per real second
const REGION_FC: Record<Region, (typeof FCS)[number]> = {
  "us-east": FCS[0],
  "us-central": FCS[2],
  "us-south": FCS[3],
  "us-west": FCS[4],
};

interface Sim {
  order: Order;
  shipment: Shipment;
  reports: Report[];
  listeners: Set<(r: Report) => void>;
  path: { lat: number; lon: number }[];
}

const sims = new Map<string, Sim>();
const byKey = new Map<string, string>();
let seq = 0;

function orderId(): string {
  // Same look as order-svc's ids: Crockford base32 of time | shard | worker | sequence.
  const n = (BigInt(Date.now() - 1_700_000_000_000) << 22n) | (BigInt(seq++ % 16) << 18n) | BigInt(seq % 1024);
  return n.toString(32).toUpperCase().padStart(13, "0").replace(/I/g, "J").replace(/O/g, "P");
}

/** An L-shaped street route of `km` from the FC, bending once, as couriers drive grids. */
function route(fc: { lat: number; lon: number }, seedN: number): { lat: number; lon: number }[] {
  const rnd = (k: number) => ((Math.sin(seedN * 9301 + k * 49297) * 233280) % 1 + 1) % 1;
  const kmN = 4 + rnd(1) * 6;
  const kmE = (rnd(2) > 0.5 ? 1 : -1) * (3 + rnd(3) * 6);
  const north = rnd(4) > 0.5 ? 1 : -1;
  const dLat = (north * kmN) / 111;
  const dLon = kmE / (111 * Math.cos((fc.lat * Math.PI) / 180));
  const corner = { lat: fc.lat + dLat * 0.65, lon: fc.lon + dLon * 0.15 };
  const door = { lat: fc.lat + dLat, lon: fc.lon + dLon };
  const pts: { lat: number; lon: number }[] = [];
  const legs = [[fc, corner], [corner, door]] as const;
  for (const [a, b] of legs) for (let i = 0; i < 24; i++) pts.push({ lat: a.lat + ((b.lat - a.lat) * i) / 24, lon: a.lon + ((b.lon - a.lon) * i) / 24 });
  pts.push(door);
  return pts;
}

function emit(sim: Sim, r: Omit<Report, "seq" | "recordedAt" | "lat" | "lon" | "speed" | "scanType" | "location"> & Partial<Report>) {
  const report: Report = {
    seq: sim.reports.length + 1,
    recordedAt: new Date().toISOString(),
    lat: null,
    lon: null,
    speed: null,
    scanType: null,
    location: null,
    ...r,
  };
  sim.reports.push(report);
  if (report.kind === "position") {
    sim.shipment = { ...sim.shipment, lat: report.lat, lon: report.lon, positionAt: report.recordedAt, lastSeq: report.seq };
  } else if (report.scanType) {
    sim.shipment = { ...sim.shipment, status: report.scanType, lastSeq: report.seq };
  }
  sim.listeners.forEach((l) => l(report));
}

const at = (seconds: number, f: () => void) => setTimeout(f, (seconds * 1000) / SPEED);

function drive(sim: Sim) {
  const fc = REGION_FC[sim.order.shipToRegion];
  at(2, () => emit(sim, { kind: "scan", scanType: "PICKED", location: fc.id }));
  at(4, () => emit(sim, { kind: "scan", scanType: "LOADED", location: fc.id }));
  at(6, () => emit(sim, { kind: "scan", scanType: "OUT_FOR_DELIVERY" }));
  sim.path.forEach((p, i) => at(7 + i * 1.2, () => emit(sim, { kind: "position", lat: p.lat, lon: p.lon, speed: 11 })));
  at(8 + sim.path.length * 1.2, () => emit(sim, { kind: "scan", scanType: "DELIVERED" }));
}

function promise(placed: Date): string {
  const d = new Date(Date.UTC(placed.getUTCFullYear(), placed.getUTCMonth(), placed.getUTCDate() + 2, 21));
  return d.toISOString();
}

function settle(sim: Sim, status: Order["status"], reason: string | null) {
  const confirmed = status === "CONFIRMED";
  sim.order = {
    ...sim.order,
    status,
    version: 2,
    statusReason: reason,
    promisedDelivery: confirmed ? promise(new Date(sim.order.placedAt)) : null,
  };
  sim.shipment = { ...sim.shipment, status: confirmed ? "READY" : "CANCELLED", promisedDelivery: sim.order.promisedDelivery };
  if (confirmed) drive(sim);
}

// ---------------------------------------------------------------- ops: a synthetic fleet

interface Courier {
  id: string;
  lat: number;
  lon: number;
  dLat: number;
  dLon: number;
  seq: number;
}
const fleet: Courier[] = FCS.flatMap((fc, f) =>
  Array.from({ length: 48 }, (_, k) => {
    const a = (k / 48) * Math.PI * 2 + f;
    const r = 0.02 + ((k * 37) % 11) / 90;
    return {
      id: `SIM-${fc.id}-${k}`,
      lat: fc.lat + Math.sin(a) * r,
      lon: fc.lon + Math.cos(a) * r * 1.3,
      dLat: Math.sin(a) * 0.0009,
      dLon: Math.cos(a) * 0.0012,
      seq: 1,
    };
  }),
);

const history: OpsOrder[] = Array.from({ length: 64 }, (_, k) => {
  const statuses: OpsOrder["status"][] = ["CONFIRMED", "CONFIRMED", "CONFIRMED", "CONFIRMED", "CONFIRMED", "PAYMENT_FAILED", "REJECTED", "CANCELLED", "PLACED"];
  const cents = 199 + ((k * 7919) % 19801);
  return {
    orderId: (BigInt(3_400_000_000_000_000) + BigInt(k * 977_331)).toString(32).toUpperCase().padStart(13, "0"),
    userId: k % 3 ? "6ba64ca1-f001-4f34-b72f-5dd3880ec474" : "2f6d0c1e-7a44-4c55-9e1a-0b3f5d1c8e90",
    status: statuses[k % statuses.length],
    totalCents: cents,
    lines: 1 + (k % 3),
    placedAt: new Date(Date.now() - (k + 1) * 4.7 * 60_000).toISOString(),
    version: k % 9 === 8 ? 1 : 2,
  };
});

function streamState(onState: (s: StreamState) => void) {
  onState("connecting");
  setTimeout(() => onState("live"), 250);
}

export const demo: Backend = {
  kind: "demo",
  search: async ({ text, category, page, size }) => {
    await new Promise((r) => setTimeout(r, 120));
    return search(text, category, page, size);
  },
  product: async (sku) => bySku(sku) ?? null,
  deals: async () => [dealNow()],
  joinDeal: async () => {
    await new Promise((r) => setTimeout(r, 180));
    const d = dealNow();
    const ticket = `demo.${Date.now()}.${d.joined + 1}.${d.joined + 1 - d.admitted}`;
    return placeOf(ticket);
  },
  pollDeal: async (_id, ticket) => {
    await new Promise((r) => setTimeout(r, 90));
    return placeOf(ticket);
  },
  placeOrder: async (items, region, key) => {
    await new Promise((r) => setTimeout(r, 260));
    const known = byKey.get(key);
    if (known) return sims.get(known)!.order;
    const lines = items.map(({ sku, quantity }) => {
      const p = bySku(sku);
      if (!p) throw new Problem(422, `Unknown SKU ${sku}.`);
      return { sku, quantity, unitPriceCents: p.priceCents, lineTotalCents: p.priceCents * quantity };
    });
    const total = lines.reduce((s, l) => s + l.lineTotalCents, 0);
    const id = orderId();
    const order: Order = {
      orderId: id,
      status: "PLACED",
      lines,
      totalCents: total,
      currency: "USD",
      shipToRegion: region,
      version: 1,
      placedAt: new Date().toISOString(),
      statusReason: null,
      promisedDelivery: null,
    };
    const sim: Sim = {
      order,
      shipment: { shipmentId: id, status: "ORDERED", region, promisedDelivery: null, lat: null, lon: null, positionAt: null, lastSeq: 0 },
      reports: [],
      listeners: new Set(),
      path: route(REGION_FC[region], total),
    };
    sims.set(id, sim);
    byKey.set(key, id);
    const cents = total % 100;
    const soldOut = items.some(({ sku }) => parseInt(sku.slice(-2), 16) % 17 === 0);
    if (soldOut) at(1.1, () => settle(sim, "REJECTED", `out of stock: [${items.map((i) => `${i.sku}x${i.quantity}`).join(", ")}]`));
    else if (cents === 13) at(2.2, () => settle(sim, "PAYMENT_FAILED", "card declined"));
    else if (cents === 37) at(9, () => settle(sim, "CONFIRMED", null));
    else at(1.8, () => settle(sim, "CONFIRMED", null));
    return order;
  },
  order: async (id) => {
    const sim = sims.get(id);
    if (!sim) throw new Problem(404, `No order ${id}.`);
    return sim.order;
  },
  myOrders: async () => [...sims.values()].map((s) => s.order).reverse(),
  shipment: async (id) => sims.get(id)?.shipment ?? null,
  shipmentEvents: (id, onReport, onState) => {
    const sim = sims.get(id);
    streamState(onState);
    if (!sim) return () => onState("closed");
    sim.reports.forEach(onReport);
    sim.listeners.add(onReport);
    return () => {
      sim.listeners.delete(onReport);
      onState("closed");
    };
  },
  opsMap: (onFrame, onState) => {
    streamState(onState);
    const t = setInterval(() => {
      const dots: Dot[] = fleet.map((c) => {
        c.lat += c.dLat;
        c.lon += c.dLon;
        c.seq += 1;
        if (Math.abs(c.lat) > 90 || c.seq % 140 === 0) {
          c.dLat = -c.dLat;
          c.dLon = -c.dLon;
        }
        return { shipmentId: c.id, lat: c.lat, lon: c.lon, seq: c.seq };
      });
      for (const s of sims.values()) {
        if (s.shipment.lat !== null && s.shipment.lon !== null && s.shipment.status !== "DELIVERED") {
          dots.push({ shipmentId: s.order.orderId, lat: s.shipment.lat, lon: s.shipment.lon, seq: s.shipment.lastSeq });
        }
      }
      onFrame(dots);
    }, 500);
    return () => {
      clearInterval(t);
      onState("closed");
    };
  },
  opsOrders: async ({ status, sku, size }) => {
    await new Promise((r) => setTimeout(r, 90));
    const mine: OpsOrder[] = [...sims.values()].map(({ order: o }) => ({
      orderId: o.orderId,
      userId: "6ba64ca1-f001-4f34-b72f-5dd3880ec474",
      status: o.status,
      totalCents: o.totalCents,
      lines: o.lines.length,
      placedAt: o.placedAt,
      version: o.version,
    }));
    const all = [...mine.reverse(), ...history].filter(
      (o) => (!status || o.status === status) && (!sku || [...sims.values()].some((s) => s.order.orderId === o.orderId && s.order.lines.some((l) => l.sku === sku))),
    );
    return { total: all.length, orders: all.slice(0, size ?? 50) };
  },
};

// ------------------------------------------------------------------ the deal's line, simulated

// The demo line repeats every 400 s so a visitor always finds it mid-sale: people join at 9/s
// and the gateway admits 12/s, so whoever joins now waits about 25 to 40 s.
const DEAL = { id: "lightning-1", sku: "B09E3779B1", units: 6000 };
const JOIN_RATE = 9;
const ADMIT_RATE = 12;

function dealNow() {
  const t = (Date.now() / 1000) % 400;
  const joined = Math.floor(1800 + JOIN_RATE * t);
  const admitted = Math.floor(joined - 300 - t / 2);
  return { ...DEAL, joined, admitted, admissionsPerSecond: ADMIT_RATE };
}

function placeOf(ticket: string) {
  const [, at, place, ahead0] = ticket.split(".").map(Number);
  const ahead = Math.max(0, Math.ceil(ahead0 - ((Date.now() - at) / 1000) * ADMIT_RATE));
  const base = { dealId: DEAL.id, sku: DEAL.sku, ticket, place, etaSeconds: Math.ceil(ahead / ADMIT_RATE) };
  if (ahead > 0) {
    return { ...base, state: "WAITING" as const, ahead, pollAfterMs: Math.min(15_000, Math.max(1000, (ahead / ADMIT_RATE) * 500)) };
  }
  const expires = at + (ahead0 / ADMIT_RATE) * 1000 + 600_000;
  return { ...base, state: "ADMITTED" as const, ahead: 0, admission: `demo-admission.${place}`, admissionExpiresAt: new Date(expires).toISOString() };
}

import type { OrderStatus, Region, ShipmentStatus, StreamState } from "../api/types";

export const REGION_LETTER: Record<Region, string> = {
  "us-east": "E",
  "us-central": "C",
  "us-south": "S",
  "us-west": "W",
};
export const REGION_NAME: Record<Region, string> = {
  "us-east": "East",
  "us-central": "Central",
  "us-south": "South",
  "us-west": "West",
};
export const REGION_VAR: Record<Region, string> = {
  "us-east": "var(--east)",
  "us-central": "var(--central)",
  "us-south": "var(--south)",
  "us-west": "var(--west)",
};

export function Bullet({ region, size }: { region: Region; size?: "sm" | "lg" }) {
  return (
    <span className={`bullet${size ? ` ${size}` : ""}`} data-region={region} aria-label={`${REGION_NAME[region]} line`} role="img">
      {REGION_LETTER[region]}
    </span>
  );
}

const STATUS_TEXT: Record<OrderStatus | ShipmentStatus, string> = {
  PLACED: "Placed",
  CONFIRMED: "Confirmed",
  REJECTED: "Out of stock",
  PAYMENT_FAILED: "Payment failed",
  CANCELLED: "Cancelled",
  ORDERED: "Ordered",
  READY: "Ready at FC",
  PICKED: "Picked",
  LOADED: "Loaded",
  OUT_FOR_DELIVERY: "Out for delivery",
  DELIVERED: "Delivered",
  DELIVERY_FAILED: "Delivery failed",
};
const FAILED = new Set(["REJECTED", "PAYMENT_FAILED", "CANCELLED", "DELIVERY_FAILED"]);

export function statusText(s: OrderStatus | ShipmentStatus) {
  return STATUS_TEXT[s];
}
export function isFailure(s: string) {
  return FAILED.has(s);
}

export function Plate({ status }: { status: OrderStatus | ShipmentStatus }) {
  const tone = FAILED.has(status) ? "fail" : status === "PLACED" || status === "ORDERED" ? "open" : undefined;
  return (
    <span className="plate" data-tone={tone}>
      {STATUS_TEXT[status]}
    </span>
  );
}

const STATE_TEXT: Record<StreamState, string> = {
  connecting: "Connecting",
  live: "Live",
  reconnecting: "Reconnecting",
  closed: "Closed",
};
export function LiveState({ state }: { state: StreamState }) {
  return (
    <span className="live" data-state={state} role="status" aria-live="polite">
      {STATE_TEXT[state]}
    </span>
  );
}

/** Ids (orders, SKUs, shipments) are set in the sign face with tabular figures, everywhere. */
export function Id({ children }: { children: string }) {
  return <span className="id">{children}</span>;
}

export const money = (cents: number, currency = "USD") =>
  new Intl.NumberFormat("en-US", { style: "currency", currency }).format(cents / 100);

export const clock = (iso: string) =>
  new Date(iso).toLocaleTimeString("en-US", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });

export const day = (iso: string) =>
  new Date(iso).toLocaleDateString("en-US", { weekday: "short", month: "short", day: "numeric" });

export function ago(iso: string, now = Date.now()) {
  const s = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000));
  if (s < 60) return `${s} s ago`;
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  return `${Math.floor(s / 3600)} h ago`;
}

/** The wordmark's glyph: a line through two stations with a 45° bend. */
export function LineGlyph({ color = "var(--east)" }: { color?: string }) {
  return (
    <svg width="30" height="24" viewBox="0 0 30 24" aria-hidden="true">
      <path d="M2 18 H10 L18 6 H28" fill="none" stroke={color} strokeWidth="5" strokeLinejoin="round" />
      <circle cx="10" cy="18" r="4.5" fill="#fff" stroke="#fff" strokeWidth="0" />
      <circle cx="10" cy="18" r="2.5" fill="#141414" />
      <circle cx="18" cy="6" r="4.5" fill="#fff" />
      <circle cx="18" cy="6" r="2.5" fill="#141414" />
    </svg>
  );
}

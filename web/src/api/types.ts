// The shapes the services return through the gateway. Names match the backend records.

export type Region = "us-east" | "us-central" | "us-south" | "us-west";
export const REGIONS: Region[] = ["us-east", "us-central", "us-south", "us-west"];

export interface Hit {
  sku: string;
  title: string;
  brand: string;
  category: string;
  priceCents: number;
  rating: number;
}

export interface SearchResult {
  total: number;
  tookMillis: number;
  hits: Hit[];
  facets: Record<string, { value: string; count: number }[]>;
}

/** Saga outcomes; PLACED until the saga decides. */
export type OrderStatus = "PLACED" | "CONFIRMED" | "REJECTED" | "PAYMENT_FAILED" | "CANCELLED";

export interface Order {
  orderId: string;
  status: OrderStatus;
  lines: { sku: string; quantity: number; unitPriceCents: number; lineTotalCents: number }[];
  totalCents: number;
  currency: string;
  shipToRegion: Region;
  version: number;
  placedAt: string;
  statusReason: string | null;
  promisedDelivery: string | null;
}

export type ShipmentStatus =
  | "ORDERED"
  | "READY"
  | "CANCELLED"
  | "PICKED"
  | "LOADED"
  | "OUT_FOR_DELIVERY"
  | "DELIVERED"
  | "DELIVERY_FAILED";

export interface Shipment {
  shipmentId: string;
  status: ShipmentStatus;
  region: Region;
  promisedDelivery: string | null;
  lat: number | null;
  lon: number | null;
  positionAt: string | null;
  lastSeq: number;
}

export interface Report {
  seq: number;
  kind: "position" | "scan";
  recordedAt: string;
  lat: number | null;
  lon: number | null;
  speed: number | null;
  scanType: Exclude<ShipmentStatus, "ORDERED" | "READY" | "CANCELLED"> | null;
  location: string | null;
}

export interface Dot {
  shipmentId: string;
  lat: number;
  lon: number;
  seq: number;
}

export interface OpsOrder {
  orderId: string;
  userId: string;
  status: OrderStatus;
  totalCents: number;
  lines: number;
  placedAt: string;
  version: number;
}

/** RFC 9457 problem, as every Conveyor service answers errors. */
export class Problem extends Error {
  constructor(
    readonly status: number,
    readonly detail: string,
    readonly extra: Record<string, unknown> = {},
  ) {
    super(detail);
  }
}

/** A deal sold through the gateway's waiting room (GET /api/v1/deals). */
export interface Deal {
  id: string;
  sku: string;
  units: number;
  joined: number;
  admitted: number;
  admissionsPerSecond: number;
}

export type LineState = "WAITING" | "ADMITTED" | "SOLD_OUT";

/** A place in a deal's line: the gateway's answer to joining and to every poll. */
export interface Place {
  dealId: string;
  sku: string;
  ticket: string;
  state: LineState;
  place: number;
  ahead: number;
  etaSeconds: number;
  pollAfterMs?: number;
  /** Once admitted: what order-svc wants in Conveyor-Admission, for this customer only. */
  admission?: string;
  admissionExpiresAt?: string;
}

export type StreamState = "connecting" | "live" | "reconnecting" | "closed";

/** Everything the screens need. Implemented by the gateway client and by the demo simulation. */
export interface Backend {
  readonly kind: "gateway" | "demo";
  search(q: { text?: string; category?: string; page?: number; size?: number }): Promise<SearchResult>;
  placeOrder(
    items: { sku: string; quantity: number }[],
    region: Region,
    idempotencyKey: string,
    admission?: string,
  ): Promise<Order>;
  product(sku: string): Promise<Hit | null>;
  deals(): Promise<Deal[]>;
  joinDeal(dealId: string): Promise<Place>;
  pollDeal(dealId: string, ticket: string): Promise<Place>;
  order(orderId: string): Promise<Order>;
  myOrders(): Promise<Order[]>;
  shipment(shipmentId: string): Promise<Shipment | null>;
  /** History then live. Returns a closer. */
  shipmentEvents(shipmentId: string, onReport: (r: Report) => void, onState: (s: StreamState) => void): () => void;
  opsMap(onFrame: (dots: Dot[]) => void, onState: (s: StreamState) => void): () => void;
  opsOrders(q: { status?: string; sku?: string; size?: number }): Promise<{ total: number; orders: OpsOrder[] }>;
}

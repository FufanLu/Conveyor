import { bearer } from "./auth";
import { openStream } from "./sse";
import { Problem, type Backend, type Dot, type Order, type Report } from "./types";

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(`/api${path}`, {
    ...init,
    headers: { Accept: "application/json", ...(await bearer()), ...(init.headers ?? {}) },
  });
  if (!res.ok) {
    let detail = `The service answered ${res.status}.`;
    let extra: Record<string, unknown> = {};
    try {
      const body = await res.json();
      detail = body.detail ?? detail;
      extra = body;
    } catch {
      /* not problem+json */
    }
    throw new Problem(res.status, detail, extra);
  }
  return (await res.json()) as T;
}

function query(params: Record<string, string | number | undefined>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== "") q.set(k, String(v));
  const s = q.toString();
  return s ? `?${s}` : "";
}

/** The real thing: every call through the gateway, tokens from Keycloak. */
export const gateway: Backend = {
  kind: "gateway",
  search: ({ text, category, page, size }) =>
    call(`/v1/search${query({ q: text, category, page, size: size ?? 20 })}`),
  placeOrder: (items, region, key) =>
    call<Order>("/v1/orders", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Idempotency-Key": key },
      body: JSON.stringify({ items, shipToRegion: region }),
    }),
  order: (id) => call<Order>(`/v1/orders/${encodeURIComponent(id)}`),
  myOrders: async () => (await call<{ orders: Order[] }>("/v1/orders?limit=20")).orders,
  shipment: async (id) => {
    try {
      return await call(`/v1/shipments/${encodeURIComponent(id)}`);
    } catch (e) {
      if (e instanceof Problem && e.status === 404) return null;
      throw e;
    }
  },
  shipmentEvents: (id, onReport, onState) =>
    openStream(
      `/api/v1/shipments/${encodeURIComponent(id)}/events`,
      bearer,
      (e) => onReport(JSON.parse(e.data) as Report),
      onState,
    ),
  opsMap: (onFrame, onState) =>
    openStream("/api/v1/ops/map", bearer, (e) => onFrame(JSON.parse(e.data) as Dot[]), onState),
  opsOrders: ({ status, sku, size }) => call(`/v1/ops/orders${query({ status, sku, size: size ?? 50 })}`),
};

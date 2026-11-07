import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { FCS, regionAt } from "../api/geo";
import { Problem, REGIONS, type Dot, type OpsOrder, type OrderStatus, type StreamState } from "../api/types";
import { MetroInset, NetworkMap, groupByFc } from "../map/NetworkMap";
import { isOps, useSession } from "../session";
import { Bullet, LiveState, Plate, REGION_NAME, ago, money } from "../ui/kit";
import "./ops.css";

const STATUSES: (OrderStatus | "")[] = ["", "PLACED", "CONFIRMED", "PAYMENT_FAILED", "REJECTED", "CANCELLED"];
const STATUS_LABEL: Record<string, string> = {
  "": "All",
  PLACED: "Placed",
  CONFIRMED: "Confirmed",
  PAYMENT_FAILED: "Payment failed",
  REJECTED: "Out of stock",
  CANCELLED: "Cancelled",
};

export default function OpsPage() {
  const { who, signIn } = useSession();
  if (!who) {
    return (
      <div className="wrap empty">
        <h3>The network view is for operators.</h3>
        <p>Sign in as a user with the ops role.</p>
        <button className="btn" onClick={() => signIn("ops")}>
          Sign in
        </button>
      </div>
    );
  }
  if (!isOps(who)) {
    return (
      <div className="wrap empty">
        <h3>The network view is for operators.</h3>
        <p>
          You are signed in as {who.name}, without the ops role. The live map and order search answer 403 to
          customers.
        </p>
      </div>
    );
  }
  return <Ops />;
}

function Ops() {
  const { backend } = useSession();
  const [dots, setDots] = useState<Dot[]>([]);
  const [state, setState] = useState<StreamState>("connecting");
  const frames = useRef<number[]>([]);
  const [fps, setFps] = useState(0);
  const latest = useRef(new Map<string, Dot>());

  useEffect(() => {
    // Frames hold only shipments that moved in the last interval; keep the last known
    // position of each for 30 s so a quiet courier does not blink off the map.
    const seenAt = new Map<string, number>();
    return backend.opsMap((frame) => {
      const now = Date.now();
      frames.current = [...frames.current.filter((t) => now - t < 5000), now];
      for (const d of frame) {
        latest.current.set(d.shipmentId, d);
        seenAt.set(d.shipmentId, now);
      }
      for (const [id, t] of seenAt) if (now - t > 30_000) (seenAt.delete(id), latest.current.delete(id));
      setDots([...latest.current.values()]);
      setFps(frames.current.length / 5);
    }, setState);
  }, [backend]);

  const groups = useMemo(() => groupByFc(dots), [dots]);
  const byRegion = useMemo(() => {
    const m = new Map(REGIONS.map((r) => [r, 0]));
    for (const d of dots) m.set(regionAt(d.lat, d.lon), (m.get(regionAt(d.lat, d.lon)) ?? 0) + 1);
    return m;
  }, [dots]);

  return (
    <>
      <section className="band ops-band">
        <div className="wrap ops-head">
          <h1>Network</h1>
          <p className="ops-legend num">
            <span>
              <strong>{dots.length.toLocaleString("en-US")}</strong> shipments moving
            </span>
            {REGIONS.map((r) => (
              <span key={r} className="legend-region">
                <Bullet region={r} size="sm" />
                {REGION_NAME[r]} <strong>{(byRegion.get(r) ?? 0).toLocaleString("en-US")}</strong>
              </span>
            ))}
            <span>
              <LiveState state={state} /> {fps.toFixed(1)} frames/s
            </span>
          </p>
        </div>
      </section>
      <div className="wrap ops-grid">
        <section aria-label="Live map" className="ops-map">
          <NetworkMap groups={groups} />
          {state === "live" && dots.length === 0 && (
            <p className="ops-quiet muted">No courier has reported in the last 30 seconds. Dots appear as positions arrive.</p>
          )}
          <div className="insets">
            {FCS.map((fc) => (
              <MetroInset key={fc.id} fc={fc} dots={groups.get(fc.id) ?? []} />
            ))}
          </div>
        </section>
        <OrderSearch />
      </div>
    </>
  );
}

function OrderSearch() {
  const { backend } = useSession();
  const [status, setStatus] = useState<OrderStatus | "">("");
  const [sku, setSku] = useState("");
  const [skuApplied, setSkuApplied] = useState("");
  const [result, setResult] = useState<{ total: number; orders: OpsOrder[] } | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    const load = () =>
      backend
        .opsOrders({ status: status || undefined, sku: skuApplied || undefined, size: 20 })
        .then((r) => live && (setResult(r), setError(null)))
        .catch((e) => live && setError(e instanceof Problem ? e.detail : "Order search did not answer."));
    void load();
    const t = setInterval(load, 5000);
    return () => {
      live = false;
      clearInterval(t);
    };
  }, [backend, status, skuApplied]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setSkuApplied(sku.trim().toUpperCase());
  };

  return (
    <section aria-labelledby="search-h" className="ops-orders">
      <div className="section-head">
        <h2 id="search-h">Orders</h2>
        <span className="muted num">{result ? `${result.total.toLocaleString("en-US")} match` : "…"}</span>
      </div>
      <div className="ops-filters">
        <div className="seg" role="group" aria-label="Status">
          {STATUSES.map((s) => (
            <button key={s || "all"} aria-pressed={status === s} onClick={() => setStatus(s)}>
              {STATUS_LABEL[s]}
            </button>
          ))}
        </div>
        <form onSubmit={submit} className="ops-sku">
          <label className="visually-hidden" htmlFor="sku">
            SKU
          </label>
          <input id="sku" className="input mono" placeholder="SKU, e.g. B0DEE0D8F5" value={sku} onChange={(e) => setSku(e.target.value)} />
          <button className="btn ghost">Filter</button>
        </form>
      </div>
      {error && (
        <div className="error" role="alert">
          <strong>Order search is unavailable.</strong>
          {error}
        </div>
      )}
      <div className="ops-table-wrap">
      <table className="ops-table">
        <thead>
          <tr>
            <th>Order</th>
            <th>Status</th>
            <th className="r">Total</th>
            <th className="r">Lines</th>
            <th className="r">Placed</th>
          </tr>
        </thead>
        <tbody>
          {result?.orders.length === 0 && (
            <tr>
              <td colSpan={5} className="muted">
                No order matches these filters.
              </td>
            </tr>
          )}
          {result?.orders.map((o) => (
            <tr key={o.orderId}>
              <td className="id">{o.orderId}</td>
              <td>
                <Plate status={o.status} />
              </td>
              <td className="r num">{money(o.totalCents)}</td>
              <td className="r num">{o.lines}</td>
              <td className="r num muted">{ago(o.placedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      </div>
    </section>
  );
}

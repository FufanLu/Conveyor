import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router";
import { Problem, type Order, type Report, type Shipment, type StreamState } from "../api/types";
import { LineDiagram, type Station } from "../map/LineDiagram";
import { RouteMap } from "../map/RouteMap";
import { useSession } from "../session";
import { Bullet, LiveState, REGION_NAME, ago, clock, day, isFailure, money, statusText } from "../ui/kit";
import "./order.css";

const SCANS = ["PICKED", "LOADED", "OUT_FOR_DELIVERY", "DELIVERED"] as const;

/** Polls the order until the saga decides, then follows the shipment's stream. */
function useOrderLive(orderId: string) {
  const { backend, who } = useSession();
  const [order, setOrder] = useState<Order | null>(null);
  const [error, setError] = useState<Problem | null>(null);
  const [confirmedAt, setConfirmedAt] = useState<string | null>(() => sessionStorage.getItem(`confirmed:${orderId}`));
  const [shipment, setShipment] = useState<Shipment | null>(null);
  const [reports, setReports] = useState<Report[]>([]);
  const [stream, setStream] = useState<StreamState>("closed");

  useEffect(() => {
    if (!who) return;
    let live = true;
    let timer: ReturnType<typeof setTimeout>;
    const tick = async () => {
      try {
        const o = await backend.order(orderId);
        if (!live) return;
        setOrder(o);
        setError(null);
        if (o.status === "PLACED") timer = setTimeout(tick, 1200);
        else if (o.status === "CONFIRMED" && !sessionStorage.getItem(`confirmed:${orderId}`)) {
          // order-svc does not expose when the saga confirmed; record when this page saw it.
          const seen = new Date().toISOString();
          sessionStorage.setItem(`confirmed:${orderId}`, seen);
          setConfirmedAt(seen);
        }
      } catch (e) {
        if (!live) return;
        setError(e instanceof Problem ? e : new Problem(0, "The order service did not answer."));
        timer = setTimeout(tick, 3000);
      }
    };
    void tick();
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [backend, orderId, who]);

  const confirmed = order?.status === "CONFIRMED";
  useEffect(() => {
    if (!confirmed) return;
    let live = true;
    let close: (() => void) | null = null;
    let retry: ReturnType<typeof setTimeout>;
    const seen = new Set<number>();
    const open = async () => {
      // tracking-svc creates the shipment from the order events; it can lag the order by a moment.
      const s = await backend.shipment(orderId).catch(() => null);
      if (!live) return;
      if (!s) {
        retry = setTimeout(open, 1500);
        return;
      }
      setShipment(s);
      close = backend.shipmentEvents(
        orderId,
        (r) => {
          if (seen.has(r.seq)) return;
          seen.add(r.seq);
          setReports((rs) => [...rs, r].sort((a, b) => a.seq - b.seq));
          if (r.kind === "scan" && r.scanType) setShipment((sh) => (sh ? { ...sh, status: r.scanType! } : sh));
        },
        setStream,
      );
    };
    void open();
    return () => {
      live = false;
      clearTimeout(retry);
      close?.();
    };
  }, [backend, orderId, confirmed]);

  return { order, error, confirmedAt, shipment, reports, stream, signedIn: !!who };
}

export default function OrderPage() {
  const { orderId = "" } = useParams();
  const { signIn } = useSession();
  const { order, error, confirmedAt, shipment, reports, stream, signedIn } = useOrderLive(orderId);
  const [, force] = useState(0);
  useEffect(() => {
    const t = setInterval(() => force((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, []);

  const scanAt = useMemo(() => {
    const m: Partial<Record<(typeof SCANS)[number] | "DELIVERY_FAILED", string>> = {};
    for (const r of reports) if (r.kind === "scan" && r.scanType && !m[r.scanType]) m[r.scanType] = r.recordedAt;
    return m;
  }, [reports]);
  const positions = useMemo(() => reports.filter((r) => r.kind === "position"), [reports]);

  if (!signedIn) {
    return (
      <div className="wrap empty">
        <h3>Sign in to follow this order.</h3>
        <p>Orders belong to the customer who placed them.</p>
        <button className="btn" onClick={() => signIn()}>
          Sign in
        </button>
      </div>
    );
  }
  if (error && !order) {
    return (
      <div className="wrap">
        <div className="error" role="alert">
          <strong>{error.status === 404 ? `No order ${orderId}.` : "This order could not be loaded."}</strong>
          {error.status === 404 ? "It does not exist, or it belongs to another customer." : `${error.detail} Retrying.`}
        </div>
        <Link to="/orders">All your orders</Link>
      </div>
    );
  }
  if (!order) return <OrderSkeleton id={orderId} />;

  const failed = isFailure(order.status);
  const stations: Station[] = [
    { key: "placed", label: "Placed", at: order.placedAt, reached: true },
    { key: "confirmed", label: "Confirmed", at: order.status === "CONFIRMED" ? confirmedAt : null, reached: order.status === "CONFIRMED" },
    // tracking-svc marks the shipment READY from the same CONFIRMED event
    { key: "ready", label: "Ready at FC", at: order.status === "CONFIRMED" ? confirmedAt : null, reached: order.status === "CONFIRMED" },
    ...SCANS.map((s) => ({ key: s, label: statusText(s), at: scanAt[s] ?? null, reached: !!scanAt[s] })),
  ];
  const failure = failed
    ? { from: 0, label: statusText(order.status), reason: order.statusReason }
    : scanAt.DELIVERY_FAILED
      ? { from: 5, label: "Delivery failed", reason: null }
      : null;
  const delivered = !!scanAt.DELIVERED;
  const pendingFor = order.status === "PLACED" ? (Date.now() - new Date(order.placedAt).getTime()) / 1000 : 0;
  const lastPosition = positions[positions.length - 1];
  const scans = reports.filter((r) => r.kind === "scan");
  const nowIdx = stations.reduce((k, st, i) => (st.reached ? i : k), 0);
  const now = failed ? statusText(order.status) : stations[nowIdx].label;
  const next = failed ? null : stations[nowIdx + 1]?.label ?? null;

  return (
    <>
      <section className="band order-band">
        <div className="wrap order-head">
          <div className="order-id">
            <h1 className="mono-display">
              <span className="visually-hidden">Order </span>
              {order.orderId}
            </h1>
          </div>
          <dl className="order-facts">
            <div className="platform">
              <dt>Now</dt>
              <dd className={failed ? "now-fail" : undefined}>
                {failed && <span className="fail-square" aria-hidden="true" />}
                {now}
              </dd>
            </div>
            {next && (
              <div className="platform">
                <dt>Next</dt>
                <dd className="next">{next}</dd>
              </div>
            )}
            <div>
              <dt>Deliver to</dt>
              <dd className="facts-line">
                <Bullet region={order.shipToRegion} size="sm" />
                {REGION_NAME[order.shipToRegion]}
              </dd>
            </div>
            <div>
              <dt>Total</dt>
              <dd className="num">{money(order.totalCents, order.currency)}</dd>
            </div>
            <div>
              <dt>{delivered ? "Delivered" : "Arrives by"}</dt>
              <dd className="num">
                {delivered ? clock(scanAt.DELIVERED!) : order.promisedDelivery ? `${day(order.promisedDelivery)}, 9 pm` : failed ? "—" : "Being promised"}
              </dd>
            </div>
          </dl>
        </div>
      </section>

      <div className="wrap">
        <section aria-labelledby="line-h" className="order-line">
          <div className="section-head">
            <h2 id="line-h">The order’s line</h2>
            <span className="muted">
              {order.status === "PLACED"
                ? pendingFor > 6
                  ? "The saga is still working: a pending payment can take up to 15 minutes."
                  : "The saga is reserving stock and authorizing payment."
                : failed
                  ? "The saga stopped and undid what it had done."
                  : "Segment lengths are the real time between stops."}
            </span>
          </div>
          <LineDiagram stations={stations} region={order.shipToRegion} failure={failure} live={stream === "live" && !delivered} />
          {failed && <FailureNote order={order} />}
        </section>

        {!failed && (
          <section aria-labelledby="route-h" className="order-route">
            <div className="section-head">
              <h2 id="route-h">Courier</h2>
              <span className="route-meta">
                {lastPosition && !delivered && <span className="muted num">last report {ago(lastPosition.recordedAt)}</span>}
                {shipment ? <LiveState state={stream} /> : <span className="muted">{order.status === "CONFIRMED" ? "Opening the shipment…" : "Follows once the saga confirms the order"}</span>}
              </span>
            </div>
            <div className="route-grid">
              <RouteMap positions={positions} scans={scans} region={order.shipToRegion} delivered={delivered} promised={order.promisedDelivery} />
              <ReportLog reports={reports} />
            </div>
          </section>
        )}

        <section aria-labelledby="items-h" className="order-items">
          <div className="section-head">
            <h2 id="items-h">Items</h2>
            <span className="muted num">placed {clock(order.placedAt)} · version {order.version}</span>
          </div>
          <table className="lines">
            <thead>
              <tr>
                <th>SKU</th>
                <th className="r">Qty</th>
                <th className="r">Unit</th>
                <th className="r">Line</th>
              </tr>
            </thead>
            <tbody>
              {order.lines.map((l) => (
                <tr key={l.sku}>
                  <td className="id">{l.sku}</td>
                  <td className="r num">{l.quantity}</td>
                  <td className="r num">{money(l.unitPriceCents)}</td>
                  <td className="r num">{money(l.lineTotalCents)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      </div>
    </>
  );
}

function FailureNote({ order }: { order: Order }) {
  const what: Record<string, string> = {
    REJECTED: "No fulfillment center had enough stock, so nothing was held and no payment was taken.",
    PAYMENT_FAILED: "The card was declined. The stock held for this order was released before you were told.",
    CANCELLED: "The saga gave up, voided any payment and released the stock.",
  };
  return (
    <div className="failnote">
      <strong>What the saga did</strong>
      <p>{what[order.status]}</p>
      <Link className="btn ghost" to="/">
        Back to the shop
      </Link>
    </div>
  );
}

function ReportLog({ reports }: { reports: Report[] }) {
  const recent = reports.slice(-14).reverse();
  return (
    <div className="reportlog">
      <table>
        <caption className="visually-hidden">Courier reports, newest first</caption>
        <thead>
          <tr>
            <th className="r">Seq</th>
            <th>Time</th>
            <th>Report</th>
          </tr>
        </thead>
        <tbody>
          {recent.length === 0 && (
            <tr>
              <td colSpan={3} className="muted">
                Nothing reported yet.
              </td>
            </tr>
          )}
          {recent.map((r) => (
            <tr key={r.seq} data-kind={r.kind}>
              <td className="r num">{r.seq}</td>
              <td className="num">{clock(r.recordedAt)}</td>
              <td>
                {r.kind === "scan" ? (
                  <strong>
                    {statusText(r.scanType!)}
                    {r.location ? ` · ${r.location}` : ""}
                  </strong>
                ) : (
                  <span className="num">
                    {r.lat!.toFixed(4)}, {r.lon!.toFixed(4)}
                  </span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function OrderSkeleton({ id }: { id: string }) {
  return (
    <section className="band order-band">
      <div className="wrap order-head">
        <div className="order-id">
          <h1 className="mono-display">
            <span className="visually-hidden">Order </span>
            {id}
          </h1>
        </div>
        <p className="muted" role="status">
          Loading…
        </p>
      </div>
    </section>
  );
}

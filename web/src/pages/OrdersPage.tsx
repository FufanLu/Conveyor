import { useEffect, useState } from "react";
import { Link } from "react-router";
import { Problem, type Order } from "../api/types";
import { useSession } from "../session";
import { Bullet, Plate, day, money } from "../ui/kit";
import "./orders.css";

export default function OrdersPage() {
  const { backend, who, signIn } = useSession();
  const [orders, setOrders] = useState<Order[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!who) return;
    let live = true;
    const load = () =>
      backend
        .myOrders()
        .then((o) => live && (setOrders(o), setError(null)))
        .catch((e) => live && setError(e instanceof Problem ? e.detail : "The order service did not answer."));
    void load();
    const t = setInterval(load, 4000);
    return () => {
      live = false;
      clearInterval(t);
    };
  }, [backend, who]);

  if (!who) {
    return (
      <div className="wrap empty">
        <h3>Sign in to see your orders.</h3>
        <button className="btn" onClick={() => signIn()}>
          Sign in
        </button>
      </div>
    );
  }
  return (
    <div className="wrap">
      <div className="section-head">
        <h1 className="page-title">My orders</h1>
        <span className="muted">Newest first, refreshed every few seconds</span>
      </div>
      {error && (
        <div className="error" role="alert">
          <strong>Orders could not be loaded.</strong>
          {error}
        </div>
      )}
      {orders && orders.length === 0 && (
        <div className="empty">
          <h3>No orders yet.</h3>
          <p>
            Pick anything in the <Link to="/">shop</Link>, expand its row and place an order. You will land on its line
            and can watch the saga and the courier from there.
          </p>
        </div>
      )}
      <ol className="departures">
        {orders?.map((o) => (
          <li key={o.orderId}>
            <Link to={`/orders/${o.orderId}`} className="departure">
              <Bullet region={o.shipToRegion} />
              <span className="dep-id id">{o.orderId}</span>
              <span className="dep-items muted">
                {o.lines.reduce((s, l) => s + l.quantity, 0)} item{o.lines.reduce((s, l) => s + l.quantity, 0) === 1 ? "" : "s"}
              </span>
              <span className="dep-total num">{money(o.totalCents, o.currency)}</span>
              <span className="dep-when num muted">{day(o.placedAt)}</span>
              <span className="dep-status">
                <Plate status={o.status} />
              </span>
            </Link>
          </li>
        ))}
      </ol>
    </div>
  );
}

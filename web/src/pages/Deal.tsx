import { useEffect, useId, useRef, useState, type CSSProperties } from "react";
import { useNavigate } from "react-router";
import { Problem, REGIONS, type Deal, type Hit, type Place, type Region } from "../api/types";
import { useSession } from "../session";
import { Bullet, REGION_NAME, REGION_VAR, money } from "../ui/kit";
import "./deal.css";

const n = (v: number) => v.toLocaleString("en-US");

/** After a failed poll: a random wait up to 2 s, 4 s, 8 s … 30 s, so a crowd does not retry in step. */
const backoff = (failures: number) => 500 + Math.random() * Math.min(30_000, 2000 * 2 ** Math.min(failures, 4));

function duration(seconds: number) {
  if (seconds < 60) return `${Math.max(1, seconds)} s`;
  const m = Math.floor(seconds / 60);
  return seconds % 60 ? `${m} min ${seconds % 60} s` : `${m} min`;
}

/**
 * A deal sold through the gateway's waiting room. The panel walks one customer through the line
 * in place: join, wait while the front of the line comes toward them, order within ten minutes
 * of their turn. Every number comes from the gateway's answers (place, ahead, eta, rate).
 */
export function DealPanel({ id, deal, p }: { id: string; deal: Deal; p: Hit }) {
  const { backend, who, signIn } = useSession();
  const navigate = useNavigate();
  const [region, setRegion] = useState<Region>("us-east");
  const [place, setPlace] = useState<Place | null>(null);
  const [firstAhead, setFirstAhead] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lost, setLost] = useState(false);
  const [now, setNow] = useState(Date.now());
  const key = useRef(crypto.randomUUID());
  // One key per visit: pressing Join again after a timeout keeps the place the first try got.
  const joinKey = useRef(crypto.randomUUID());
  const [failures, setFailures] = useState(0);

  // Poll when the gateway says to; it spaces polls out for customers far back in the line.
  useEffect(() => {
    if (!place || place.state !== "WAITING") return;
    let live = true;
    const timer = setTimeout(async () => {
      try {
        const next = await backend.pollDeal(deal.id, place.ticket);
        if (live) {
          setPlace(next);
          setLost(false);
          setFailures(0);
        }
      } catch {
        if (live) {
          setLost(true);
          setFailures((f) => f + 1);
          setPlace({ ...place }); // try again
        }
      }
    }, lost ? backoff(failures) : (place.pollAfterMs ?? 2000));
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [backend, deal.id, place, lost, failures]);

  // The admission window counts down by the second.
  useEffect(() => {
    if (place?.state !== "ADMITTED") return;
    setNow(Date.now());
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [place?.state]);

  const join = async () => {
    setBusy(true);
    setError(null);
    try {
      const first = await backend.joinDeal(deal.id, joinKey.current);
      setFirstAhead(first.ahead);
      setPlace(first);
    } catch (e) {
      setError(e instanceof Problem ? e.detail : "The line did not answer. Try joining again.");
    } finally {
      setBusy(false);
    }
  };

  const order = async () => {
    if (!place?.admission) return;
    setBusy(true);
    setError(null);
    try {
      const o = await backend.placeOrder([{ sku: p.sku, quantity: 1 }], region, key.current, place.admission);
      navigate(`/orders/${o.orderId}`);
    } catch (e) {
      if (e instanceof Problem && e.status === 403) {
        setPlace(null);
        setError("Your ten minutes ran out, so the place went to the next customer. Join the line again.");
      } else {
        setError(
          e instanceof Problem && e.status === 503
            ? "Checkout is busy for a moment. Your place is kept: press Place order again."
            : e instanceof Problem
              ? e.detail
              : "The order service did not answer. Nothing was ordered; try again.",
        );
      }
      setBusy(false);
    }
  };

  const left = place?.admissionExpiresAt ? Math.max(0, Math.floor((Date.parse(place.admissionExpiresAt) - now) / 1000)) : 0;
  const expired = place?.state === "ADMITTED" && left === 0;

  return (
    <div id={id} className="deal" role="region" aria-label={`Lightning deal: ${p.title}`}>
      <fieldset className="buy-region">
        <legend className="field-label">Deliver to</legend>
        {REGIONS.map((r) => (
          <label key={r} className="region-pick" data-on={region === r || undefined}>
            <input type="radio" name={`${id}-region`} value={r} checked={region === r} onChange={() => setRegion(r)} />
            <Bullet region={r} size="sm" />
            {REGION_NAME[r]}
          </label>
        ))}
      </fieldset>

      {!place && (
        <div className="deal-idle">
          <p>
            {n(deal.units)} units at {money(p.priceCents)}, one per order. The deal sells through a line so that
            checkout stays up for everyone: join, keep this page open, and when your turn comes you have ten
            minutes to order.
          </p>
          {who ? (
            <button className="btn" onClick={join} disabled={busy}>
              {busy ? "Joining…" : "Join the line"}
            </button>
          ) : (
            <button className="btn" onClick={() => signIn()}>
              Sign in to join the line
            </button>
          )}
        </div>
      )}

      {place?.state === "WAITING" && (
        <>
          <QueueLine region={region} place={place} firstAhead={firstAhead} />
          <dl className="deal-readout">
            <div>
              <dt>Your place</dt>
              <dd className="num">{n(place.place)}</dd>
            </div>
            <div>
              <dt>Ahead of you</dt>
              <dd className="num">{n(place.ahead)}</dd>
            </div>
            <div>
              <dt>About</dt>
              <dd className="num">{duration(place.etaSeconds)}</dd>
            </div>
            <div>
              <dt>Admitting</dt>
              <dd className="num">{deal.admissionsPerSecond.toFixed(0)}/s</dd>
            </div>
          </dl>
          <p className="deal-note muted" role="status" aria-live="polite">
            {lost
              ? "Lost touch with the line. Your place is kept; checking again shortly…"
              : `${n(place.ahead)} ahead of you. This page checks again on its own.`}
          </p>
        </>
      )}

      {place?.state === "ADMITTED" && (
        <div className="deal-turn" role="status" aria-live="polite">
          <span className="plate">Your turn</span>
          <p>
            {expired ? (
              "Your ten minutes are up."
            ) : (
              <>
                Order within <strong className="num">{`${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}`}</strong>.
                Place {n(place.place)} is held for you.
              </>
            )}
          </p>
          <button className="btn" onClick={order} disabled={busy || expired}>
            {busy ? "Placing…" : `Place order · ${money(p.priceCents)}`}
          </button>
        </div>
      )}

      {place?.state === "SOLD_OUT" && (
        <div className="deal-turn" role="status" aria-live="polite">
          <span className="plate" data-tone="fail">
            Sold out
          </span>
          <p>
            You were {n(place.place)} in line for {n(deal.units)} units. Every unit is spoken for; the line hands out
            a few more places than units because some customers never order, and yours was past that.
          </p>
        </div>
      )}

      {error && (
        <p className="buy-error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

/**
 * The line as a line: the customer's own station at the right, where they joined at the left,
 * and the front of the queue as the train coming toward them in their delivery region's colour.
 */
function QueueLine({ region, place, firstAhead }: { region: Region; place: Place; firstAhead: number }) {
  const done = firstAhead > 0 ? Math.min(1, Math.max(0, 1 - place.ahead / firstAhead)) : 0;
  const labelId = useId();
  return (
    <div
      className="qline"
      style={{ "--qc": REGION_VAR[region], "--done": done } as CSSProperties}
      role="img"
      aria-labelledby={labelId}
    >
      <span id={labelId} className="sr-only">
        {`Front of the line ${Math.round(done * 100)} % of the way to you`}
      </span>
      <span className="qline-track" />
      <span className="qline-done" />
      <span className="qline-stop qline-from">
        <span className="qline-label">Joined</span>
      </span>
      <span className="qline-gap num">{n(place.ahead)} ahead</span>
      <span className="qline-train">
        <Bullet region={region} size="sm" />
      </span>
      <span className="qline-stop qline-you">
        <span className="qline-label">Your turn</span>
      </span>
    </div>
  );
}

/** The deal strip above the results: the product row, opening into the line. */
export function DealSection({ deal }: { deal: Deal }) {
  const { backend } = useSession();
  const [p, setP] = useState<Hit | null>(null);
  const [open, setOpen] = useState(false);
  const panelId = useId();
  useEffect(() => {
    let live = true;
    backend
      .product(deal.sku)
      .then((r) => live && setP(r))
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [backend, deal.sku]);
  if (!p) return null;
  return (
    <section aria-labelledby={`${panelId}-h`}>
      <div className="section-head">
        <h2 id={`${panelId}-h`}>Lightning deal</h2>
        <span className="muted num">
          {n(deal.units)} units · {n(deal.joined)} joined · {n(deal.admitted)} admitted
        </span>
      </div>
      <ol className="timetable deal-table">
        <li className="row" data-open={open || undefined}>
          <button className="row-head" aria-expanded={open} aria-controls={panelId} onClick={() => setOpen(!open)}>
            <span className="row-sku id">{p.sku}</span>
            <span className="row-title">
              <strong>{p.title}</strong>
              <span className="muted">{p.brand}</span>
            </span>
            <span className="row-cat muted">{p.category}</span>
            <span className="row-rating num">{deal.admissionsPerSecond.toFixed(0)}/s admitted</span>
            <span className="row-price num">{money(p.priceCents)}</span>
          </button>
          {open && <DealPanel id={panelId} deal={deal} p={p} />}
        </li>
      </ol>
    </section>
  );
}

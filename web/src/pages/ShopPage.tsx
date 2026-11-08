import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import { useNavigate, useSearchParams } from "react-router";
import { Problem, REGIONS, type Deal, type Hit, type Region, type SearchResult } from "../api/types";
import { useSession } from "../session";
import { Bullet, REGION_NAME, money } from "../ui/kit";
import { DealSection } from "./Deal";
import "./shop.css";

const PAGE = 25;

export default function ShopPage() {
  const { backend } = useSession();
  const [params, setParams] = useSearchParams();
  const text = params.get("q") ?? "";
  const category = params.get("category") ?? "";
  const [draft, setDraft] = useState(text);
  const [result, setResult] = useState<SearchResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [open, setOpen] = useState<string | null>(null);
  const [more, setMore] = useState(false);
  const [deals, setDeals] = useState<Deal[]>([]);

  // Deals and their lines; refreshed every 10 s so the counts move. No deals, no strip.
  useEffect(() => {
    let live = true;
    const load = () =>
      backend
        .deals()
        .then((d) => live && setDeals(d))
        .catch(() => undefined);
    void load();
    const t = setInterval(load, 10_000);
    return () => {
      live = false;
      clearInterval(t);
    };
  }, [backend]);

  useEffect(() => {
    let live = true;
    setLoading(true);
    setError(null);
    backend
      .search({ text, category, size: PAGE })
      .then((r) => live && setResult(r))
      .catch((e) => live && setError(e instanceof Problem ? e.detail : "The catalog did not answer."))
      .finally(() => live && setLoading(false));
    return () => {
      live = false;
    };
  }, [backend, text, category]);

  const loadMore = async () => {
    if (!result) return;
    setMore(true);
    try {
      const next = await backend.search({ text, category, page: Math.floor(result.hits.length / PAGE), size: PAGE });
      setResult({ ...result, hits: [...result.hits, ...next.hits] });
    } catch (e) {
      setError(e instanceof Problem ? e.detail : "The catalog did not answer.");
    } finally {
      setMore(false);
    }
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const next = new URLSearchParams(params);
    if (draft.trim()) next.set("q", draft.trim());
    else next.delete("q");
    setParams(next);
    setOpen(null);
  };
  const pickCategory = (c: string) => {
    const next = new URLSearchParams(params);
    if (c && c !== category) next.set("category", c);
    else next.delete("category");
    setParams(next);
    setOpen(null);
  };

  return (
    <>
      <section className="band shop-band">
        <div className="wrap">
          <form className="shop-search" role="search" onSubmit={submit}>
            <label htmlFor="q" className="shop-legend">
              Search the catalog
            </label>
            <div className="shop-query">
              <input
                id="q"
                className="shop-input"
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                placeholder="Try “kettle”, “Juniper Lane”, “trail backpack”"
                autoComplete="off"
              />
              <button className="btn shop-go" type="submit">
                Search
              </button>
            </div>
          </form>
          <div className="shop-facets" role="group" aria-label="Category">
            {(result?.facets.category ?? []).map((f) => (
              <button
                key={f.value}
                className="facet"
                aria-pressed={category === f.value}
                onClick={() => pickCategory(f.value)}
              >
                {f.value}
                <span className="num">{f.count.toLocaleString("en-US")}</span>
              </button>
            ))}
          </div>
        </div>
      </section>

      <div className="wrap">
        {!text && !category && deals.map((d) => <DealSection key={d.id} deal={d} />)}
        <div className="section-head">
          <h2>{text || category ? "Results" : "Top rated"}</h2>
          <span className="muted num" aria-live="polite">
            {result && !loading
              ? `${result.total.toLocaleString("en-US")} products · ${result.tookMillis} ms`
              : "Searching…"}
          </span>
        </div>
        {error && (
          <div className="error" role="alert">
            <strong>Search is unavailable.</strong>
            {error} Try again in a moment.
          </div>
        )}
        {!error && result && result.hits.length === 0 && !loading && (
          <div className="empty">
            <h3>Nothing matches “{text}”.</h3>
            <p>Every word has to appear in a product’s title or brand. Try fewer words, or clear the category.</p>
          </div>
        )}
        <ol className="timetable" aria-busy={loading}>
          {loading && !result
            ? Array.from({ length: 8 }, (_, i) => <li key={i} className="row skeleton" aria-hidden="true" />)
            : result?.hits.map((p) => (
                <ProductRow key={p.sku} p={p} open={open === p.sku} onToggle={() => setOpen(open === p.sku ? null : p.sku)} />
              ))}
        </ol>
        {result && result.hits.length > 0 && (
          <div className="more">
            <span className="muted num">
              Showing {result.hits.length.toLocaleString("en-US")} of {result.total.toLocaleString("en-US")}
            </span>
            {result.hits.length < Math.min(result.total, 10_000) && (
              <button className="btn ghost" onClick={loadMore} disabled={more}>
                {more ? "Loading…" : `Show ${PAGE} more`}
              </button>
            )}
          </div>
        )}
      </div>
    </>
  );
}

function ProductRow({ p, open, onToggle }: { p: Hit; open: boolean; onToggle: () => void }) {
  const panelId = useId();
  return (
    <li className="row" data-open={open || undefined}>
      <button className="row-head" aria-expanded={open} aria-controls={panelId} onClick={onToggle}>
        <span className="row-sku id">{p.sku}</span>
        <span className="row-title">
          <strong>{p.title}</strong>
          <span className="muted">{p.brand}</span>
        </span>
        <span className="row-cat muted">{p.category}</span>
        <span className="row-rating num" aria-label={`Rated ${p.rating} out of 5`}>
          <Stars value={p.rating} />
          {p.rating.toFixed(1)}
        </span>
        <span className="row-price num">{money(p.priceCents)}</span>
      </button>
      {open && <BuyPanel id={panelId} p={p} />}
    </li>
  );
}

function Stars({ value }: { value: number }) {
  // Five ticks on a rail, filled to the rating: the line-diagram vocabulary, not star glyphs.
  return (
    <svg width="52" height="10" viewBox="0 0 52 10" aria-hidden="true" className="stars">
      <line x1="4" y1="5" x2="48" y2="5" stroke="var(--rule)" strokeWidth="2" />
      <line x1="4" y1="5" x2={4 + (44 * (value - 1)) / 4} y2="5" stroke="var(--ink)" strokeWidth="2" />
      {[0, 1, 2, 3, 4].map((i) => (
        <circle key={i} cx={4 + i * 11} cy="5" r="2.6" fill={i + 1 <= value ? "var(--ink)" : "var(--surface)"} stroke="var(--ink)" strokeWidth="1.2" />
      ))}
    </svg>
  );
}

function BuyPanel({ id, p }: { id: string; p: Hit }) {
  const { backend, who, signIn } = useSession();
  const navigate = useNavigate();
  const [qty, setQty] = useState(1);
  const [region, setRegion] = useState<Region>("us-east");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // One key per intent: a double click or a retry after a timeout reuses it, so the order
  // service returns the same order instead of placing a second one.
  const key = useRef(crypto.randomUUID());
  useEffect(() => {
    key.current = crypto.randomUUID();
  }, [qty, region]);

  const place = async () => {
    setBusy(true);
    setError(null);
    try {
      const order = await backend.placeOrder([{ sku: p.sku, quantity: qty }], region, key.current);
      navigate(`/orders/${order.orderId}`);
    } catch (e) {
      setError(e instanceof Problem ? e.detail : "The order service did not answer. Your order was not placed; try again.");
      setBusy(false);
    }
  };

  const total = p.priceCents * qty;
  const cents = total % 100;
  return (
    <div id={id} className="buy" role="region" aria-label={`Buy ${p.title}`}>
      <div className="buy-qty">
        <span className="field-label">Quantity</span>
        <div className="stepper">
          <button aria-label="One fewer" onClick={() => setQty(Math.max(1, qty - 1))} disabled={qty <= 1}>
            <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
              <path d="M1 6 H11" stroke="currentColor" strokeWidth="2" />
            </svg>
          </button>
          <output className="num" aria-live="polite">
            {qty}
          </output>
          <button aria-label="One more" onClick={() => setQty(Math.min(10, qty + 1))} disabled={qty >= 10}>
            <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
              <path d="M1 6 H11 M6 1 V11" stroke="currentColor" strokeWidth="2" />
            </svg>
          </button>
        </div>
      </div>
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
      <div className="buy-total">
        <span className="field-label">Total</span>
        <strong className="num">{money(total)}</strong>
        {cents === 13 && <span className="hint">The test card declines totals ending in .13.</span>}
        {cents === 37 && <span className="hint">Totals ending in .37 wait for a payment callback.</span>}
      </div>
      <div className="buy-act">
        {who ? (
          <button className="btn" onClick={place} disabled={busy}>
            {busy ? "Placing…" : "Place order"}
          </button>
        ) : (
          <button className="btn" onClick={() => signIn()}>
            Sign in to order
          </button>
        )}
      </div>
      {error && (
        <p className="buy-error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

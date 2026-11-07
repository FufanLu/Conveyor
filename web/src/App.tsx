import { useEffect } from "react";
import { NavLink, Route, Routes, useNavigate } from "react-router";
import { users } from "./api/auth";
import OpsPage from "./pages/OpsPage";
import OrderPage from "./pages/OrderPage";
import OrdersPage from "./pages/OrdersPage";
import ShopPage from "./pages/ShopPage";
import { isOps, useSession } from "./session";
import { LineGlyph } from "./ui/kit";

export default function App() {
  const { who, signIn, signOut, backend } = useSession();
  return (
    <>
      <a className="visually-hidden" href="#main">
        Skip to content
      </a>
      <header className="band topbar">
        <NavLink to="/" className="wordmark" aria-label="Conveyor, home">
          <LineGlyph />
          Conveyor
        </NavLink>
        <nav className="topnav" aria-label="Main">
          <NavLink to="/" end>
            Shop
          </NavLink>
          <NavLink to="/orders">My orders</NavLink>
          {isOps(who) && <NavLink to="/ops">Network</NavLink>}
        </nav>
        <span className="spacer" />
        {backend.kind === "demo" && (
          <span className="demo-plate" title="Synthetic data from an in-browser simulation of the backend">
            Demo data
          </span>
        )}
        <div className="who">
          {who ? (
            <>
              <span>
                {who.name}
                {isOps(who) ? " · ops" : ""}
              </span>
              {backend.kind === "demo" && (
                <button className="btn ghost switch" onClick={() => signIn(isOps(who) ? "alice" : "ops")}>
                  <span className="long">Switch to {isOps(who) ? "alice" : "ops"}</span>
                  <span className="short" aria-hidden="true">
                    As {isOps(who) ? "alice" : "ops"}
                  </span>
                </button>
              )}
              <button className="btn ghost" onClick={signOut}>
                Sign out
              </button>
            </>
          ) : (
            <button className="btn ghost" onClick={() => signIn()}>
              Sign in
            </button>
          )}
        </div>
      </header>
      <main id="main">
        <Routes>
          <Route path="/" element={<ShopPage />} />
          <Route path="/orders" element={<OrdersPage />} />
          <Route path="/orders/:orderId" element={<OrderPage />} />
          <Route path="/ops" element={<OpsPage />} />
          <Route path="/callback" element={<Callback />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </main>
    </>
  );
}

function Callback() {
  const navigate = useNavigate();
  useEffect(() => {
    users
      .signinRedirectCallback()
      .then((u) => navigate(typeof u.state === "string" ? u.state : "/", { replace: true }))
      .catch(() => navigate("/", { replace: true }));
  }, [navigate]);
  return <p className="wrap empty">Signing you in…</p>;
}

function NotFound() {
  return (
    <div className="wrap empty">
      <h3>No such stop on this line.</h3>
      <p>
        The address does not match a page. <NavLink to="/">Back to the shop</NavLink>.
      </p>
    </div>
  );
}

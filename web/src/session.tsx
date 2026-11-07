import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { identityOf, users, type Identity } from "./api/auth";
import { gateway } from "./api/gateway";
import type { Backend } from "./api/types";
import { demo } from "./demo/sim";

const DEMO = import.meta.env.VITE_BACKEND === "demo";

interface Session {
  backend: Backend;
  who: Identity | null;
  ready: boolean;
  signIn(as?: "alice" | "ops"): void;
  signOut(): void;
}

const Ctx = createContext<Session | null>(null);

const DEMO_PEOPLE: Record<"alice" | "ops", Identity> = {
  alice: { name: "alice", subject: "6ba64ca1-f001-4f34-b72f-5dd3880ec474", roles: ["customer"] },
  ops: { name: "ops", subject: "2f6d0c1e-7a44-4c55-9e1a-0b3f5d1c8e90", roles: ["ops"] },
};

export function SessionProvider({ children }: { children: ReactNode }) {
  const [who, setWho] = useState<Identity | null>(null);
  const [ready, setReady] = useState(DEMO);

  useEffect(() => {
    if (DEMO) {
      const saved = sessionStorage.getItem("demo-who") as "alice" | "ops" | null;
      setWho(saved ? DEMO_PEOPLE[saved] : DEMO_PEOPLE.alice);
      return;
    }
    let live = true;
    void users.getUser().then((u) => {
      if (live) {
        setWho(identityOf(u));
        setReady(true);
      }
    });
    const loaded = (u: Parameters<Parameters<typeof users.events.addUserLoaded>[0]>[0]) => setWho(identityOf(u));
    const gone = () => setWho(null);
    users.events.addUserLoaded(loaded);
    users.events.addUserUnloaded(gone);
    return () => {
      live = false;
      users.events.removeUserLoaded(loaded);
      users.events.removeUserUnloaded(gone);
    };
  }, []);

  const value = useMemo<Session>(
    () => ({
      backend: DEMO ? demo : gateway,
      who,
      ready,
      signIn: (as) => {
        if (DEMO) {
          const p = as ?? "alice";
          sessionStorage.setItem("demo-who", p);
          setWho(DEMO_PEOPLE[p]);
        } else {
          void users.signinRedirect({ state: window.location.pathname + window.location.search });
        }
      },
      signOut: () => {
        if (DEMO) {
          sessionStorage.removeItem("demo-who");
          setWho(null);
        } else {
          void users.signoutRedirect();
        }
      },
    }),
    [who, ready],
  );
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useSession(): Session {
  const s = useContext(Ctx);
  if (!s) throw new Error("useSession outside SessionProvider");
  return s;
}

export const isOps = (who: Identity | null) => !!who?.roles.includes("ops");

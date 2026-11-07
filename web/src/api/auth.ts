import { UserManager, WebStorageStateStore, type User } from "oidc-client-ts";

/**
 * Keycloak sign-in: authorization code + PKCE with the public client conveyor-web (realm
 * conveyor). Tokens stay in sessionStorage and are refreshed silently in the background.
 */
const authority = import.meta.env.VITE_KEYCLOAK_URL ?? "http://localhost:8180/realms/conveyor";

export const users = new UserManager({
  authority,
  client_id: "conveyor-web",
  redirect_uri: `${window.location.origin}/callback`,
  post_logout_redirect_uri: window.location.origin,
  response_type: "code",
  scope: "openid profile",
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
});

export interface Identity {
  name: string;
  subject: string;
  roles: string[];
}

export function identityOf(user: User | null): Identity | null {
  if (!user || user.expired) return null;
  const claims = user.profile as Record<string, unknown>;
  const realm = (claims.realm_access as { roles?: string[] } | undefined)?.roles;
  let roles = realm ?? [];
  if (roles.length === 0) {
    // Roles live in the access token; the id token may not carry them.
    try {
      const payload = JSON.parse(atob(user.access_token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
      roles = payload?.realm_access?.roles ?? [];
    } catch {
      roles = [];
    }
  }
  return {
    name: (claims.preferred_username as string) ?? (claims.name as string) ?? "signed in",
    subject: user.profile.sub,
    roles,
  };
}

export async function bearer(): Promise<Record<string, string>> {
  const user = await users.getUser();
  return user && !user.expired ? { Authorization: `Bearer ${user.access_token}` } : {};
}

import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { apiRequest, type Credentials } from "../api/client";

export type Role = "VIEWER" | "MAKER" | "CHECKER";

interface Session {
  username: string;
  roles: Role[];
  credentials: Credentials;
}

interface AuthContextValue {
  session: Session | null;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
  loginError: string | null;
  loggingIn: boolean;
}

// Exported (not just used internally) so tests can wire a session directly via
// AuthContext.Provider, bypassing the real login network call -- see test/test-utils.tsx.
export const AuthContext = createContext<AuthContextValue | null>(null);
export type { Session };

/**
 * Credentials and role live only in this component's state -- React memory,
 * cleared on refresh -- never in localStorage/sessionStorage, per the phase
 * brief. The brief also requires the UI to hide actions a role can't
 * perform (§5); this is the one and only place that decision is made, by
 * calling GET /v1/me (added this phase -- see MeController's own javadoc)
 * once at login to learn the authenticated user's actual roles rather than
 * guessing from the username. This is a usability affordance, not a
 * security control: every mutating request still goes to the server with
 * the same credentials, and the server's own @PreAuthorize checks are what
 * actually enforce the role, not this hidden-or-shown button.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [loginError, setLoginError] = useState<string | null>(null);
  const [loggingIn, setLoggingIn] = useState(false);

  const login = useCallback(async (username: string, password: string) => {
    setLoggingIn(true);
    setLoginError(null);
    const credentials = { username, password };
    try {
      const me = await apiRequest<{ username: string; roles: string[] }>("/v1/me", credentials);
      setSession({ username: me.username, roles: me.roles as Role[], credentials });
    } catch {
      setLoginError("Could not sign in. Check the username and password.");
    } finally {
      setLoggingIn(false);
    }
  }, []);

  const logout = useCallback(() => setSession(null), []);

  const value = useMemo(() => ({ session, login, logout, loginError, loggingIn }), [session, login, logout, loginError, loggingIn]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}

export function useRole(role: Role): boolean {
  const { session } = useAuth();
  return session?.roles.includes(role) ?? false;
}

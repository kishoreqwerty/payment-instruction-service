import { NavLink, Outlet } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

/**
 * Nav links are shown to everyone regardless of role -- the approval queue
 * is top-level for a checker (brief §3: "not nested"), but a maker or
 * viewer can still open it to see what's pending, just without an approve
 * button once they're there (SecurityConfig's real 403 is what actually
 * stops a maker from approving; this only decides what's visible). See
 * RequireRole/the screens themselves for the server-mirroring comment this
 * brief's §5 asks for.
 */
export function Layout() {
  const { session, logout } = useAuth();

  return (
    <div className="app-shell">
      <header className="top-nav">
        <span className="brand">Payment Exceptions</span>
        <nav>
          <NavLink to="/" end className={({ isActive }) => (isActive ? "active" : undefined)}>
            Queue
          </NavLink>
          <NavLink to="/approvals" className={({ isActive }) => (isActive ? "active" : undefined)}>
            Approvals
          </NavLink>
          <NavLink to="/lookup" className={({ isActive }) => (isActive ? "active" : undefined)}>
            Lookup
          </NavLink>
        </nav>
        <div className="spacer" />
        {session && (
          <div className="session">
            <span className="mono">{session.username}</span>
            <span className="role-pill">{session.roles.join(", ") || "no role"}</span>
            <button type="button" className="link" onClick={logout}>
              Sign out
            </button>
          </div>
        )}
      </header>
      <main className="screen">
        <Outlet />
      </main>
    </div>
  );
}

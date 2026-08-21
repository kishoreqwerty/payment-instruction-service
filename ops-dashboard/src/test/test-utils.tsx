import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { MemoryRouter } from "react-router-dom";
import { AuthContext, type Role } from "../auth/AuthContext";

export function makeQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
}

/**
 * Wires a session directly into AuthContext rather than driving the real
 * login form -- most screen tests are about what a given role can see and
 * do, not about the login flow itself (LoginScreen.test.tsx covers that
 * separately). credentials.password is fixed to "password" to match every
 * local/test user in SecurityConfig and the MSW handlers below.
 */
export function renderWithSession(
  ui: ReactElement,
  options: { username: string; roles: Role[]; route?: string; queryClient?: QueryClient } = { username: "viewer1", roles: ["VIEWER"] },
) {
  const queryClient = options.queryClient ?? makeQueryClient();
  const session = { username: options.username, roles: options.roles, credentials: { username: options.username, password: "password" } };
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthContext.Provider value={{ session, login: async () => {}, logout: () => {}, loginError: null, loggingIn: false }}>
        <MemoryRouter initialEntries={[options.route ?? "/"]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>{ui}</MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

export function renderUnauthenticated(ui: ReactElement, options: { route?: string } = {}) {
  const queryClient = makeQueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthContext.Provider value={{ session: null, login: async () => {}, logout: () => {}, loginError: null, loggingIn: false }}>
        <MemoryRouter initialEntries={[options.route ?? "/"]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>{ui}</MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

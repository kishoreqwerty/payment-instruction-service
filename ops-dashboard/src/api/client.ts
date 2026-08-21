import type { ErrorBody } from "./types";

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8084";

export interface Credentials {
  username: string;
  password: string;
}

/**
 * Thrown for every non-2xx response. Carries the parsed body when the
 * server sent one (exception-service's ApiExceptionHandler and, since
 * Phase 9, JsonAuthEntryPoints both return `{error, detail}` JSON for
 * every 4xx/401/403 -- see that class's own javadoc for why the security
 * layer needed the same treatment) so a screen can render `detail` instead
 * of a blank page or a stack trace.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly body: ErrorBody | null;

  constructor(status: number, body: ErrorBody | null) {
    super(body?.detail ?? `Request failed with status ${status}`);
    this.status = status;
    this.body = body;
  }
}

export interface RequestOptions {
  method?: "GET" | "POST";
  body?: unknown;
  query?: Record<string, string | number | undefined>;
}

function toQueryString(query: RequestOptions["query"]): string {
  if (!query) return "";
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== "") {
      params.set(key, String(value));
    }
  }
  const qs = params.toString();
  return qs ? `?${qs}` : "";
}

/**
 * Every call takes credentials explicitly rather than reading them from a
 * module-level variable: this keeps the client a pure function of its
 * arguments, which is what makes it trivial to swap in a fixed set of test
 * credentials under MSW without any hidden global state.
 */
export async function apiRequest<T>(path: string, credentials: Credentials | null, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {};
  if (credentials) {
    headers.Authorization = `Basic ${btoa(`${credentials.username}:${credentials.password}`)}`;
  }
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }

  const response = await fetch(`${BASE_URL}${path}${toQueryString(options.query)}`, {
    method: options.method ?? "GET",
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  });

  if (!response.ok) {
    let body: ErrorBody | null = null;
    try {
      body = await response.json();
    } catch {
      body = null;
    }
    throw new ApiError(response.status, body);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

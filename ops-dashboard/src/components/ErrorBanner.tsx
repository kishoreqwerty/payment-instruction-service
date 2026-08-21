import { ApiError } from "../api/client";

/**
 * Acceptance criterion 5: a 403 (or any other API failure) must render as a
 * clear message, not a blank screen or a raw error. Every screen that calls
 * a mutating endpoint routes its error through this component rather than
 * letting a thrown ApiError reach the console unhandled.
 */
export function ErrorBanner({ error }: { error: unknown }) {
  if (!error) return null;
  const message = error instanceof ApiError ? apiErrorMessage(error) : "Something went wrong. Please try again.";
  return (
    <div className="error-banner" role="alert">
      {message}
    </div>
  );
}

function apiErrorMessage(error: ApiError): string {
  if (error.status === 401) return "Your session is no longer valid. Please sign in again.";
  if (error.status === 403) return error.body?.detail ?? "You do not have permission to do that.";
  if (error.status === 404) return error.body?.detail ?? "That item could not be found.";
  if (error.status === 409) return error.body?.detail ?? "That action can't be completed in the case's current state.";
  if (error.status === 422) return error.body?.detail ?? "That field can't be repaired this way.";
  return error.body?.detail ?? `The server reported an error (${error.status}).`;
}

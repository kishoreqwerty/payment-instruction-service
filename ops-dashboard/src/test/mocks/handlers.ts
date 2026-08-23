import { http, HttpResponse } from "msw";
import * as data from "./data";

const BASE = "http://localhost:8084";

function usernameFrom(request: Request): string | null {
  const auth = request.headers.get("Authorization");
  if (!auth?.startsWith("Basic ")) return null;
  const decoded = atob(auth.slice("Basic ".length));
  return decoded.split(":")[0] ?? null;
}

function requireAuth(request: Request) {
  const username = usernameFrom(request);
  if (!username || !(username in data.usersRoles)) {
    return HttpResponse.json({ error: "UNAUTHORIZED", detail: "Authentication is required to access this resource" }, { status: 401 });
  }
  return { username };
}

export const handlers = [
  http.get(`${BASE}/v1/me`, ({ request }) => {
    const auth = requireAuth(request);
    if (auth instanceof HttpResponse) return auth;
    return HttpResponse.json({ username: auth.username, roles: data.usersRoles[auth.username] });
  }),

  http.get(`${BASE}/v1/cases`, ({ request }) => {
    const auth = requireAuth(request);
    if (auth instanceof HttpResponse) return auth;
    const content = [data.openBusinessCase, data.pendingApprovalCase, data.investigationCase];
    return HttpResponse.json({ content, totalElements: content.length, totalPages: 1, number: 0, size: 100 });
  }),

  http.get(`${BASE}/v1/cases/:caseId`, ({ request, params }) => {
    const auth = requireAuth(request);
    if (auth instanceof HttpResponse) return auth;
    const detail = data.caseDetails[params.caseId as string];
    if (!detail) return HttpResponse.json({ error: "NOT_FOUND", detail: "No such case" }, { status: 404 });
    return HttpResponse.json(detail);
  }),

  http.get(`${BASE}/v1/repairable-fields`, ({ request }) => {
    const auth = requireAuth(request);
    if (auth instanceof HttpResponse) return auth;
    return HttpResponse.json([
      { fieldPath: "creditorAccount", label: "Creditor account" },
      { fieldPath: "creditorAgentBic", label: "Creditor agent bic" },
      { fieldPath: "creditorName", label: "Creditor name" },
      { fieldPath: "chargeBearer", label: "Charge bearer" },
      { fieldPath: "requestedExecutionDate", label: "Requested execution date" },
    ]);
  }),

  http.get(`${BASE}/v1/repairs/pending`, ({ request }) => {
    const auth = requireAuth(request);
    if (auth instanceof HttpResponse) return auth;
    return HttpResponse.json([data.pendingRepairAction]);
  }),

  http.get(`${BASE}/v1/investigation-confirmations/pending`, ({ request }) => {
    const auth = requireAuth(request);
    if (auth instanceof HttpResponse) return auth;
    return HttpResponse.json([data.pendingConfirmation]);
  }),

  http.get(`${BASE}/v1/instructions/:instructionId/timeline`, ({ request, params }) => {
    const auth = requireAuth(request);
    if (auth instanceof HttpResponse) return auth;
    return HttpResponse.json(data.timelineFor[params.instructionId as string] ?? []);
  }),

  http.get(`${BASE}/v1/instructions`, ({ request }) => {
    const auth = requireAuth(request);
    if (auth instanceof HttpResponse) return auth;
    return HttpResponse.json([]);
  }),

  http.post(`${BASE}/v1/cases/:caseId/repairs`, async ({ request }) => {
    const auth = requireAuth(request);
    if (auth instanceof HttpResponse) return auth;
    if (!(data.usersRoles[auth.username] ?? []).includes("MAKER")) {
      return HttpResponse.json({ error: "FORBIDDEN", detail: "Your role does not permit this action" }, { status: 403 });
    }
    return HttpResponse.json([data.pendingRepairAction], { status: 201 });
  }),

  http.post(`${BASE}/v1/repairs/:actionId/approve`, ({ request }) => {
    const auth = requireAuth(request);
    if (auth instanceof HttpResponse) return auth;
    if (auth.username === data.pendingRepairAction.proposedBy) {
      return HttpResponse.json({ error: "MAKER_CHECKER_VIOLATION", detail: "A different checker must approve this repair" }, { status: 403 });
    }
    return HttpResponse.json({ ...data.pendingRepairAction, approvedBy: auth.username, approvedAt: new Date().toISOString() });
  }),

  http.post(`${BASE}/v1/cases/:caseId/investigation/confirm-sent`, ({ request }) => {
    const auth = requireAuth(request);
    if (auth instanceof HttpResponse) return auth;
    return HttpResponse.json(data.pendingConfirmation, { status: 201 });
  }),

  http.post(`${BASE}/v1/investigation-confirmations/:confirmationId/approve`, ({ request }) => {
    const auth = requireAuth(request);
    if (auth instanceof HttpResponse) return auth;
    if (auth.username === data.pendingConfirmation.proposedBy) {
      return HttpResponse.json({ error: "MAKER_CHECKER_VIOLATION", detail: "A different checker must approve this confirmation" }, { status: 403 });
    }
    return HttpResponse.json({ ...data.pendingConfirmation, approvedBy: auth.username, approvedAt: new Date().toISOString() });
  }),

  http.post(`${BASE}/v1/cases/:caseId/investigation/reject`, ({ request }) => {
    const auth = requireAuth(request);
    if (auth instanceof HttpResponse) return auth;
    return HttpResponse.json({ ...data.investigationCase, status: "REJECTED" });
  }),

  http.post(`${BASE}/v1/cases/:caseId/reject`, ({ request }) => {
    const auth = requireAuth(request);
    if (auth instanceof HttpResponse) return auth;
    return HttpResponse.json({ ...data.openBusinessCase, status: "REJECTED" });
  }),

  http.post(`${BASE}/v1/cases/:caseId/retry`, ({ request }) => {
    const auth = requireAuth(request);
    if (auth instanceof HttpResponse) return auth;
    return HttpResponse.json(data.openBusinessCase);
  }),

  http.post(`${BASE}/v1/cases/:caseId/classifier-feedback`, async ({ request }) => {
    const auth = requireAuth(request);
    if (auth instanceof HttpResponse) return auth;
    const body = (await request.json()) as { accepted: boolean };
    return HttpResponse.json({ ...data.openBusinessCase, classifierAccepted: body.accepted });
  }),
];

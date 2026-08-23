import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../auth/AuthContext";
import { apiRequest } from "./client";
import type {
  CaseDetailResponse,
  CaseStatus,
  FailureStage,
  FieldChange,
  InstructionSummaryResponse,
  InvestigationConfirmationResponse,
  Page,
  Repairability,
  RepairableFieldResponse,
  RepairActionResponse,
  TimelineEntry,
} from "./types";

function useApi() {
  const { session } = useAuth();
  return {
    credentials: session?.credentials ?? null,
    get: <T>(path: string, query?: Record<string, string | number | undefined>) =>
      apiRequest<T>(path, session?.credentials ?? null, { query }),
    post: <T>(path: string, body?: unknown) => apiRequest<T>(path, session?.credentials ?? null, { method: "POST", body }),
  };
}

export interface CaseFilters {
  status?: CaseStatus;
  failureStage?: FailureStage;
  reasonCode?: string;
  repairability?: Repairability;
  assignedTo?: string;
  page?: number;
  sort?: string;
}

export function useCases(filters: CaseFilters) {
  const api = useApi();
  return useQuery({
    queryKey: ["cases", filters],
    queryFn: () =>
      api.get<Page<import("./types").CaseSummaryResponse>>("/v1/cases", {
        status: filters.status,
        failureStage: filters.failureStage,
        reasonCode: filters.reasonCode,
        repairability: filters.repairability,
        assignedTo: filters.assignedTo,
        page: filters.page,
        size: 100,
        sort: filters.sort,
      }),
    enabled: api.credentials !== null,
    // Each distinct filter/sort combination is its own query key, so without this the whole
    // table would unmount to a loading state on every toggle -- exactly the disruption the
    // brief warns against ("no animation on anything the operator does repeatedly"). The old
    // rows stay on screen, dimmed by isFetching if a caller wants that, while the new page loads.
    placeholderData: keepPreviousData,
  });
}

export function useCase(caseId: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ["case", caseId],
    queryFn: () => api.get<CaseDetailResponse>(`/v1/cases/${caseId}`),
    enabled: api.credentials !== null && caseId !== undefined,
  });
}

export function useTimeline(instructionId: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ["timeline", instructionId],
    queryFn: () => api.get<TimelineEntry[]>(`/v1/instructions/${instructionId}/timeline`),
    enabled: api.credentials !== null && instructionId !== undefined,
  });
}

export function useInstructionLookup(query: { uetr?: string; endToEndId?: string } | null) {
  const api = useApi();
  return useQuery({
    queryKey: ["instruction-lookup", query],
    queryFn: () => api.get<InstructionSummaryResponse[]>("/v1/instructions", { uetr: query?.uetr, endToEndId: query?.endToEndId }),
    enabled: api.credentials !== null && query !== null && (Boolean(query.uetr) || Boolean(query.endToEndId)),
  });
}

export function usePendingRepairs() {
  const api = useApi();
  return useQuery({
    queryKey: ["pending-repairs"],
    queryFn: () => api.get<RepairActionResponse[]>("/v1/repairs/pending"),
    enabled: api.credentials !== null,
  });
}

export function useRepairableFields() {
  const api = useApi();
  return useQuery({
    queryKey: ["repairable-fields"],
    queryFn: () => api.get<RepairableFieldResponse[]>("/v1/repairable-fields"),
    enabled: api.credentials !== null,
    staleTime: Infinity, // a fixed allowlist for the lifetime of the session; no reason to refetch it
  });
}

export function usePendingConfirmations() {
  const api = useApi();
  return useQuery({
    queryKey: ["pending-confirmations"],
    queryFn: () => api.get<InvestigationConfirmationResponse[]>("/v1/investigation-confirmations/pending"),
    enabled: api.credentials !== null,
  });
}

function useInvalidateAfterMutation() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: ["cases"] });
    void queryClient.invalidateQueries({ queryKey: ["case"] });
    void queryClient.invalidateQueries({ queryKey: ["pending-repairs"] });
    void queryClient.invalidateQueries({ queryKey: ["pending-confirmations"] });
    void queryClient.invalidateQueries({ queryKey: ["timeline"] });
  };
}

export function useProposeRepair() {
  const api = useApi();
  const invalidate = useInvalidateAfterMutation();
  return useMutation({
    mutationFn: ({ caseId, changes }: { caseId: string; changes: FieldChange[] }) =>
      api.post<RepairActionResponse[]>(`/v1/cases/${caseId}/repairs`, changes),
    onSuccess: invalidate,
  });
}

export function useApproveRepair() {
  const api = useApi();
  const invalidate = useInvalidateAfterMutation();
  return useMutation({
    mutationFn: (actionId: string) => api.post<RepairActionResponse>(`/v1/repairs/${actionId}/approve`),
    onSuccess: invalidate,
  });
}

export function useRetryStaticData() {
  const api = useApi();
  const invalidate = useInvalidateAfterMutation();
  return useMutation({
    mutationFn: (caseId: string) => api.post(`/v1/cases/${caseId}/retry`),
    onSuccess: invalidate,
  });
}

export function useClassifierFeedback() {
  const api = useApi();
  const invalidate = useInvalidateAfterMutation();
  return useMutation({
    mutationFn: ({ caseId, accepted }: { caseId: string; accepted: boolean }) =>
      api.post<import("./types").CaseSummaryResponse>(`/v1/cases/${caseId}/classifier-feedback`, { accepted }),
    onSuccess: invalidate,
  });
}

export function useRejectCase() {
  const api = useApi();
  const invalidate = useInvalidateAfterMutation();
  return useMutation({
    mutationFn: (caseId: string) => api.post(`/v1/cases/${caseId}/reject`),
    onSuccess: invalidate,
  });
}

export function useProposeConfirmSent() {
  const api = useApi();
  const invalidate = useInvalidateAfterMutation();
  return useMutation({
    mutationFn: ({ caseId, justification }: { caseId: string; justification: string }) =>
      api.post<InvestigationConfirmationResponse>(`/v1/cases/${caseId}/investigation/confirm-sent`, { justification }),
    onSuccess: invalidate,
  });
}

export function useApproveConfirmSent() {
  const api = useApi();
  const invalidate = useInvalidateAfterMutation();
  return useMutation({
    mutationFn: (confirmationId: string) =>
      api.post<InvestigationConfirmationResponse>(`/v1/investigation-confirmations/${confirmationId}/approve`),
    onSuccess: invalidate,
  });
}

export function useRejectInvestigation() {
  const api = useApi();
  const invalidate = useInvalidateAfterMutation();
  return useMutation({
    mutationFn: ({ caseId, justification }: { caseId: string; justification: string }) =>
      api.post(`/v1/cases/${caseId}/investigation/reject`, { justification }),
    onSuccess: invalidate,
  });
}

import type { HealthResponse } from '@/types/api';
import { apiClient } from './apiClient';

/**
 * The only service Phase 1 implements.
 *
 * It exists so the whole request path — config, URL building, correlation id, error
 * normalisation, CORS — can be proven against the real backend before a single banking
 * endpoint is written. It is also the template every later service follows: thin, typed,
 * no UI concerns, no error swallowing.
 */
export const healthService = {
  /** `GET /health`. Throws {@link import('./apiError').ApiError} on failure. */
  check: (signal?: AbortSignal): Promise<HealthResponse> =>
    apiClient.get<HealthResponse>('/health', signal === undefined ? {} : { signal }),
} as const;

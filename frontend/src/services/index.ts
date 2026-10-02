/**
 * Service layer barrel.
 *
 * Feature code imports from `@/services`. As each phase lands its endpoints, the new
 * service is exported here: accountService, beneficiaryService, transferService,
 * paymentService, statementService, loanService, cardService, bulkService,
 * approvalService (blueprint section 39).
 */
export { apiClient, apiRequest, onUnauthenticated } from './apiClient';
export type { ApiRequest, HttpMethod, QueryValue } from './apiClient';
export { ApiError, isApiErrorEnvelope, kindFromStatus } from './apiError';
export type { ApiErrorKind } from './apiError';
export { CORRELATION_HEADER, newCorrelationId } from './correlation';
export { IDEMPOTENCY_HEADER, IdempotencyScope, newIdempotencyKey } from './idempotency';
export { adminService, staffAuthService, temporaryPasswordService } from './adminService';
export type { StaffSummary } from './adminService';
export { authService } from './authService';
export { healthService } from './healthService';
export { registrationService } from './registrationService';
export { sessionService } from './sessionService';
export type { SessionResponse } from './sessionService';
export {
  accountService,
  approvalService,
  beneficiaryService,
  bulkService,
  cardService,
  loanService,
  notificationService,
  serviceRequestService,
} from './bankingService';
export type {
  BalanceResponse,
  LoanApplication,
  LoanSimulation,
  NewBeneficiary,
  TransactionQuery,
} from './bankingService';
export {
  cashService,
  fxService,
  paymentService,
  securityService,
  standingOrderService,
  statementService,
  internalTransferService,
  transferService,
} from './movementService';

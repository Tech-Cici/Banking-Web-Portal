/**
 * Every route in the application, in one place.
 *
 * Paths are referenced through these constants and builders, never as string literals in
 * components. A renamed route then changes in one file instead of leaking a dead link into
 * a navigation menu, and the parameterised builders make a malformed URL a compile error.
 *
 * The set below is exactly the route map from the brief (section 48) and the blueprint's
 * page inventory (section 4). Nothing has been added.
 */

export const PUBLIC_PATHS = {
  /*
   * Landing and registration are NOT in the blueprint's page inventory (section 4); they
   * were requested directly. Registration appears there only as section 5.1's
   * "Optional: Register/Activate Internet Banking if onboarding is enabled".
   */
  landing: '/',
  register: '/register',
  registerPersonal: '/register/personal',
  registerBusiness: '/register/business',
  registerJoin: '/register/join',

  login: '/login',
  verify: '/auth/verify',
  forgotPassword: '/forgot-password',
  resetPassword: '/reset-password',
  /** Where a temporary password lands. The portal opens only once it is replaced. */
  changeTemporaryPassword: '/auth/new-password',
} as const;

/**
 * The bank's own portal.
 *
 * A separate URL space, not a section of the customer portal. Staff and customers are
 * different populations with different sessions, and a shared prefix is how a support
 * tool ends up one guessed URL away from a customer's screen.
 */
export const STAFF_PATHS = {
  login: '/staff/login',
  dashboard: '/staff',
  applications: '/staff/applications',
  applicationDetail: '/staff/applications/:id',
  approvals: '/staff/approvals',
  /** Money a customer has sent that a manager has not released yet. */
  transferQueue: '/staff/transfers',
  /** Customers who have forgotten their password and asked the bank for a new one. */
  passwordRequests: '/staff/password-requests',
  /** Saved payees waiting for a member of staff to check the name against the account. */
  beneficiaryReviews: '/staff/payees',
  /** Cards and cheque books to make, and ones waiting on the counter to be collected. */
  serviceRequests: '/staff/requests',
  customers: '/staff/customers',
  /**
   * Developer tools: the message log and the start-over button.
   *
   * NOT A BANKING SCREEN, and no longer on the overview. Both of these used to sit on the
   * first page staff see, one of them a red delete-everything button under a paragraph
   * naming build profiles. `StaffLayout` hides the link outside development and the backend
   * refuses the reset outside the dev, h2 and test profiles.
   */
  developerTools: '/staff/developer-tools',
} as const;

export const RETAIL_PATHS = {
  dashboard: '/dashboard',

  accounts: '/accounts',
  accountDetail: '/accounts/:accountId',
  accountTransactions: '/accounts/:accountId/transactions',
  accountStatementNew: '/accounts/:accountId/statements/new',
  statements: '/statements',

  beneficiaries: '/beneficiaries',
  beneficiaryNew: '/beneficiaries/new',

  transfers: '/transfers',
  transferOwn: '/transfers/own',
  transferInternal: '/transfers/internal',
  transferExternal: '/transfers/external',
  transferInternational: '/transfers/international',

  payments: '/payments',
  paymentWallet: '/payments/wallet',
  paymentAirtime: '/payments/airtime',
  paymentElectricity: '/payments/electricity',
  paymentWater: '/payments/water',
  paymentTv: '/payments/tv',
  paymentTax: '/payments/tax',
  paymentAfos: '/payments/afos',

  standingOrders: '/standing-orders',
  standingOrderNew: '/standing-orders/new',
  standingOrderDetail: '/standing-orders/:id',

  fx: '/fx',
  fxReview: '/fx/:quoteId/review',

  loans: '/loans',
  loanDetail: '/loans/:loanId',
  loanSimulation: '/loans/simulation',
  loanRequest: '/loans/request',
  loanStatements: '/loans/:loanId/statements',

  cards: '/cards',
  cardRequest: '/cards/request',
  cardRequestStatus: '/cards/requests/:id',
  cardBlock: '/cards/:id/block',

  chequeBooks: '/cheque-books',
  chequeBookNew: '/cheque-books/new',

  profile: '/profile',
  security: '/security',
  notifications: '/notifications',
  help: '/help',
} as const;

export const CORPORATE_PATHS = {
  dashboard: '/corporate/dashboard',

  approvals: '/approvals',
  approvalDetail: '/approvals/:id',

  bulk: '/bulk',
  bulkNew: '/bulk/new',
  bulkValidation: '/bulk/:batchId/validation',
  bulkReview: '/bulk/:batchId/review',
  bulkDetail: '/bulk/:batchId',

  /** Blueprint section 18.6. Reuses the bulk engine with salary-specific permissions. */
  salary: '/salary',
} as const;

/**
 * Phase 1 only.
 *
 * A diagnostic page that exercises the real API client against the backend so the
 * architecture can be verified in a browser before any banking screen exists. Fold it into
 * /help (blueprint 19.4, "service status") or delete it once Phase 5 lands.
 */
export const DIAGNOSTIC_PATHS = {
  systemStatus: '/system-status',
} as const;

export const PATHS = {
  ...PUBLIC_PATHS,
  ...STAFF_PATHS,
  ...RETAIL_PATHS,
  ...CORPORATE_PATHS,
  ...DIAGNOSTIC_PATHS,
} as const;

/** Where an authenticated user lands when nothing more specific applies. */
export const DEFAULT_AUTHENTICATED_PATH = RETAIL_PATHS.dashboard;

/**
 * Landing route for a user type.
 *
 * A corporate user dropped on the retail dashboard sees an empty page — their accounts
 * belong to a company, not to them — so the destination depends on who signed in.
 */
export function homePathFor(userType: 'RETAIL' | 'CORPORATE'): string {
  return userType === 'CORPORATE' ? CORPORATE_PATHS.dashboard : RETAIL_PATHS.dashboard;
}

/* --- Builders for parameterised routes ------------------------------------------------ */

function fill(template: string, params: Readonly<Record<string, string>>): string {
  return Object.entries(params).reduce(
    (path, [key, value]) => path.replace(`:${key}`, encodeURIComponent(value)),
    template,
  );
}

export const routeTo = {
  staffApplication: (id: string): string => fill(STAFF_PATHS.applicationDetail, { id }),
  accountDetail: (accountId: string): string => fill(RETAIL_PATHS.accountDetail, { accountId }),
  accountTransactions: (accountId: string): string =>
    fill(RETAIL_PATHS.accountTransactions, { accountId }),
  accountStatementNew: (accountId: string): string =>
    fill(RETAIL_PATHS.accountStatementNew, { accountId }),
  standingOrderDetail: (id: string): string => fill(RETAIL_PATHS.standingOrderDetail, { id }),
  fxReview: (quoteId: string): string => fill(RETAIL_PATHS.fxReview, { quoteId }),
  loanDetail: (loanId: string): string => fill(RETAIL_PATHS.loanDetail, { loanId }),
  loanStatements: (loanId: string): string => fill(RETAIL_PATHS.loanStatements, { loanId }),
  cardRequestStatus: (id: string): string => fill(RETAIL_PATHS.cardRequestStatus, { id }),
  cardBlock: (id: string): string => fill(RETAIL_PATHS.cardBlock, { id }),
  approvalDetail: (id: string): string => fill(CORPORATE_PATHS.approvalDetail, { id }),
  bulkValidation: (batchId: string): string => fill(CORPORATE_PATHS.bulkValidation, { batchId }),
  bulkReview: (batchId: string): string => fill(CORPORATE_PATHS.bulkReview, { batchId }),
  bulkDetail: (batchId: string): string => fill(CORPORATE_PATHS.bulkDetail, { batchId }),
} as const;

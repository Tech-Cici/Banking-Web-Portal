import { createBrowserRouter, type RouteObject } from 'react-router-dom';
import { AppLayout } from '@/layouts/AppLayout';
import { MarketingLayout } from '@/layouts/MarketingLayout';
import { PublicLayout } from '@/layouts/PublicLayout';
import { NotFoundPage } from '@/pages/NotFoundPage';
import { PlaceholderPage } from '@/pages/PlaceholderPage';
import { SystemStatusPage } from '@/pages/SystemStatusPage';
import { ForgotPasswordPage, LoginPage, NewPasswordPage, OtpVerifyPage } from '@/features/auth';
import { CorporateDashboardPage, RetailDashboardPage } from '@/features/dashboard';
import {
  BusinessRegistrationPage,
  JoinBusinessPage,
  PersonalRegistrationPage,
} from '@/features/registration';
import { LandingPage } from '@/pages/landing/LandingPage';
import { RegisterChoicePage } from '@/pages/landing/RegisterChoicePage';
import { ProtectedRoute } from './ProtectedRoute';
import {
  CORPORATE_PATHS,
  DIAGNOSTIC_PATHS,
  PUBLIC_PATHS,
  RETAIL_PATHS,
  STAFF_PATHS,
} from './paths';

/**
 * The application's route table.
 *
 * Every route from the brief (section 48) is declared here and renders a real screen,
 * with three exceptions that say so on the page itself because the backend contract for
 * them does not exist yet: the bulk upload endpoint, the statement download endpoint and
 * per-row batch listing.
 *
 * CODE SPLITTING. The feature groups below are loaded on demand rather than bundled into
 * the first download. A retail customer has no business downloading the corporate bulk
 * and approval screens they can never open, and a corporate approver has no use for the
 * loan calculator. The dashboards are deliberately NOT split: they are the first thing
 * every session renders, so splitting them would only add a round trip to the screen
 * people are waiting on.
 */

/** Kept for the routes whose backend contract is still owed. */
function placeholder(
  title: string,
  phase: string,
  blueprintSection: string,
): RouteObject['element'] {
  return <PlaceholderPage title={title} phase={phase} blueprintSection={blueprintSection} />;
}

const marketingRoutes: RouteObject[] = [
  {
    element: <MarketingLayout />,
    children: [
      { path: PUBLIC_PATHS.landing, element: <LandingPage /> },
      { path: PUBLIC_PATHS.register, element: <RegisterChoicePage /> },
      { path: PUBLIC_PATHS.registerPersonal, element: <PersonalRegistrationPage /> },
      { path: PUBLIC_PATHS.registerBusiness, element: <BusinessRegistrationPage /> },
      { path: PUBLIC_PATHS.registerJoin, element: <JoinBusinessPage /> },
    ],
  },
];

const publicRoutes: RouteObject[] = [
  {
    element: <PublicLayout />,
    children: [
      { path: PUBLIC_PATHS.login, element: <LoginPage /> },
      { path: PUBLIC_PATHS.verify, element: <OtpVerifyPage /> },
      /*
       * Where a temporary password lands. Public-layout, not protected: the customer
       * HAS a session at this point, but it is a session that may do exactly one thing,
       * and putting the screen behind the portal guard would let the dashboard load
       * first with a staff-known password still live.
       */
      { path: PUBLIC_PATHS.changeTemporaryPassword, element: <NewPasswordPage /> },
      /*
       * NO LONGER A PLACEHOLDER. This said the service was not available online and to
       * telephone the bank — on the one page somebody reaches when they are already
       * locked out, for the commonest reason anybody contacts a bank about internet
       * banking. It now takes the request and puts it in front of a manager; there is
       * still no self-service reset link, deliberately (backend V15).
       */
      { path: PUBLIC_PATHS.forgotPassword, element: <ForgotPasswordPage /> },
      {
        path: PUBLIC_PATHS.resetPassword,
        element: placeholder('Reset password', 'Phase 3', 'section 5.3'),
      },
    ],
  },
];

/* ------------------------------------------------------------------ lazy groups */

const accountsChunk = () => import('@/features/accounts');
const moneyChunk = () => import('@/features/money');
const productsChunk = () => import('@/features/products');
const corporateChunk = () => import('@/features/corporate');
const accountChunk = () => import('@/features/account');

const accountsRoutes: RouteObject[] = [
  {
    path: RETAIL_PATHS.accounts,
    lazy: async () => ({ Component: (await accountsChunk()).AccountsPage }),
  },
  {
    path: RETAIL_PATHS.accountDetail,
    lazy: async () => ({ Component: (await accountsChunk()).AccountDetailPage }),
  },
  {
    path: RETAIL_PATHS.accountTransactions,
    lazy: async () => ({ Component: (await accountsChunk()).TransactionsPage }),
  },
  {
    path: RETAIL_PATHS.accountStatementNew,
    lazy: async () => ({ Component: (await accountsChunk()).StatementRequestPage }),
  },
  {
    path: RETAIL_PATHS.statements,
    lazy: async () => ({ Component: (await accountsChunk()).StatementsPage }),
  },
];

/*
 * THE FOUR TRANSFER RAILS NO LONGER SHARE A COMPONENT, so the table that generated them
 * is gone.
 *
 * It listed OWN, INTERNAL, EXTERNAL and INTERNATIONAL and handed each to TransferPage
 * with a different prop, which was right while all four were answered by the same mock.
 * Two of them now go to the real service and two of them refuse, so they are written out
 * below — four routes rather than a loop over a shape they no longer have in common.
 *
 * Payment routes still share BILL_KINDS, which is unchanged.
 */

const BILL_KINDS = [
  {
    path: RETAIL_PATHS.paymentWallet,
    category: 'WALLET',
    title: 'Send to a mobile wallet',
    lead: "Money arrives on the recipient's phone within minutes.",
  },
  {
    path: RETAIL_PATHS.paymentAirtime,
    category: 'AIRTIME',
    title: 'Buy airtime',
    lead: 'Top up any number, instantly.',
  },
  {
    path: RETAIL_PATHS.paymentElectricity,
    category: 'ELECTRICITY',
    title: 'Pay for electricity',
    lead: 'Your token is sent by SMS to the number on your profile.',
  },
  {
    path: RETAIL_PATHS.paymentWater,
    category: 'WATER',
    title: 'Pay your water bill',
    lead: 'WASAC, by customer number.',
  },
  {
    path: RETAIL_PATHS.paymentTv,
    category: 'TV',
    title: 'Pay a TV subscription',
    lead: 'Canal+ and StarTimes. Reconnection can take a few minutes.',
  },
  {
    path: RETAIL_PATHS.paymentTax,
    category: 'TAX',
    title: 'Pay tax',
    lead: 'RRA declarations. The amount comes from the declaration, not from you.',
  },
  {
    path: RETAIL_PATHS.paymentAfos,
    category: 'AFOS',
    title: 'Pay AFOS',
    lead: 'By member number.',
  },
] as const;

const TRANSFER_CHOICES = [
  {
    label: 'Between my own accounts',
    note: 'Instant and free.',
    to: RETAIL_PATHS.transferOwn,
    anyOf: ['TRANSFER_CREATE', 'CORPORATE_TRANSFER_CREATE'],
  },
  {
    label: 'To someone at this bank',
    note: 'Arrives within minutes.',
    to: RETAIL_PATHS.transferInternal,
    anyOf: ['TRANSFER_CREATE', 'CORPORATE_TRANSFER_CREATE'],
  },
  {
    label: 'To another bank in Rwanda',
    note: 'Usually same day. A fee applies.',
    to: RETAIL_PATHS.transferExternal,
    anyOf: ['TRANSFER_CREATE', 'CORPORATE_TRANSFER_CREATE'],
  },
  {
    label: 'Abroad',
    note: 'Up to three working days. Fees and rate quoted before you confirm.',
    to: RETAIL_PATHS.transferInternational,
    anyOf: ['TRANSFER_CREATE', 'CORPORATE_TRANSFER_CREATE'],
  },
] as const;

const PAYMENT_CHOICES = BILL_KINDS.map((bill) => ({
  label: bill.title.replace(/^(Pay|Send to|Buy)\s+(a\s+|your\s+)?/, ''),
  note: bill.lead,
  to: bill.path,
  anyOf: ['PAYMENT_CREATE', 'CORPORATE_PAYMENT_CREATE'] as const,
}));

const moneyRoutes: RouteObject[] = [
  {
    path: RETAIL_PATHS.transfers,
    lazy: async () => {
      const { HubPage } = await moneyChunk();
      return {
        Component: () => (
          <HubPage title="Transfers" lead="Where is the money going?" choices={TRANSFER_CHOICES} />
        ),
      };
    },
  },

  /*
   * OWN and INTERNAL go to the REAL service; the two outbound rails do not exist.
   *
   * SendMoneyPage speaks the API that is implemented: one rail inside Zigama, no fee, no
   * quote, and a destination that is either one of the customer's own accounts or an
   * account number they typed. TransferPage speaks the four-rail shape with a quote id
   * and a saved payee, which only the mocks ever answered — pointing it at the service
   * would send a quote id the server has never issued.
   *
   * Both own-account and someone-else transfers are the same screen, because they differ
   * only in which destination field appears.
   */
  {
    path: RETAIL_PATHS.transferOwn,
    lazy: async () => {
      const { SendMoneyPage } = await moneyChunk();
      return { Component: SendMoneyPage };
    },
  },
  {
    path: RETAIL_PATHS.transferInternal,
    lazy: async () => {
      const { SendMoneyPage } = await moneyChunk();
      return { Component: SendMoneyPage };
    },
  },

  /*
   * Sending outside Zigama is NOT AVAILABLE, and now says so.
   *
   * These two were answered by MSW and finished on "Done — the money is on its way" for
   * money that went nowhere. That is the false receipt this codebase has already had to
   * unpick twice: an error sends somebody to a branch, a receipt sends them home to wait.
   * There is no rail to send on, so the honest screen is one that refuses.
   */
  ...(['transferExternal', 'transferInternational'] as const).map<RouteObject>((rail) => ({
    path: RETAIL_PATHS[rail],
    lazy: async () => {
      const { OutboundNotAvailablePage } = await moneyChunk();
      return {
        Component: () => (
          <OutboundNotAvailablePage
            kind={rail === 'transferExternal' ? 'EXTERNAL' : 'INTERNATIONAL'}
          />
        ),
      };
    },
  })),

  {
    path: RETAIL_PATHS.payments,
    lazy: async () => {
      const { HubPage } = await moneyChunk();
      return {
        Component: () => (
          <HubPage title="Payments" lead="What are you paying?" choices={PAYMENT_CHOICES} />
        ),
      };
    },
  },

  ...BILL_KINDS.map<RouteObject>((bill) => ({
    path: bill.path,
    lazy: async () => {
      const { PaymentPage } = await moneyChunk();
      return {
        Component: () => (
          <PaymentPage category={bill.category} title={bill.title} lead={bill.lead} />
        ),
      };
    },
  })),

  {
    path: RETAIL_PATHS.standingOrders,
    lazy: async () => ({ Component: (await moneyChunk()).StandingOrdersPage }),
  },
  {
    path: RETAIL_PATHS.standingOrderNew,
    lazy: async () => ({ Component: (await moneyChunk()).StandingOrderNewPage }),
  },
  {
    path: RETAIL_PATHS.standingOrderDetail,
    lazy: async () => ({ Component: (await moneyChunk()).StandingOrderDetailPage }),
  },

  { path: RETAIL_PATHS.fx, lazy: async () => ({ Component: (await moneyChunk()).FxPage }) },
  { path: RETAIL_PATHS.fxReview, lazy: async () => ({ Component: (await moneyChunk()).FxPage }) },
];

const productRoutes: RouteObject[] = [
  {
    path: RETAIL_PATHS.beneficiaries,
    lazy: async () => ({ Component: (await productsChunk()).BeneficiariesPage }),
  },
  {
    path: RETAIL_PATHS.beneficiaryNew,
    lazy: async () => ({ Component: (await productsChunk()).BeneficiaryNewPage }),
  },

  {
    path: RETAIL_PATHS.loans,
    lazy: async () => ({ Component: (await productsChunk()).LoansPage }),
  },
  {
    path: RETAIL_PATHS.loanSimulation,
    lazy: async () => ({ Component: (await productsChunk()).LoanSimulationPage }),
  },
  {
    path: RETAIL_PATHS.loanRequest,
    lazy: async () => ({ Component: (await productsChunk()).LoanRequestPage }),
  },
  {
    path: RETAIL_PATHS.loanDetail,
    lazy: async () => ({ Component: (await productsChunk()).LoanDetailPage }),
  },
  {
    path: RETAIL_PATHS.loanStatements,
    element: placeholder('Loan statement', 'Phase 19', 'section 14.5'),
  },

  {
    path: RETAIL_PATHS.cards,
    lazy: async () => ({ Component: (await productsChunk()).CardsPage }),
  },
  {
    path: RETAIL_PATHS.cardRequest,
    lazy: async () => ({ Component: (await productsChunk()).CardRequestPage }),
  },
  {
    path: RETAIL_PATHS.cardRequestStatus,
    element: placeholder('Card request status', 'Phase 20', 'section 15.3'),
  },
  {
    path: RETAIL_PATHS.cardBlock,
    lazy: async () => ({ Component: (await productsChunk()).CardBlockPage }),
  },

  {
    path: RETAIL_PATHS.chequeBooks,
    lazy: async () => ({ Component: (await productsChunk()).ChequeBooksPage }),
  },
  {
    path: RETAIL_PATHS.chequeBookNew,
    lazy: async () => ({ Component: (await productsChunk()).ChequeBookNewPage }),
  },
];

const accountRoutes: RouteObject[] = [
  {
    path: RETAIL_PATHS.profile,
    lazy: async () => ({ Component: (await accountChunk()).ProfilePage }),
  },
  {
    path: RETAIL_PATHS.security,
    lazy: async () => ({ Component: (await accountChunk()).SecurityPage }),
  },
  {
    path: RETAIL_PATHS.notifications,
    lazy: async () => ({ Component: (await accountChunk()).NotificationsPage }),
  },
  { path: RETAIL_PATHS.help, lazy: async () => ({ Component: (await accountChunk()).HelpPage }) },
];

const corporateRoutes: RouteObject[] = [
  { path: CORPORATE_PATHS.dashboard, element: <CorporateDashboardPage /> },
  {
    path: CORPORATE_PATHS.approvals,
    lazy: async () => ({ Component: (await corporateChunk()).ApprovalsPage }),
  },
  {
    path: CORPORATE_PATHS.approvalDetail,
    lazy: async () => ({ Component: (await corporateChunk()).ApprovalDetailPage }),
  },
  {
    path: CORPORATE_PATHS.bulk,
    lazy: async () => {
      const { BulkListPage } = await corporateChunk();
      return { Component: () => <BulkListPage salaryOnly={false} /> };
    },
  },
  {
    path: CORPORATE_PATHS.bulkNew,
    lazy: async () => ({ Component: (await corporateChunk()).BulkNewPage }),
  },
  {
    path: CORPORATE_PATHS.bulkValidation,
    element: placeholder('Batch validation', 'Phase 23', 'section 18.3'),
  },
  {
    path: CORPORATE_PATHS.bulkReview,
    element: placeholder('Batch review', 'Phase 23', 'section 18.4'),
  },
  {
    path: CORPORATE_PATHS.bulkDetail,
    lazy: async () => ({ Component: (await corporateChunk()).BulkDetailPage }),
  },
  {
    path: CORPORATE_PATHS.salary,
    lazy: async () => {
      const { BulkListPage } = await corporateChunk();
      return { Component: () => <BulkListPage salaryOnly /> };
    },
  },
];

/**
 * The bank's own portal.
 *
 * Its own layout, its own session and its own chunk. A customer's browser never
 * downloads the staff screens, and the staff tree sits outside `ProtectedRoute`
 * entirely because it is guarded by the staff session, not the customer one.
 */
const staffChunk = () => import('@/features/staff');

const staffRoutes: RouteObject[] = [
  {
    path: STAFF_PATHS.login,
    lazy: async () => ({ Component: (await staffChunk()).StaffLoginPage }),
  },
  {
    lazy: async () => ({ Component: (await staffChunk()).StaffLayout }),
    children: [
      {
        path: STAFF_PATHS.dashboard,
        lazy: async () => ({ Component: (await staffChunk()).StaffDashboardPage }),
      },
      {
        path: STAFF_PATHS.applications,
        lazy: async () => ({ Component: (await staffChunk()).ApplicationsPage }),
      },
      {
        path: STAFF_PATHS.applicationDetail,
        lazy: async () => ({ Component: (await staffChunk()).ApplicationDetailPage }),
      },
      {
        path: STAFF_PATHS.approvals,
        lazy: async () => ({ Component: (await staffChunk()).ApprovalsPage }),
      },
      {
        path: STAFF_PATHS.transferQueue,
        lazy: async () => ({ Component: (await staffChunk()).TransferQueuePage }),
      },
      {
        path: STAFF_PATHS.beneficiaryReviews,
        lazy: async () => ({ Component: (await staffChunk()).BeneficiaryReviewsPage }),
      },
      {
        path: STAFF_PATHS.serviceRequests,
        lazy: async () => ({ Component: (await staffChunk()).ServiceRequestsPage }),
      },
      {
        path: STAFF_PATHS.passwordRequests,
        lazy: async () => ({ Component: (await staffChunk()).PasswordRequestsPage }),
      },
      {
        path: STAFF_PATHS.customers,
        lazy: async () => ({ Component: (await staffChunk()).StaffCustomersPage }),
      },
      /*
       * THE DEVELOPER TOOLS ROUTE ONLY EXISTS WHEN THE BUILD ASKS FOR IT.
       *
       * <p>Spread conditionally rather than rendered-then-hidden, and that is the whole
       * point: `appConfig.enableDevTools` resolves from an inlined `import.meta.env` value,
       * so with the flag off this array entry is dead code and the bundler never emits the
       * page or the reset client.
       *
       * <p>IT WAS NOT LIKE THIS AN HOUR AGO, and the comment in `devReset.ts` claimed
       * otherwise. The route was registered unconditionally and only the navigation link
       * was hidden, so a production build emitted `devReset-*.js` containing the literal
       * `/admin/dev/reset` path — checked by building it and reading the chunk. Hiding a
       * link is not removing a page.
       */
      /*
       * `import.meta.env.VITE_ENABLE_DEV_TOOLS` DIRECTLY, not `appConfig.enableDevTools`.
       *
       * This is the line that actually removes the page, and the first two attempts at it
       * failed. Vite inlines `import.meta.env.X` as a literal in the source, so this
       * comparison folds to a constant and the whole branch becomes dead code. `appConfig`
       * is an object built at module load — a runtime value the bundler cannot fold, so
       * gating on it kept the dynamic `import()` reachable and the chunk was still emitted.
       * Confirmed by building both ways and grepping dist for `/admin/dev/reset`.
       */
      ...(import.meta.env.VITE_ENABLE_DEV_TOOLS === 'true'
        ? [
            {
              path: STAFF_PATHS.developerTools,
              /*
               * BY PATH, NOT THROUGH `staffChunk()`. The barrel is loaded as a namespace,
               * which retains all of its exports — going through it would put this page in
               * the staff chunk even with the flag off. See the note in features/staff.
               */
              lazy: async () => ({
                Component: (await import('@/features/staff/StaffDeveloperToolsPage'))
                  .StaffDeveloperToolsPage,
              }),
            },
          ]
        : []),
    ],
  },
];

export const routes: RouteObject[] = [
  ...marketingRoutes,
  ...publicRoutes,
  ...staffRoutes,
  {
    element: <ProtectedRoute />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { path: RETAIL_PATHS.dashboard, element: <RetailDashboardPage /> },
          ...accountsRoutes,
          ...moneyRoutes,
          ...productRoutes,
          ...accountRoutes,
          ...corporateRoutes,
          { path: DIAGNOSTIC_PATHS.systemStatus, element: <SystemStatusPage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
];

export const router = createBrowserRouter(routes);

/**
 * Inputs that force a particular mocked server response.
 *
 * Deliberately a standalone module with NO msw import. `RegistrationShell` displays these
 * in development, and importing them from `registrationHandlers.ts` pulled the whole of
 * MSW into the production bundle — roughly 360 kB of request-interception code shipped to
 * customers who will never run a mock.
 *
 * Anything app code needs from this directory belongs here, not beside the handlers.
 */
export const TEST_TRIGGERS = {
  accountNotFound: '0000000000',
  accountRateLimited: '1111111111',
  serverError: '9999999999',
  otpInvalid: '000000',
  otpExpired: '111111',
  otpTooManyAttempts: '222222',
  companyCodeUnknown: 'NOSUCHCO',
  companyCodeRejected: 'REJECTED',
  /*
   * There was a `duplicateTin` trigger here that forced a 409 on business registration.
   * The handler no longer refuses a duplicate — doing so confirmed to an anonymous
   * visitor that a given TIN banks here. Duplicates are now resolved by staff from the
   * applications queue, so there is no error state left to demonstrate.
   */
} as const;

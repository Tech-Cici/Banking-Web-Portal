export { ApplicationDetailPage, ApplicationsPage } from './ApplicationsPage';
export { BeneficiaryReviewsPage } from './BeneficiaryReviewsPage';
export { ApprovalsPage, StaffCustomersPage } from './ApprovalsPage';
export { StaffDashboardPage } from './StaffDashboardPage';
/*
 * StaffDeveloperToolsPage is DELIBERATELY NOT EXPORTED HERE.
 *
 * `router.tsx` loads this barrel as a namespace (`await import('@/features/staff')`), and a
 * namespace import retains every export — so a page re-exported from here is in the staff
 * chunk whether or not any route references it. Measured, not assumed: with the page in
 * this file, a production build with VITE_ENABLE_DEV_TOOLS off still emitted
 * `devReset-*.js` containing the literal `/admin/dev/reset` path, because the barrel kept
 * the page alive and the page kept its dynamic import alive.
 *
 * The route imports the module by path instead, inside a conditional, so with the flag off
 * nothing references it and both chunks disappear.
 */
export { PasswordRequestsPage } from './PasswordRequestsPage';
export { ServiceRequestsPage } from './ServiceRequestsPage';
export { StaffLayout } from './StaffLayout';
export { StaffLoginPage } from './StaffLoginPage';
export { TransferQueuePage } from './TransferQueuePage';
export { useStaffSession } from './useStaffSession';

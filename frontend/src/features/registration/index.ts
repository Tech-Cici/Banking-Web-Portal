/**
 * Registration feature.
 *
 * NOT DEFINED IN THE BLUEPRINT beyond section 5.1's "Optional: Register/Activate Internet
 * Banking if onboarding is enabled". The flows implement the model agreed with the client:
 * personal registration activates an existing account, business registration is an
 * application the bank reviews, and joining a business needs a company code plus approval
 * from that company's administrator.
 */
export { BusinessRegistrationPage } from './BusinessRegistrationPage';
export { JoinBusinessPage } from './JoinBusinessPage';
export { PersonalRegistrationPage } from './PersonalRegistrationPage';

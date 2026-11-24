package rw.bank.ibanking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Registration settings, bound from {@code ibanking.registration.*}.
 *
 * <p>Bound rather than read ad hoc for the same reason {@link OutboundMailProperties} is: a
 * misspelled key would otherwise leave the value at its default without anybody being told,
 * and the default here decides whether a security control is on.
 *
 * @param verifyContact whether an applicant must prove they can read the contact address
 *     they gave before their application reaches the staff queue.
 *     <p>DEFAULTS TO TRUE, which is the opposite of the mail setting's default and is
 *     deliberate. {@code ibanking.mail.enabled} defaults to false because adding a mail
 *     dependency must not start sending on a machine with no SMTP configured -- the safe
 *     default there is to do less. Here the safe default is to do MORE: a deployment that
 *     silently stopped verifying contact details would hand the staff queue to anybody, and
 *     would do it quietly. Switching it off is therefore something an environment has to
 *     say out loud.
 *     <p>It is switched off in this project today because verification is moving from email
 *     to SMS and no SMS provider is funded yet. Nothing about the flow is deleted: the
 *     codes, the attempt counter and the rate limit all still exist, and setting this back
 *     to true restores them.
 *     <p>WHAT IT COSTS WHILE OFF. The contact address on an application is unproven, so the
 *     credentials a manager later sends can reach somebody who is not the applicant -- a
 *     mistyped address is enough. Nothing downstream can catch it either: the CBS record
 *     holds a telephone number, not an email address, so there is nothing to compare
 *     against. The manager approving the application is the only check, and the screens
 *     that say these details are unverified have to keep saying so.
 */
@ConfigurationProperties(prefix = "ibanking.registration")
public record RegistrationProperties(Boolean verifyContact) {

    public RegistrationProperties {
        verifyContact = verifyContact == null || verifyContact;
    }
}

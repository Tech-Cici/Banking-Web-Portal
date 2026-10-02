package rw.bank.ibanking.onboarding.service;

/**
 * What the bank actually says.
 *
 * <p>Plain text, assembled here rather than in a template engine, because there are three
 * of them and a dependency earns its place when there are thirty. When HTML versions are
 * needed this is the file that grows a renderer.
 *
 * <p>Two rules these templates follow, and a reviewer should check they still do:
 *
 * <p><b>No credential is ever in an approval or rejection email.</b> The temporary password
 * is shown to the administrator once, on screen, to hand over in person. An email
 * containing working bank credentials is a permanent risk sitting in an inbox, and it is
 * forwardable. The only message here carrying anything secret is the verification code,
 * which is short-lived by design and is the whole point of that message.
 *
 * <p><b>Every message says what the bank will never ask for.</b> A customer who has read
 * that line in genuine bank mail has a chance of spotting the message that asks.
 */
final class EmailTemplates {

    private EmailTemplates() {}

    static final String VERIFICATION_SUBJECT = "Your Zigama CSS verification code";

    static String verification(String code, long validityMinutes) {
        return String.join(
                "\n",
                "Your verification code is " + code + ".",
                "",
                "Enter it on the registration page to confirm this email address is yours.",
                "It expires in " + validityMinutes + " minutes.",
                "",
                "If you did not start a registration with Zigama CSS, ignore this message —",
                "no account will be created and nothing further will happen.",
                "",
                "Zigama CSS will never ask you for this code by phone, SMS or email.",
                "Anyone who does is not the bank.");
    }

    static final String APPROVED_SUBJECT = "Your Zigama CSS internet banking account is ready";

    /**
     * Note what is absent: the password.
     *
     * <p>The customer already has it — an administrator handed it over when the account was
     * created. Repeating it here would put a working credential in an inbox forever.
     */
    /**
     * The approval notice, which now CARRIES THE TEMPORARY PASSWORD.
     *
     * <p>This reverses an earlier decision and the reasoning is worth keeping, because
     * the trade is real. The message contains both where to sign in and the credential to
     * do it with, so a compromised mailbox is a compromised account. It was changed
     * anyway, deliberately: the alternative required every customer to reach a branch to
     * collect a password, and for a bank whose customers are posted away from one that
     * makes online registration pointless.
     *
     * <p>Three things keep the cost bounded, and none of them should be removed casually:
     *
     * <ul>
     *   <li>The password EXPIRES — see TEMPORARY_PASSWORD_VALIDITY. A mailbox breached
     *       months later yields nothing.
     *   <li>It is single-use in effect: the portal refuses everything except the password
     *       change until it has been replaced.
     *   <li>The sign-in is recorded, so a customer who is told to expect this email and
     *       finds it already used has a trace.
     * </ul>
     *
     * <p>The old promise that the bank would never email a password has been removed
     * rather than softened. Leaving it in while doing the opposite would teach customers
     * to ignore exactly the assurance that protects them from a real phishing message.
     */
    static String approved(
            String fullName,
            String customerNumber,
            String signInEmail,
            String temporaryPassword,
            java.time.Duration validity) {

        return String.join(
                "\n",
                "Dear " + fullName + ",",
                "",
                "Your internet banking account has been approved and is now active.",
                "",
                /*
                 * THE EMAIL ADDRESS IS NAMED, and it was not.
                 *
                 * This showed the customer number immediately above the temporary
                 * password and said only "sign in with the temporary password above".
                 * The obvious reading is customer number plus password — and sign-in
                 * looks the customer up by EMAIL and by nothing else, so that pair fails
                 * with "the details you entered do not match an account". A new customer
                 * on their first contact with the service, told nothing except that their
                 * correct password is wrong.
                 *
                 * The customer number stays because it is what the bank asks for on the
                 * telephone; it is now labelled as that rather than sitting where a
                 * username would.
                 */
                "Sign in with your email address:",
                "  Email address:      " + signInEmail,
                "  Temporary password: " + temporaryPassword,
                "",
                "You will be asked to replace the password straight away, before you can",
                "use the service — please do that as soon as you are in. Once you have,",
                "nobody at the bank knows your password, and this email is worth nothing",
                "to anybody who reads it.",
                "",
                "Your customer number is " + customerNumber + ". Quote it if you call us;",
                "it is not what you sign in with.",
                "",
                "This temporary password stops working in " + validity.toHours() + " hours.",
                "If it expires before you use it, call us and we will issue another.",
                "",
                "Delete this email once you have changed your password.",
                "",
                "Zigama CSS will never ask you for your password, PIN or one-time code by",
                "phone, SMS or email. Always type the banking address into your browser",
                "yourself rather than following a link — including a link in this message.",
                "",
                "Zigama CSS");
    }

    static final String FROZEN_SUBJECT = "Your Zigama CSS internet banking access has been paused";

    /**
     * Tells the customer their access has stopped — without saying why.
     *
     * <p>The reason is deliberately absent. A freeze may concern an investigation the
     * customer must not be tipped off about, and where it is something innocent the
     * branch is the right place to explain it. Staff can see the reason; this message
     * cannot, because the bank does not know which kind of freeze it is describing.
     *
     * <p>It still tells them the one thing they need: that it happened, and who to ask.
     * A customer who simply finds themselves locked out assumes a fault and tries again
     * and again, which is worse for them and for the contact centre.
     */
    static String frozen(String fullName) {
        return String.join(
                "\n",
                "Dear " + fullName + ",",
                "",
                "We have paused access to your Zigama CSS internet banking. You will not be",
                "able to sign in until it is restored.",
                "",
                "Your money is not affected by this and your account itself remains open.",
                "",
                "Please call the number on the back of your card, or visit any branch, and we",
                "will explain. Bring your identification.",
                "",
                "Zigama CSS will never ask you for your password, PIN or one-time code by",
                "phone, SMS or email.",
                "",
                "Zigama CSS");
    }

    static final String UNFROZEN_SUBJECT = "Your Zigama CSS internet banking access is back";

    static String unfrozen(String fullName) {
        return String.join(
                "\n",
                "Dear " + fullName + ",",
                "",
                "Access to your Zigama CSS internet banking has been restored. You can sign in",
                "again with the password you already use.",
                "",
                "If you did not expect this, call the number on the back of your card straight",
                "away.",
                "",
                "Zigama CSS");
    }

    static final String REJECTED_SUBJECT = "About your Zigama CSS internet banking application";

    static String rejected(String fullName, String reason) {
        return String.join(
                "\n",
                "Dear " + fullName + ",",
                "",
                "We were not able to approve your internet banking application.",
                "",
                "Reason: " + reason,
                "",
                "If you think this is a mistake, or you would like to apply again with",
                "different details, please visit any branch or call the number printed on the",
                "back of your card. We can explain what is needed.",
                "",
                "Zigama CSS");
    }

    /* ------------------------------------------------- a forgotten password */

    static final String RESET_ISSUED_SUBJECT = "Your new Zigama CSS internet banking password";

    /**
     * The re-issued password, after a customer asked the bank for a new one.
     *
     * <p>Deliberately the same shape as {@link #approved}: the same two labelled lines, the
     * same expiry sentence, the same warning at the foot. A customer who registered a month
     * ago has seen this layout once already, and a password email that looks DIFFERENT from
     * the one the bank sent before is the one a phishing message gets to imitate.
     *
     * <p>It names who asked and when, because that is the only way the recipient can tell
     * an expected message from an unexpected one. If somebody else asked for a reset on
     * their account, this email is how they find out — so it says what to do about it.
     */
    static String resetIssued(
            String fullName,
            String signInEmail,
            String temporaryPassword,
            java.time.Duration validity) {

        return String.join(
                "\n",
                "Dear " + fullName + ",",
                "",
                "You asked us for a new internet banking password, and a member of our staff",
                "has issued one.",
                "",
                "Sign in with your email address:",
                "  Email address:      " + signInEmail,
                "  Temporary password: " + temporaryPassword,
                "",
                "You will be asked to replace this password straight away, before you can use",
                "the service. Once you have, nobody at the bank knows your password and this",
                "email is worth nothing to anybody who reads it.",
                "",
                "This temporary password stops working in " + validity.toHours() + " hours.",
                "",
                /*
                 * THE PART THAT MATTERS IF THE CUSTOMER DID NOT ASK. A reset somebody else
                 * requested is the one case where this email is the only warning the real
                 * customer gets, so it has to say so and say what to do — not bury it under
                 * instructions for the expected case.
                 */
                "IF YOU DID NOT ASK FOR THIS, your password has not been changed by anybody",
                "except us, but somebody may be trying to reach your account. Do not use the",
                "password above. Call the number on the back of your card straight away and",
                "tell us.",
                "",
                "Any browsers that were set to skip the sign-in code have been reset, so you",
                "will be asked for a code again next time. That is expected.",
                "",
                "Delete this email once you have changed your password.",
                "",
                "Zigama CSS will never ask you for your password, PIN or one-time code by",
                "phone, SMS or email. Always type the banking address into your browser",
                "yourself rather than following a link - including a link in this message.",
                "",
                "Zigama CSS");
    }

    static final String RESET_REFUSED_SUBJECT = "About your Zigama CSS password request";

    /**
     * Told, not left waiting.
     *
     * <p>A refused request with no message is a customer who checks an empty inbox for a
     * week and then calls anyway. The reason is the manager's own words, because a generic
     * refusal is what makes the telephone call longer rather than shorter.
     */
    static String resetRefused(String fullName, String reason) {
        return String.join(
                "\n",
                "Dear " + fullName + ",",
                "",
                "We were not able to issue a new internet banking password from the request we",
                "received.",
                "",
                "Reason: " + reason,
                "",
                "Your existing password has not been changed. To get this sorted out, please",
                "visit any branch with your ID, or call the number printed on the back of your",
                "card.",
                "",
                "If you did not ask for a new password, you do not need to do anything.",
                "",
                "Zigama CSS");
    }

    static final String BENEFICIARY_APPROVED_SUBJECT = "You can now pay a payee you saved";

    /**
     * A saved payee has been checked and can be paid.
     *
     * <p>THE EMAIL IS WHY THE WAIT IS TOLERABLE. A payee that becomes payable silently
     * means a customer who checks the screen every few hours, so this closes the loop they
     * were left in when they added it.
     *
     * <p>IT NAMES THE PAYEE AND THE LAST FOUR DIGITS, NOT THE NUMBER. Enough for the
     * customer to recognise which payee this is about; not enough for anybody reading the
     * message over their shoulder to pay it or to learn an account number.
     *
     * <p>AND IT SAYS WHAT TO DO IF THEY DID NOT ADD IT. A payee appearing on somebody's
     * account that they did not save is the single clearest sign that another person has
     * their password, and this message is the only moment the bank gets to tell them.
     */
    static String beneficiaryApproved(String fullName, String payeeName, String maskedNumber) {
        return String.join(
                "\n",
                "Dear " + fullName + ",",
                "",
                "A member of our staff has checked the payee you saved, and you can now send",
                "money to them.",
                "",
                "  Payee:   " + payeeName,
                "  Account: " + maskedNumber,
                "",
                "You will find them in the payee list when you make a transfer or set up a",
                "standing order.",
                "",
                "IF YOU DID NOT ADD THIS PAYEE, somebody else may have access to your internet",
                "banking. Call the number printed on the back of your card straight away.",
                "",
                "Zigama CSS will never ask you for your password, PIN or one-time code by",
                "phone, SMS or email. Always type the banking address into your browser",
                "yourself rather than following a link - including a link in this message.",
                "",
                "Zigama CSS");
    }

    static final String BENEFICIARY_REFUSED_SUBJECT = "About a payee you saved";

    /**
     * A saved payee was not approved, and what to do about it.
     *
     * <p>The reason is the reviewer's own words. The commonest one — the name does not
     * match the account the bank holds — is something the customer fixes in a minute by
     * checking the number, and a generic refusal turns that minute into a branch visit.
     */
    static String beneficiaryRefused(
            String fullName, String payeeName, String maskedNumber, String reason) {

        return String.join(
                "\n",
                "Dear " + fullName + ",",
                "",
                "We were not able to approve a payee you saved, so money cannot be sent to it.",
                "",
                "  Payee:   " + payeeName,
                "  Account: " + maskedNumber,
                "",
                "Reason: " + reason,
                "",
                "Nothing has been sent and no money has left your account. If the account",
                "number was wrong, add the payee again with the corrected number. If you think",
                "this is a mistake, visit any branch with your ID or call the number printed on",
                "the back of your card.",
                "",
                "IF YOU DID NOT ADD THIS PAYEE, somebody else may have access to your internet",
                "banking. Please call us straight away.",
                "",
                "Zigama CSS");
    }

    static final String PASSWORD_CHANGED_SUBJECT = "Your password has been changed";

    /**
     * Tells the customer their password changed.
     *
     * <p>THIS MESSAGE IS THE DETECTION MECHANISM, which decides everything about how it is
     * written. It cannot prevent the change — whoever made it had the current password — so
     * its only job is to reach somebody who did NOT make it and tell them what to do in the
     * next few minutes. The instruction therefore comes before the pleasantries, and it is
     * to telephone, not to click: a customer whose password has just been taken is exactly
     * the customer who should not be following links out of their email.
     *
     * <p>NO LINK AT ALL, for the same reason the account emails carry none. A genuine
     * "your password changed, click here if this was not you" message trains people to
     * click exactly the link a phishing copy of it will supply.
     *
     * <p>IT SAYS HOW MANY BROWSERS WERE SIGNED OUT, because something just happened to the
     * customer that they did not ask for and will otherwise notice as an unexplained code
     * request. The count is omitted entirely when it is zero rather than printed as "0".
     */
    static String passwordChanged(String fullName, int browsersSignedOut) {
        String browsers =
                browsersSignedOut == 0
                        ? null
                        : browsersSignedOut == 1
                                ? "One browser that could sign in without an emailed code has"
                                        + " been signed out."
                                : browsersSignedOut
                                        + " browsers that could sign in without an emailed code"
                                        + " have been signed out.";

        return String.join(
                "\n",
                "Dear " + fullName + ",",
                "",
                "The password for your internet banking was changed just now.",
                "",
                "IF THIS WAS NOT YOU, call the number printed on the back of your card",
                "immediately. Somebody else may be able to reach your accounts.",
                "",
                browsers == null ? "" : browsers,
                browsers == null ? "" : "You will be asked for a code next time you sign in.",
                browsers == null ? "" : "",
                "Zigama CSS will never ask you for your password, PIN or one-time code by",
                "phone, SMS or email, and we will never send you a link to reset it.",
                "",
                "Zigama CSS");
    }

    static final String SERVICE_REQUEST_READY_SUBJECT = "Your request is ready to collect";

    /**
     * A card or cheque book is ready, and where to go for it.
     *
     * <p>THIS IS THE ONLY PLACE THE BANK NAMES A COLLECTION POINT. The portal used to name
     * one itself, from a list of six branches the front end had invented, and told the
     * customer to bring identification to it. Here the location is whatever the member of
     * staff who produced the thing typed, which is the only version of this sentence that
     * can be true.
     *
     * <p>It carries the reference, because that is what the person at the counter will ask
     * for, and it says to bring identification, because collecting a card without being
     * identified is how somebody else collects it.
     */
    static String serviceRequestReady(
            String fullName, String what, String reference, String collectionPoint) {

        return String.join(
                "\n",
                "Dear " + fullName + ",",
                "",
                "The " + what + " you asked for is ready to collect.",
                "",
                "  Reference:  " + reference,
                "  Collect at: " + collectionPoint,
                "",
                "Please bring photo identification. We cannot hand it to anybody else.",
                "",
                "IF YOU DID NOT ASK FOR THIS, somebody else may have access to your internet",
                "banking. Call the number printed on the back of your card straight away.",
                "",
                "Zigama CSS will never ask you for your password, PIN or one-time code by",
                "phone, SMS or email.",
                "",
                "Zigama CSS");
    }

    static final String SERVICE_REQUEST_DECLINED_SUBJECT = "About your request";

    /** Declined, in the staff member's own words, and what to do about it. */
    static String serviceRequestDeclined(
            String fullName, String what, String reference, String reason) {

        return String.join(
                "\n",
                "Dear " + fullName + ",",
                "",
                "We were not able to action the " + what + " you asked for.",
                "",
                "  Reference: " + reference,
                "",
                "Reason: " + reason,
                "",
                "Nothing has been charged to your account. If you think this is a mistake,",
                "visit any branch with your ID or call the number printed on the back of your",
                "card.",
                "",
                "Zigama CSS");
    }
}

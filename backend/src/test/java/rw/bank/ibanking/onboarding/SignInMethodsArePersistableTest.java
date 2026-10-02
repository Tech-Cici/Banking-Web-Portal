package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import rw.bank.ibanking.onboarding.domain.SignInEntity;
import rw.bank.ibanking.onboarding.domain.SignInMethod;
import rw.bank.ibanking.onboarding.repo.SignInRepository;

/**
 * Every {@link SignInMethod} can actually be written to {@code sign_ins}.
 *
 * <p>THE SAME TEST AS {@link OutboxKindsArePersistableTest}, for the same reason, against a
 * different list. {@code SignInMethod} is persisted by name and {@code
 * sign_ins_method_known} lists the names the column accepts: two copies of one list, in two
 * languages, in two files. V5 is what happened last time they drifted — {@code
 * ACCOUNT_FROZEN} went into the enum and not into the constraint, so freezing an account
 * failed writing its notification inside the same transaction, rolled the suspension back,
 * and returned 500 for a feature that appeared merely broken.
 *
 * <p>WHAT IT WOULD COST HERE. Adding a method to the enum and not to V19's constraint would
 * make every sign-in through that path fail at the INSERT — which happens inside the
 * sign-in transaction, so the customer would be refused entry with a 500 while holding the
 * correct password. A sign-in path that cannot record itself is a sign-in path that does not
 * work.
 *
 * <p>Parameterised over {@code EnumSource} rather than a written-out list, because a
 * hand-kept list here would be a third copy of the same thing.
 */
@DisplayName("Sign-in methods are persistable")
class SignInMethodsArePersistableTest extends OnboardingIntegrationTest {

    @Autowired private SignInRepository signInRepository;

    @ParameterizedTest(name = "{0} can be recorded against a sign-in")
    @EnumSource(SignInMethod.class)
    void everyMethodSatisfiesTheCheckConstraint(SignInMethod method) throws Exception {
        /*
         * A CUSTOMER IS NEEDED. sign_ins.customer_id references customers(id), so a probe
         * row with a random id would fail on the foreign key rather than on the CHECK —
         * which means a drifted constraint would still fail this test, but for the wrong
         * reason and with an error naming the wrong thing.
         */
        customerWithAccounts(
                method.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')
                        + "@example.rw",
                """
                {"accounts":[{"accountNumber":"4009999999999","accountType":"CURRENT",\
                "currency":"RWF","openingBalance":"1000"}]}""");

        UUID customerId = customers.findAll().get(0).id();

        /*
         * saveAndFlush, not save. A CHECK is enforced when the INSERT reaches the
         * database, and save() may only queue it — which would let this pass while the
         * constraint still rejected the row, reproducing the exact bug it prevents.
         */
        SignInEntity saved =
                signInRepository.saveAndFlush(
                        new SignInEntity(customerId, method, "Written by a constraint probe"));

        assertThat(signInRepository.findById(saved.id()))
                .as("%s survived the round trip", method)
                .get()
                .extracting(SignInEntity::method)
                .isEqualTo(method);
    }
}

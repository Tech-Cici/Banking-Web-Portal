package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import rw.bank.ibanking.onboarding.domain.NotificationEntity;
import rw.bank.ibanking.onboarding.domain.NotificationKind;
import rw.bank.ibanking.onboarding.repo.NotificationRepository;

/**
 * Every {@link NotificationKind} can actually be written to {@code notifications}.
 *
 * <p>THE THIRD TEST OF THIS SHAPE, after {@link OutboxKindsArePersistableTest} and
 * {@code SignInMethodsArePersistableTest}, and the one with the sharpest consequence.
 *
 * <p>WHY. The enum is persisted by name and {@code notifications_kind_known} lists the names
 * the column accepts: two copies of one list, in two languages, in two files. V5 is what
 * happened last time they drifted — ACCOUNT_FROZEN went into {@code OutboxKind} and not into
 * its constraint, so freezing an account failed writing the notification inside the same
 * transaction, rolled the suspension back, and returned 500 for a feature that appeared
 * merely broken.
 *
 * <p>THIS TABLE REINTRODUCES THAT EXACT FAILURE MODE if the list drifts, because
 * {@code Notifications} deliberately writes inside the caller's transaction — a notification
 * must not survive an event that rolled back. So a kind missing from V20 would not merely
 * fail to notify: it would roll back releasing somebody's money, or marking their card
 * ready, and return 500 to the member of staff who pressed the button.
 *
 * <p>Parameterised over {@code EnumSource} rather than a written-out list, which would be a
 * third copy of the same thing.
 */
@DisplayName("Notification kinds are persistable")
class NotificationKindsArePersistableTest extends OnboardingIntegrationTest {

    @Autowired private NotificationRepository notificationRepository;

    @ParameterizedTest(name = "{0} can be written to notifications")
    @EnumSource(NotificationKind.class)
    void everyKindSatisfiesTheCheckConstraint(NotificationKind kind) throws Exception {
        /*
         * A CUSTOMER IS NEEDED: notifications.customer_id references customers(id), so a
         * probe row with a random id would fail on the foreign key rather than on the
         * CHECK — the test would still go red, but naming the wrong thing.
         */
        customerWithAccounts(
                kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-') + "@example.rw",
                """
                {"accounts":[{"accountNumber":"4009999999999","accountType":"CURRENT",\
                "currency":"RWF","openingBalance":"1000"}]}""");

        UUID customerId = customers.findAll().get(0).id();

        /*
         * saveAndFlush, not save. A CHECK is enforced when the INSERT reaches the database
         * and save() may only queue it — which would let this pass while the constraint
         * still rejected the row, reproducing the bug it exists to prevent.
         */
        NotificationEntity saved =
                notificationRepository.saveAndFlush(
                        NotificationEntity.raised(
                                customerId,
                                kind,
                                "Constraint probe",
                                "Written by NotificationKindsArePersistableTest.",
                                "/dashboard"));

        assertThat(notificationRepository.findById(saved.id()))
                .as("%s survived the round trip", kind)
                .get()
                .extracting(NotificationEntity::kind)
                .isEqualTo(kind);
    }
}

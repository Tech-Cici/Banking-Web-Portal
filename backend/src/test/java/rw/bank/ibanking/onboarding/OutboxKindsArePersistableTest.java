package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import rw.bank.ibanking.onboarding.domain.OutboxEntity;
import rw.bank.ibanking.onboarding.domain.OutboxKind;
import rw.bank.ibanking.onboarding.repo.OutboxRepository;

/**
 * Every {@link OutboxKind} can actually be written to the outbox table.
 *
 * <p>WHY THIS TEST EXISTS.
 *
 * <p>{@code OutboxKind} is persisted by name and {@code outbox_kind_check} lists the names
 * the column accepts. They are two copies of one list, in two languages, in two files, and
 * nothing connected them. Adding {@code ACCOUNT_FROZEN} and {@code ACCOUNT_UNFROZEN} to the
 * enum was therefore half a change: freezing an account suspended the customer, then failed
 * writing the notification in the same transaction, which rolled the suspension back and
 * returned 500. The feature was not partly broken — it did nothing at all, and said so in a
 * way no manager could act on.
 *
 * <p>The specific fix was V5. This is the general one. Enumerating the enum means the list
 * cannot drift again without a red test: a value added to the enum and not to the
 * constraint fails here, in a suite, naming itself.
 *
 * <p>It is deliberately parameterised over {@code EnumSource} rather than an explicit list
 * of six names. A hand-written list is a third copy of the same thing, and would have to be
 * remembered too.
 *
 * <p>This runs against H2, as the whole suite does, so it proves the constraint admits each
 * name — not that PostgreSQL's copy of it agrees. The two are written by the same migration
 * files, which is what makes the H2 run meaningful; V4 is the standing reminder that "the
 * same files" is not the same as "the same SQL dialect", so migrations still have to be
 * written in the intersection of both.
 */
@DisplayName("Outbox kinds are persistable")
class OutboxKindsArePersistableTest extends OnboardingIntegrationTest {

    @Autowired private OutboxRepository outbox;

    @ParameterizedTest(name = "{0} can be written to the outbox")
    @EnumSource(OutboxKind.class)
    void everyKindSatisfiesTheCheckConstraint(OutboxKind kind) {
        /*
         * saveAndFlush, not save. A CHECK constraint is enforced when the INSERT reaches the
         * database, and save() may only queue it — which would let this test pass while the
         * constraint still rejects the row, reproducing the exact bug it exists to prevent.
         */
        OutboxEntity saved =
                outbox.saveAndFlush(
                        OutboxEntity.sent(
                                kind,
                                "no-reply@zigama.rw",
                                "constraint.probe@example.com",
                                "Constraint probe",
                                "Written by OutboxKindsArePersistableTest."));

        assertThat(outbox.findById(saved.id()))
                .as("%s survived the round trip", kind)
                .get()
                .extracting(OutboxEntity::kind)
                .isEqualTo(kind);
    }
}

package rw.bank.ibanking.onboarding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reference a customer reads down the telephone.
 *
 * <p>IN THE DOMAIN PACKAGE so these can call {@code referenceFrom} directly. The alternative
 * was going through {@code raisedBy}, which mints its own random id — and the case that
 * matters most here is a SPECIFIC id, so a test that cannot choose one cannot reach it.
 */
class ServiceRequestReferenceTest {

    @Test
    @DisplayName("the one id that used to throw now produces a reference")
    void handlesTheIdWhoseAbsoluteValueIsItself() {
        /*
         * msb ^ lsb == Long.MIN_VALUE, the single value Math.abs cannot negate: it returns
         * Long.MIN_VALUE unchanged, so the old implementation took a negative remainder and
         * charAt threw StringIndexOutOfBoundsException — a 500 on a customer's request with
         * nothing in the log to explain it. Masking the sign bit has no such case.
         *
         * BREAK THE GUARD TO SEE IT FAIL: put `Math.abs(...)` back in referenceFrom and this
         * is the only test in the suite that notices.
         */
        UUID worstCase = new UUID(Long.MIN_VALUE, 0L);

        String reference = ServiceRequestEntity.referenceFrom(ServiceRequestType.CARD, worstCase);

        assertThat(reference).startsWith("CRD-").hasSize(10);
    }

    @Test
    @DisplayName("never contains a character people mishear")
    void avoidsConfusableCharacters() {
        /*
         * The whole point of the alphabet. O/0, I/1 and S/5 are what goes wrong when a
         * reference is read aloud down a bad line and typed in by somebody else.
         */
        for (int attempt = 0; attempt < 2_000; attempt++) {
            String suffix =
                    ServiceRequestEntity.referenceFrom(
                                    ServiceRequestType.CHEQUE_BOOK, UUID.randomUUID())
                            .substring(4);

            assertThat(suffix).hasSize(6).doesNotContainAnyWhitespaces();
            assertThat(suffix.chars())
                    .as("suffix %s", suffix)
                    .allMatch(character -> "OI0S15".indexOf(character) < 0);
        }
    }

    @Test
    @DisplayName("the prefix says which kind it is")
    void prefixesByKind() {
        UUID id = UUID.randomUUID();

        assertThat(ServiceRequestEntity.referenceFrom(ServiceRequestType.CARD, id))
                .startsWith("CRD-");
        assertThat(ServiceRequestEntity.referenceFrom(ServiceRequestType.CHEQUE_BOOK, id))
                .startsWith("CHQ-");
    }

    @Test
    @DisplayName("different ids give different references")
    void doesNotCollideAcrossManyIds() {
        /*
         * NOT A UNIQUENESS PROOF — six characters from a 30-letter alphabet is about 729
         * million values, so collisions exist. It is a check that the derivation actually
         * uses the id: an implementation that dropped the low bits, or that returned a
         * constant, passes every other test in this class and fails this one immediately.
         */
        Set<String> seen = new HashSet<>();
        for (int attempt = 0; attempt < 5_000; attempt++) {
            seen.add(ServiceRequestEntity.referenceFrom(ServiceRequestType.CARD, UUID.randomUUID()));
        }

        assertThat(seen).hasSizeGreaterThan(4_990);
    }
}

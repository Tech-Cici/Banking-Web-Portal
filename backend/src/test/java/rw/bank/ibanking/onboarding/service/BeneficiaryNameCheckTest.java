package rw.bank.ibanking.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static rw.bank.ibanking.onboarding.service.BeneficiaryService.NameCheck.MATCH;
import static rw.bank.ibanking.onboarding.service.BeneficiaryService.NameCheck.MISMATCH;
import static rw.bank.ibanking.onboarding.service.BeneficiaryService.NameCheck.PARTIAL;
import static rw.bank.ibanking.onboarding.service.BeneficiaryService.NameCheck.UNAVAILABLE;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * COMPARING THE NAME A CUSTOMER TYPED WITH THE NAME THE BANK HOLDS.
 *
 * <p>IN THIS PACKAGE SO THE COMPARISON CAN STAY PACKAGE-PRIVATE. The integration test can
 * only reach this through one registration name, and widening {@code compare} to public
 * purely to test it would advertise a helper that nothing outside the service should call.
 *
 * <p>WHY THE RULES ARE WHAT THEY ARE. A comparison that is too strict is worse than none
 * at all: if it flags "MUZORA Teta Eliana" against "Teta Eliana Muzora" as a mismatch, then
 * reviewers see the warning on legitimate payees all day and learn to click through it — so
 * the one that matters is clicked through too. So case, punctuation and word ORDER are all
 * ignored, and only a genuinely different set of names is a mismatch.
 *
 * <p>AND WHY A SHORT FORM IS ITS OWN VERDICT. "Teta Eliana" for "Teta Eliana Muzora" is
 * both the commonest innocent case and a usable disguise — somebody adding a stranger's
 * account may well know one of their names. Calling it a match would wave it through;
 * calling it a mismatch would cry wolf. PARTIAL says what happened and leaves the decision
 * with the person.
 */
@DisplayName("Payee name checking")
class BeneficiaryNameCheckTest {

    @Test
    @DisplayName("THE SAME NAME WRITTEN DIFFERENTLY IS A MATCH")
    void sameNameDifferentWriting() {
        assertThat(BeneficiaryService.compare("Teta Eliana Muzora", "Teta Eliana Muzora"))
                .isEqualTo(MATCH);

        /* Rwandan forms put the family name first as often as last. */
        assertThat(BeneficiaryService.compare("MUZORA Teta Eliana", "Teta Eliana Muzora"))
                .isEqualTo(MATCH);

        assertThat(BeneficiaryService.compare("teta eliana muzora", "TETA ELIANA MUZORA"))
                .isEqualTo(MATCH);

        /* Hyphens, apostrophes, full stops and double spaces distinguish nobody. */
        assertThat(BeneficiaryService.compare("jean-claude  nkurunziza", "Jean Claude Nkurunziza"))
                .isEqualTo(MATCH);
        assertThat(BeneficiaryService.compare("N'dayisaba, Eric.", "Eric Ndayisaba"))
                .isEqualTo(MISMATCH);
    }

    @Test
    @DisplayName("A SHORT FORM IS PARTIAL, in either direction")
    void shortFormsArePartial() {
        assertThat(BeneficiaryService.compare("Teta Eliana", "Teta Eliana Muzora"))
                .isEqualTo(PARTIAL);

        /* The other way round too: the customer may know the longer name. */
        assertThat(BeneficiaryService.compare("Teta Eliana Muzora", "Teta Eliana"))
                .isEqualTo(PARTIAL);
    }

    @Test
    @DisplayName("TWO DIFFERENT NAMES ARE A MISMATCH")
    void differentNamesMismatch() {
        assertThat(BeneficiaryService.compare("Patrick Habimana", "Teta Eliana Muzora"))
                .isEqualTo(MISMATCH);

        /* One shared first name is not a partial match: the sets are not nested. */
        assertThat(BeneficiaryService.compare("Teta Mukamana", "Teta Eliana Muzora"))
                .isEqualTo(MISMATCH);
    }

    @Test
    @DisplayName("NOTHING TO COMPARE IS UNAVAILABLE, never a match")
    void nothingToCompare() {
        /*
         * The important half of this test is that an EMPTY name does not sail through as a
         * match against another empty one. A blank held name means the bank has nothing to
         * say about this destination, and the screen must report that rather than a pass.
         */
        assertThat(BeneficiaryService.compare("", "Teta Eliana Muzora")).isEqualTo(UNAVAILABLE);
        assertThat(BeneficiaryService.compare("Teta Eliana Muzora", "")).isEqualTo(UNAVAILABLE);
        assertThat(BeneficiaryService.compare("", "")).isEqualTo(UNAVAILABLE);
        assertThat(BeneficiaryService.compare(null, "Teta Eliana Muzora")).isEqualTo(UNAVAILABLE);

        /* Punctuation alone is not a name. */
        assertThat(BeneficiaryService.compare("---", "Teta Eliana Muzora")).isEqualTo(UNAVAILABLE);
    }
}

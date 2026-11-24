package rw.bank.ibanking.onboarding.service;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.exception.BusinessRuleException;
import rw.bank.ibanking.exception.ResourceNotFoundException;
import rw.bank.ibanking.onboarding.domain.CardEntity;
import rw.bank.ibanking.onboarding.domain.CardType;
import rw.bank.ibanking.onboarding.repo.CardRepository;

/**
 * Cards: the customer's list, blocking one, and recording one the bank has issued.
 *
 * <p>THE ONLY PLACE FOUR DIGITS BECOME A MASK. {@link #maskOf} is the single function that
 * turns what staff typed into what a customer reads, and it refuses anything that is not
 * exactly four digits. Nothing longer can get past it, so nothing longer can reach the
 * database or a log line -- which is why the table has no column for a full number.
 *
 * <p>NO CARD IS EVER GENERATED HERE. Every value on a card row was typed by a member of
 * staff reading the physical card. This service validates and stores; it does not choose.
 */
@Service
public class CardService {

    private static final Logger log = LoggerFactory.getLogger(CardService.class);

    private final CardRepository cards;

    CardService(CardRepository cards) {
        this.cards = cards;
    }

    /** What staff read off the card. A record so a caller cannot transpose the arguments. */
    public record IssuedCard(
            String lastFourDigits, String brand, CardType cardType, int expiryMonth, int expiryYear) {}

    /* ==================================================== the customer's side */

    @Transactional(readOnly = true)
    public List<CardEntity> forCustomer(UUID customerId) {
        return cards.findByCustomerIdOrderByIssuedAtDesc(customerId);
    }

    /**
     * Stops a card.
     *
     * <p>THE CUSTOMER IS CHECKED AGAINST THE ROW rather than taken from it, the same rule
     * as marking a notification read: blocking by id alone would let anybody with a session
     * stop somebody else's card. A card that is not theirs reports as not found, which is
     * also all they should learn about it.
     *
     * <p>BLOCKING AN ALREADY-BLOCKED CARD SUCCEEDS. It is a safety action behind an
     * idempotency key, and a second press under a slow connection must not show the
     * customer an error about the thing they were trying to make safe.
     */
    @Transactional
    public CardEntity block(UUID customerId, UUID cardId) {
        CardEntity card = ownedOrRefuse(customerId, cardId);

        card.blocked();
        CardEntity saved = cards.save(card);

        /* The card id and the customer, never the mask: a log file has a different audience
           and a different retention from the row it came from. */
        log.info("Customer {} blocked card {}", customerId, cardId);
        return saved;
    }

    /* ========================================================= the staff side */

    /**
     * Records a card the bank has produced, against the request that asked for it.
     *
     * <p>REFUSES A SECOND CARD FOR THE SAME REQUEST. Marking a request ready twice is an
     * ordinary thing for a member of staff to do -- a mistyped collection point, corrected
     * -- and it must not leave the customer looking at two cards when they hold one. The
     * unique index in V23 covers the race this check cannot.
     */
    @Transactional
    public CardEntity issue(
            UUID customerId,
            UUID accountId,
            UUID serviceRequestId,
            IssuedCard details,
            String cardholderName,
            String issuedByName) {

        if (serviceRequestId != null && cards.findByServiceRequestId(serviceRequestId).isPresent()) {
            throw new BusinessRuleException(
                    "A card has already been recorded for that request.");
        }

        CardEntity card =
                CardEntity.issued(
                        customerId,
                        accountId,
                        serviceRequestId,
                        maskOf(details.lastFourDigits()),
                        requireText(details.brand(), "Say which brand the card is."),
                        details.cardType(),
                        details.expiryMonth(),
                        details.expiryYear(),
                        cardholderName,
                        issuedByName);

        CardEntity saved = cards.save(card);
        log.info("Card {} issued for customer {} by {}", saved.id(), customerId, issuedByName);
        return saved;
    }

    /** The card for a request becomes usable when the request is marked collected. */
    @Transactional
    public void activateForRequest(UUID serviceRequestId) {
        cards.findByServiceRequestId(serviceRequestId)
                .ifPresent(
                        card -> {
                            card.activated();
                            cards.save(card);
                        });
    }

    /* ============================================================== plumbing */

    /**
     * Four digits in, a display string out.
     *
     * <p>THE REFUSAL IS THE POINT. Anything that is not exactly four digits is rejected
     * here, so a full card number cannot be stored even by a caller that meant well. The
     * column is 24 characters and there is no other way in.
     */
    static String maskOf(String lastFourDigits) {
        String digits = lastFourDigits == null ? "" : lastFourDigits.trim();

        if (!digits.matches("\\d{4}")) {
            throw new BusinessRuleException(
                    "Enter the last four digits of the card, and only those four.");
        }
        return "**** " + digits;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new BusinessRuleException(message);
        return value.trim();
    }

    private CardEntity ownedOrRefuse(UUID customerId, UUID cardId) {
        return cards.findById(cardId)
                .filter(row -> row.customerId().equals(customerId))
                .orElseThrow(() -> new ResourceNotFoundException("We could not find that card."));
    }
}

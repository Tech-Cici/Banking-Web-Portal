package rw.bank.ibanking.onboarding.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.common.money.Money;
import rw.bank.ibanking.config.LoanProperties;
import rw.bank.ibanking.exception.BusinessRuleException;
import rw.bank.ibanking.exception.ResourceNotFoundException;
import rw.bank.ibanking.onboarding.domain.LoanEntity;
import rw.bank.ibanking.onboarding.domain.LoanStatus;
import rw.bank.ibanking.common.time.BankZone;
import rw.bank.ibanking.onboarding.domain.AccountType;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;
import rw.bank.ibanking.onboarding.domain.LoanRepaymentEntity;
import rw.bank.ibanking.onboarding.domain.MovementKind;
import rw.bank.ibanking.onboarding.domain.TransactionDirection;
import rw.bank.ibanking.onboarding.repo.CustomerAccountRepository;
import rw.bank.ibanking.onboarding.repo.LoanRepaymentRepository;
import rw.bank.ibanking.onboarding.repo.LoanRepository;

/**
 * Loans: the customer's own, and what a new one would cost.
 *
 * <p>THE PORTAL COMPUTES NOTHING ABOUT A REAL LOAN. Every figure on {@link LoanEntity} was
 * typed by staff from the bank's systems. This service reads them back and does not check
 * them against each other, because a second opinion about somebody's debt is worse than
 * none.
 *
 * <p>THE SIMULATION IS THE ONE EXCEPTION, and it is bounded: computed per request from a
 * rate in configuration, returned, and never written down. It answers "what would this
 * cost" and commits the bank to nothing — which is why it is called indicative on the
 * screen and why no row records it.
 *
 * <p>BIGDECIMAL THROUGHOUT, NEVER DOUBLE. An annuity in floating point is wrong in the last
 * minor unit and wrong differently on different machines, which is the whole reason this
 * project moves money in longs.
 */
@Service
public class LoanService {

    private static final Logger log = LoggerFactory.getLogger(LoanService.class);

    /** Enough digits that the compounding term does not lose precision before rounding. */
    private static final MathContext MATHS = new MathContext(20, RoundingMode.HALF_UP);

    private final LoanRepository loans;
    private final LoanProperties products;
    private final CustomerAccountRepository accounts;
    private final AccountLedgerService ledger;
    private final LoanRepaymentRepository repayments;
    private final AccountHolders holders;
    private final AccountAccess access;

    LoanService(
            LoanRepository loans,
            LoanProperties products,
            CustomerAccountRepository accounts,
            AccountLedgerService ledger,
            LoanRepaymentRepository repayments,
            AccountHolders holders,
            AccountAccess access) {
        this.loans = loans;
        this.products = products;
        this.accounts = accounts;
        this.ledger = ledger;
        this.repayments = repayments;
        this.holders = holders;
        this.access = access;
    }

    /** An indicative quote. Not stored, and not a commitment. */
    public record Simulation(
            Money principal,
            int months,
            String product,
            BigDecimal interestRatePercent,
            Money monthlyInstallment,
            Money totalInterest,
            Money totalRepayable) {}

    /* ==================================================== the customer's side */

    /**
     * The level repayment that clears {@code principal} over {@code months} at that rate.
     *
     * <p>ONE FORMULA, TWO CALLERS. The simulation quotes it from the published product rate;
     * granting a loan computes it from the rate the member of staff actually agreed. Those
     * two numbers can differ -- a customer's agreed rate is not always the advertised one --
     * but the arithmetic must not, or the figure a customer was shown and the figure they are
     * held to would drift apart with nothing to say which was right.
     *
     * <p>ROUNDED UP, TO THE MINOR UNIT. Rounding down leaves a few units outstanding after the
     * final instalment, which is a debt nobody intended and a letter nobody wants to write.
     *
     * <p>BIGDECIMAL THROUGHOUT. Compound interest on a double accumulates error over sixty
     * periods, and this is the figure the customer pays every month.
     */
    public static Money installmentFor(Money principal, int months, BigDecimal annualRatePercent) {
        BigDecimal borrowed = BigDecimal.valueOf(principal.minorUnits());

        // percent a year -> rate a month
        BigDecimal monthlyRate = annualRatePercent.divide(BigDecimal.valueOf(1200), MATHS);

        BigDecimal installmentMinor;
        if (monthlyRate.signum() == 0) {
            /* An interest-free loan is just the principal split evenly. */
            installmentMinor = borrowed.divide(BigDecimal.valueOf(months), MATHS);
        } else {
            BigDecimal growth = BigDecimal.ONE.add(monthlyRate).pow(months, MATHS);
            BigDecimal denominator = BigDecimal.ONE.subtract(BigDecimal.ONE.divide(growth, MATHS));
            installmentMinor = borrowed.multiply(monthlyRate, MATHS).divide(denominator, MATHS);
        }

        return new Money(
                installmentMinor.setScale(0, RoundingMode.UP).longValueExact(),
                principal.currency());
    }

    /**
     * Grants a loan and pays it into the account the customer named.
     *
     * <p>ONE TRANSACTION, TWO FACTS. The loan record and the credit that funds it are written
     * together or not at all. The alternative -- record the loan, then disburse -- has a
     * failure mode where the customer owes money they never received, which is the single
     * worst state this feature could leave anybody in.
     *
     * <p>THE RATE IS THE STAFF MEMBER'S, not the published one. LoanEntity has said so since
     * it was written: a customer's agreed rate can differ from the advertised one, and baking
     * the configured figure in would stamp a rate nobody negotiated onto a real debt.
     *
     * <p>OUTSTANDING STARTS AT THE PRINCIPAL, because that is what this schema means by the
     * word. V25 caps it at the principal and calls anything larger a typing error, so the
     * column is capital still owed rather than the total of the remaining instalments. The
     * two readings differ by the interest, and this one is the convention a balance screen
     * uses: "you borrowed 1,200,000 and still owe 900,000" is a sentence a customer can check
     * against what they have paid.
     *
     * <p>WHICH LEAVES INTEREST UNMODELLED FOR NOW, and that is worth saying plainly rather
     * than discovering during repayments. An instalment is part capital and part interest;
     * reducing outstanding by the whole instalment would clear the debt before the interest
     * had been collected. Splitting it is the repayment path's problem, and it is the next
     * thing to build.
     *
     * <p>THE DISBURSEMENT CARRIES NO COUNTERPARTY. The other party is the bank, and naming it
     * as a payee on its own customer's statement would invent a party where there is a
     * product. MovementKind.LOAN_DISBURSEMENT is what says where the money came from.
     */
    @Transactional
    public LoanEntity grant(
            UUID customerId,
            UUID accountId,
            UUID serviceRequestId,
            String productCode,
            Money principal,
            int months,
            BigDecimal ratePercent,
            String staffName) {

        LoanProperties.Product product = products.require(productCode);
        if (product == null) {
            throw new BusinessRuleException("That is not a loan product the bank publishes.");
        }
        if (principal.minorUnits() <= 0) {
            throw new BusinessRuleException("Enter an amount greater than zero.");
        }
        if (months < product.minMonths() || months > product.maxMonths()) {
            throw new BusinessRuleException(
                    product.name()
                            + " runs from "
                            + product.minMonths()
                            + " to "
                            + product.maxMonths()
                            + " months.");
        }
        /*
         * BOUNDED HERE, NOT ONLY BY THE DATABASE. V25 caps the rate at 100 and stores it as
         * NUMERIC(6,3); a rate outside that reaches Postgres as a constraint violation, which
         * the customer-facing layer can only render as "something went wrong on our side".
         * A member of staff who typed 150 deserves to be told they typed 150.
         */
        if (ratePercent == null || ratePercent.signum() < 0) {
            throw new BusinessRuleException("Enter the interest rate for this loan.");
        }
        if (ratePercent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessRuleException("A rate above 100% a year is not one this bank lends at.");
        }

        /*
         * LOCKED, because this credit is a balance change like any other and two staff
         * pressing grant at the same moment must not both read the same starting figure.
         */
        CustomerAccountEntity account =
                accounts.findByIdForUpdate(accountId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "That account is no longer on the customer's profile."));

        if (!account.currency().equals(principal.currency())) {
            throw new BusinessRuleException(
                    "That account is held in "
                            + account.currency()
                            + ", so the loan must be too.");
        }
        if (!account.isActive()) {
            throw new BusinessRuleException("That account cannot receive money right now.");
        }

        Money installment = installmentFor(principal, months, ratePercent);

        LoanEntity loan =
                record(
                        customerId,
                        accountId,
                        serviceRequestId,
                        product.code(),
                        principal,
                        /* Nothing has been repaid yet, so the capital owed is all of it. */
                        principal,
                        installment,
                        ratePercent,
                        /* The first instalment falls a month after the money arrives. */
                        BankZone.today().plusMonths(1),
                        LoanStatus.ACTIVE,
                        staffName);

        ledger.postAgainst(
                account,
                TransactionDirection.CREDIT,
                principal,
                "Loan disbursed — " + loan.reference(),
                staffName,
                /*
                 * KEYED ON THE LOAN, so a retried grant cannot pay the money out twice. The
                 * loan row is created first and `record` already refuses a second loan for
                 * the same application, so by the time this runs the key is unique and
                 * stable.
                 */
                "loan-disbursement-" + loan.id(),
                MovementKind.LOAN_DISBURSEMENT,
                null,
                null);

        log.info(
                "Loan {} granted to customer {} and disbursed into {} by {}",
                loan.reference(),
                customerId,
                account.maskedNumber(),
                staffName);

        return loan;
    }

    /** What one payment would be split into, without applying it. */
    public record Split(Money interest, Money capital, Money outstandingAfter) {}

    /**
     * How a payment of {@code amount} divides between interest and capital.
     *
     * <p>INTEREST FIRST, CAPITAL WITH THE REMAINDER, which is the order every amortised loan
     * applies a payment in. Interest is the price of having borrowed; it does not reduce what
     * was borrowed, so a payment that only covers interest leaves the balance untouched. That
     * is correct, and it is the single most queried line on any loan statement.
     *
     * <p>INTEREST IS CHARGED ON THE CAPITAL OUTSTANDING AT THE MOMENT OF PAYMENT, for one
     * month, rather than accrued daily. V34 records that simplification; it means paying
     * early costs the same as paying on time, which a real loan book would not.
     *
     * <p>PURE, SO THE SCREEN AND THE LEDGER CANNOT DISAGREE. The page shows the customer this
     * split before they confirm, and {@code repay} applies the same function to the same
     * figures rather than a second copy of the arithmetic.
     */
    public static Split splitOf(LoanEntity loan, Money amount) {
        Money outstanding = loan.outstanding();

        BigDecimal monthlyRate =
                loan.interestRatePercent().divide(BigDecimal.valueOf(1200), MATHS);

        BigDecimal interestExact =
                new BigDecimal(outstanding.toPlainString()).multiply(monthlyRate, MATHS);

        /*
         * ROUNDED TO THE CURRENCY'S SCALE, half up. Down would quietly forgive a fraction of
         * interest on every payment; up would charge for one that was not owed.
         */
        Money interestDue =
                Money.parse(
                        interestExact
                                .setScale(Money.scaleOf(outstanding.currency()), RoundingMode.HALF_UP)
                                .toPlainString(),
                        outstanding.currency());

        Money interest =
                amount.isGreaterThan(interestDue) || amount.minorUnits() == interestDue.minorUnits()
                        ? interestDue
                        : amount;

        Money capital = amount.minus(interest);

        return new Split(interest, capital, outstanding.minus(capital));
    }

    /** Everything owed to clear the loan today: the capital plus this month's interest. */
    public static Money settlementFigure(LoanEntity loan) {
        Money outstanding = loan.outstanding();
        /* A payment of the outstanding capital alone leaves exactly the interest unpaid. */
        return outstanding.plus(splitOf(loan, outstanding).interest());
    }

    /** What a payment would do, worked out before the customer is asked to confirm it. */
    public record Preview(
            Money amount,
            Money interest,
            Money capital,
            Money outstandingBefore,
            Money outstandingAfter,
            Money settlementFigure,
            boolean settlesLoan) {}

    /**
     * What a payment of {@code amount} would come to, without taking it.
     *
     * <p>THE SCREEN ASKS THE SERVER RATHER THAN WORKING IT OUT. The split depends on the rate
     * and on what is outstanding at this moment, and a figure the browser computed could
     * differ from the one the ledger applies a second later -- which is a customer shown one
     * number and charged another.
     *
     * <p>IT REFUSES WHAT {@code repay} WOULD REFUSE, through the same method, so the page
     * cannot offer a payment that the confirmation then rejects.
     */
    @Transactional(readOnly = true)
    public Preview preview(UUID customerId, UUID loanId, Money amount) {
        LoanEntity loan = oneFor(customerId, loanId);
        checkPayable(loan, amount);

        Split split = splitOf(loan, amount);

        return new Preview(
                amount,
                split.interest(),
                split.capital(),
                loan.outstanding(),
                split.outstandingAfter(),
                settlementFigure(loan),
                split.outstandingAfter().minorUnits() == 0);
    }

    /**
     * Everything a payment must satisfy before any money moves, in one place.
     *
     * <p>Called by {@code preview} and by {@code repay}, deliberately: a rule written twice is
     * a rule that gets changed once.
     */
    private static void checkPayable(LoanEntity loan, Money amount) {
        if (loan.status() == LoanStatus.SETTLED) {
            throw new BusinessRuleException("That loan is already settled.");
        }
        if (amount.minorUnits() <= 0) {
            throw new BusinessRuleException("Enter an amount greater than zero.");
        }
        if (!amount.currency().equals(loan.currency())) {
            throw new BusinessRuleException(
                    "That loan is in " + loan.currency() + ". Pay it in " + loan.currency() + ".");
        }

        Money settlement = settlementFigure(loan);
        if (amount.isGreaterThan(settlement)) {
            throw new BusinessRuleException(
                    "That is more than is needed to clear this loan. Paying "
                            + settlement.toPlainString()
                            + " "
                            + loan.currency()
                            + " settles it in full.");
        }
    }

    /**
     * Pays money off a loan from one of the customer's own accounts.
     *
     * <p>ONE TRANSACTION. The debit, the reduced balance and the row recording the split
     * commit together. A repayment that took the money without reducing the debt is the worst
     * outcome available here, and it is the one a partial commit would produce.
     *
     * <p>FROM A CURRENT ACCOUNT, in the loan's own currency. The same rule a standing order
     * follows: savings products are where money is kept, not where it is paid from. And no
     * conversion happens on the way -- an exchange rate is the bank's to quote, not something
     * a repayment screen invents.
     *
     * <p>THE BALANCE CHECK IS THE LEDGER'S. {@code CustomerAccountEntity.debit} refuses to go
     * below zero with a sentence a customer can act on; a second copy of that rule here would
     * be a second rule to keep in step.
     */
    @Transactional
    public LoanRepaymentEntity repay(
            UUID customerId, UUID loanId, UUID fromAccountId, Money amount, String idempotencyKey) {

        LoanEntity loan = oneFor(customerId, loanId);
        checkPayable(loan, amount);

        CustomerAccountEntity from =
                accounts.findByIdForUpdate(fromAccountId)
                        .filter(account -> access.mayAct(customerId, account))
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "We could not find that account on your profile."));

        if (from.accountType() != AccountType.CURRENT) {
            throw new BusinessRuleException(
                    "A loan is repaid from your current account. Choose that as the account the"
                            + " money comes from.");
        }
        if (!from.currency().equals(amount.currency())) {
            throw new BusinessRuleException(
                    "That account is held in " + from.currency() + ", and this loan is in "
                            + loan.currency() + ".");
        }
        if (!from.isActive()) {
            throw new BusinessRuleException("That account cannot be used right now.");
        }

        Split split = splitOf(loan, amount);

        ledger.postAgainst(
                from,
                TransactionDirection.DEBIT,
                amount,
                "Loan repayment — " + loan.reference(),
                holders.nameOf(from),
                idempotencyKey,
                MovementKind.LOAN_REPAYMENT,
                null,
                null);

        loan.repaidCapital(split.capital());

        /*
         * THE NEXT INSTALMENT MOVES ON ONLY WHEN ONE WAS ACTUALLY PAID. A part payment does
         * not buy another month; a loan still has the same instalment due on the same day.
         */
        if (loan.status() != LoanStatus.SETTLED
                && !loan.installment().isGreaterThan(amount)) {
            loan.nextInstalmentDue();
        }

        loans.save(loan);

        LoanRepaymentEntity record =
                repayments.save(
                        LoanRepaymentEntity.applied(
                                loan.id(),
                                customerId,
                                from.id(),
                                amount,
                                split.interest(),
                                split.capital(),
                                loan.outstanding()));

        log.info(
                "Loan {} repaid {} ({} interest, {} capital), {} left, status {}",
                loan.reference(),
                amount.toPlainString(),
                split.interest().toPlainString(),
                split.capital().toPlainString(),
                loan.outstanding().toPlainString(),
                loan.status());

        return record;
    }

    /** One loan's payment history, oldest first. */
    @Transactional(readOnly = true)
    public List<LoanRepaymentEntity> historyFor(UUID customerId, UUID loanId) {
        LoanEntity loan = oneFor(customerId, loanId);
        return repayments.findByLoanIdOrderByPaidAtAsc(loan.id());
    }

    @Transactional(readOnly = true)
    public List<LoanEntity> forCustomer(UUID customerId) {
        return loans.findByCustomerIdOrderByRecordedAtDesc(customerId);
    }

    /**
     * What a loan would cost, on the published rate.
     *
     * <p>THE STANDARD ANNUITY, and nothing cleverer: installment = P·i / (1 − (1+i)^−n),
     * with i the monthly rate. A zero rate divides the principal evenly rather than
     * dividing by zero.
     *
     * <p>ROUNDED UP TO THE MINOR UNIT. A payment rounded down leaves a few francs
     * outstanding after the final installment, which is how a settled loan shows a balance.
     */
    public Simulation simulate(Money principal, int months, String productCode) {
        LoanProperties.Product product = products.require(productCode);
        if (product == null) {
            throw new BusinessRuleException(
                    "The bank has not published a rate for that kind of loan.");
        }
        if (principal.minorUnits() <= 0) {
            throw new BusinessRuleException("Enter an amount greater than zero.");
        }
        if (months < product.minMonths() || months > product.maxMonths()) {
            throw new BusinessRuleException(
                    product.name()
                            + " runs from "
                            + product.minMonths()
                            + " to "
                            + product.maxMonths()
                            + " months.");
        }

        Money monthly = installmentFor(principal, months, product.annualRatePercent());
        long installment = monthly.minorUnits();
        long repayable = Math.multiplyExact(installment, (long) months);

        return new Simulation(
                principal,
                months,
                product.code(),
                product.annualRatePercent(),
                new Money(installment, principal.currency()),
                new Money(repayable - principal.minorUnits(), principal.currency()),
                new Money(repayable, principal.currency()));
    }

    /* ========================================================= the staff side */

    /**
     * Records a loan the bank has granted.
     *
     * <p>EVERY FIGURE IS THE CALLER'S. This method validates shape — positive, in range,
     * one currency — and refuses a second loan against the same application. It derives
     * nothing: the installment is not checked against the principal and the rate, because
     * the bank's schedule is the schedule.
     */
    @Transactional
    public LoanEntity record(
            UUID customerId,
            UUID accountId,
            UUID serviceRequestId,
            String productCode,
            Money principal,
            Money outstanding,
            Money installment,
            BigDecimal ratePercent,
            LocalDate nextDueDate,
            LoanStatus status,
            String recordedByName) {

        if (products.require(productCode) == null) {
            throw new BusinessRuleException("That is not a loan product the bank publishes.");
        }
        if (serviceRequestId != null && loans.findByServiceRequestId(serviceRequestId).isPresent()) {
            throw new BusinessRuleException("A loan has already been recorded for that application.");
        }
        if (!principal.currency().equals(outstanding.currency())
                || !principal.currency().equals(installment.currency())) {
            throw new BusinessRuleException("The three amounts must be in one currency.");
        }

        LoanEntity saved =
                loans.save(
                        LoanEntity.recorded(
                                customerId,
                                accountId,
                                serviceRequestId,
                                products.require(productCode).code(),
                                principal,
                                outstanding,
                                installment,
                                ratePercent,
                                nextDueDate,
                                status,
                                recordedByName));

        log.info("Loan {} recorded for customer {} by {}", saved.reference(), customerId, recordedByName);
        return saved;
    }

    @Transactional(readOnly = true)
    public LoanEntity oneFor(UUID customerId, UUID loanId) {
        return loans.findById(loanId)
                .filter(row -> row.customerId().equals(customerId))
                .orElseThrow(() -> new ResourceNotFoundException("We could not find that loan."));
    }
}

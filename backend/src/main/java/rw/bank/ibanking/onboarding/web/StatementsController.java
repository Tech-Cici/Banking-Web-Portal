package rw.bank.ibanking.onboarding.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;
import rw.bank.ibanking.onboarding.repo.CustomerAccountRepository;
import rw.bank.ibanking.statements.StatementEntity;
import rw.bank.ibanking.statements.StatementFormat;
import rw.bank.ibanking.statements.StatementService;

/**
 * The signed-in customer's statements.
 *
 * <p>SCOPED TO THE CALLER, always: the customer id comes from the authenticated principal and
 * never from the request. The account id in a request is checked against that customer before
 * a single row is read.
 *
 * <p>THE FILE COMES BACK FROM THE POST, rather than a job id to poll. The customer pressed a
 * button and wanted a statement; handing them a token to exchange for one later is machinery
 * that earns its keep at a scale this bank is not at.
 *
 * <p>IT LIVES HERE RATHER THAN BESIDE ITS SERVICE because {@code AuthController.currentCustomerId}
 * is package-private, and deliberately so: who is signed in is a question this package answers.
 * CbsLookupController sits here for the same reason while its service lives in the cbs package.
 * Widening that method to public so a controller could move one package over would trade a
 * real boundary for a filing preference.
 */
@RestController
@RequestMapping("/api/v1/statements")
class StatementsController {

    private final StatementService statements;
    private final CustomerAccountRepository accounts;

    StatementsController(StatementService statements, CustomerAccountRepository accounts) {
        this.statements = statements;
        this.accounts = accounts;
    }

    /**
     * What to produce.
     *
     * <p>{@code accountId} IS OPTIONAL AND NULL MEANS ALL. A customer holding one account per
     * product under a single customer number usually means all of them when they say "my
     * transactions", so the absence of a choice is a real choice rather than a missing field.
     */
    record NewStatement(
            UUID accountId,
            @NotNull(message = "Choose the date this statement starts from.")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @NotNull(message = "Choose the date this statement runs to.")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @NotNull(message = "Choose a format.") StatementFormat format) {}

    /**
     * One statement in the customer's list.
     *
     * <p>NO STATUS FIELD. Every statement in this list exists because a document was produced,
     * so a status could only ever read READY -- and a field that always says the same thing is
     * a field that invites somebody to believe it means something.
     */
    record StatementView(
            String id,
            String accountId,
            String scope,
            /** What the list shows: "All accounts", or the product and its mask. */
            String accountLabel,
            String periodFrom,
            String periodTo,
            String format,
            String requestedAt,
            long sizeBytes) {}

    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    List<StatementView> mine() {
        return statements.forCustomer(AuthController.currentCustomerId()).stream()
                .map(this::view)
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    ResponseEntity<byte[]> produce(@Valid @RequestBody NewStatement request) {
        StatementService.Rendered rendered =
                statements.produce(
                        AuthController.currentCustomerId(),
                        request.accountId(),
                        request.from(),
                        request.to(),
                        request.format());

        return fileResponse(rendered);
    }

    @GetMapping("/{id}/download")
    @PreAuthorize("hasRole('CUSTOMER')")
    ResponseEntity<byte[]> download(@PathVariable UUID id) {
        return fileResponse(statements.reproduce(AuthController.currentCustomerId(), id));
    }

    /**
     * The file, with the headers a browser needs to save it.
     *
     * <p>THE FILENAME GOES THROUGH {@link ContentDisposition}, which quotes and escapes it,
     * rather than being concatenated into the header by hand. A filename built from a
     * customer's own text and pasted into a header is a response-splitting bug waiting to
     * happen; these names are built from dates, but the safe spelling costs nothing.
     *
     * <p>NO-STORE, because a statement is the customer's financial history and a shared cache
     * -- a proxy, a CDN, a browser on a shared machine -- has no business keeping a copy.
     */
    private static ResponseEntity<byte[]> fileResponse(StatementService.Rendered rendered) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(rendered.format().mediaType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(rendered.filename())
                                .build()
                                .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentLength(rendered.content().length)
                .body(rendered.content());
    }

    /**
     * <p>THE ACCOUNT IS NAMED HERE, not by the client. The row stores an id, and a list that
     * showed one would be showing a UUID; resolving it in the browser would mean fetching
     * every account to decode one column. The server already holds both.
     *
     * <p>AN ACCOUNT SINCE REMOVED STILL READS SENSIBLY. The statement was real, so the row
     * stays and says the account is no longer listed rather than disappearing or showing a
     * blank where a name should be.
     */
    private StatementView view(StatementEntity record) {
        String label =
                record.accountId()
                        .map(
                                id ->
                                        accounts.findById(id)
                                                .map(StatementsController::describe)
                                                .orElse("Account no longer listed"))
                        .orElse("All accounts");

        return new StatementView(
                record.id().toString(),
                record.accountId().map(UUID::toString).orElse(null),
                record.accountId().isPresent() ? "ONE_ACCOUNT" : "ALL_ACCOUNTS",
                label,
                record.periodFrom().toString(),
                record.periodTo().toString(),
                record.format().name(),
                record.requestedAt().toString(),
                record.sizeBytes());
    }

    private static String describe(CustomerAccountEntity account) {
        return account.accountType().label() + " " + account.maskedNumber();
    }
}

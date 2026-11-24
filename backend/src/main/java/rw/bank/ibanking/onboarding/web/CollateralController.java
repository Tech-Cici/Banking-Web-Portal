package rw.bank.ibanking.onboarding.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import rw.bank.ibanking.collateral.CollateralDocumentEntity;
import rw.bank.ibanking.collateral.CollateralFieldKey;
import rw.bank.ibanking.collateral.CollateralKind;
import rw.bank.ibanking.collateral.CollateralService;
import rw.bank.ibanking.collateral.TesseractReader;

/**
 * Attaching evidence of collateral to a loan application, and reading it.
 *
 * <p>THE FIRST ENDPOINT IN THIS SYSTEM THAT TAKES A FILE. Everything it returns about the
 * document is a reading rather than a finding: the customer is shown what the page appeared to
 * say, corrects it, and submits their own answers. Nothing here verifies anything against a
 * registry, because nothing in this portal can.
 */
@RestController
@RequestMapping("/api/v1/collateral")
class CollateralController {

    /** Longer than this and the customer is reading a wall of OCR instead of their form. */
    private static final int PREVIEW_CHARS = 1500;

    private final CollateralService collateral;

    CollateralController(CollateralService collateral) {
        this.collateral = collateral;
    }

    /** One thing the bank takes, and what its document is expected to carry. */
    record KindView(String kind, String label, String document, List<FieldView> fields) {}

    record FieldView(String key, String label, String hint, String value) {}

    record DocumentView(
            String id,
            String kind,
            String filename,
            String contentType,
            int sizeBytes,
            String ocrStatus,
            String ocrFailure,
            String textPreview,
            /** The document's own title, so the customer can see it was read as theirs. */
            String heading,
            List<FieldView> fields,
            /** Detail the page gave up that nobody types. Shown, never declared. */
            List<FieldView> alsoRead) {}

    /**
     * What the bank accepts as collateral, and which fields each kind asks for.
     *
     * <p>SERVED RATHER THAN DUPLICATED IN THE BROWSER. The kinds, their labels, the document
     * the customer is asked to photograph and the fields on it are one list; a second copy in
     * the front end is how a field comes to be called one thing where it is typed and another
     * where it is checked.
     */
    @GetMapping("/kinds")
    @PreAuthorize("hasRole('CUSTOMER')")
    List<KindView> kinds() {
        return Arrays.stream(CollateralKind.values())
                .map(
                        kind ->
                                new KindView(
                                        kind.name(),
                                        kind.label(),
                                        kind.document(),
                                        kind.fields().stream()
                                                .map(key -> new FieldView(key.name(), key.label(), key.hint(), null))
                                                .toList()))
                .toList();
    }

    /**
     * Uploads a document and reads it.
     *
     * <p>NO IDEMPOTENCY KEY, deliberately, and it is worth saying why given every other POST
     * in this package carries one. Those keys exist because repeating the request would move
     * money twice. This moves nothing: the same bytes uploaded again are recognised by their
     * hash and the first row is returned, so a retry after a dropped connection is already
     * harmless.
     */
    @PostMapping(path = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    DocumentView upload(
            @RequestParam("kind") @NotBlank @Size(max = 16) String kind,
            @RequestPart("file") MultipartFile file) {

        CollateralService.Accepted accepted =
                collateral.accept(
                        AuthController.currentCustomerId(), CollateralKind.parse(kind), file);

        return view(
                accepted.document(),
                accepted.fields(),
                accepted.alsoRead(),
                accepted.heading());
    }

    /**
     * The document itself, back to the customer who uploaded it.
     *
     * <p>ALWAYS AN ATTACHMENT, NEVER INLINE, and with sniffing turned off. The bytes came from
     * outside: serving them inline on the bank's own origin is how a file that is not really a
     * JPEG ends up running as a page with the customer's session attached. The type in the
     * header is the one the bytes were checked to be at upload, not the one the browser
     * claimed.
     */
    @GetMapping("/documents/{documentId}/file")
    @PreAuthorize("hasRole('CUSTOMER')")
    ResponseEntity<Resource> file(@PathVariable UUID documentId) {
        CollateralDocumentEntity document =
                collateral.documentFor(AuthController.currentCustomerId(), documentId);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                /* Spring's HttpHeaders has no constant for this one; the name is the name. */
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(document.originalFilename())
                                .build()
                                .toString())
                .contentLength(document.sizeBytes())
                .body(new ByteArrayResource(document.content()));
    }

    private static DocumentView view(
            CollateralDocumentEntity document,
            Map<CollateralFieldKey, String> read,
            Map<CollateralFieldKey, String> extra,
            String heading) {

        return new DocumentView(
                document.id().toString(),
                document.kind().name(),
                document.originalFilename(),
                document.contentType(),
                document.sizeBytes(),
                document.ocrStatus().name(),
                document.ocrFailure(),
                preview(document.ocrText()),
                heading,
                viewsOf(document.kind().fields(), read),
                /*
                 * ONLY WHAT WAS ACTUALLY FOUND. An empty row under "what else the page says"
                 * is noise: these are not fields anybody is being asked to complete, so a
                 * blank one has nothing to offer and nothing to do.
                 */
                document.kind().alsoRead().stream()
                        .filter(extra::containsKey)
                        .map(key -> new FieldView(key.name(), key.label(), key.hint(), extra.get(key)))
                        .toList());
    }

    private static List<FieldView> viewsOf(
            List<CollateralFieldKey> keys, Map<CollateralFieldKey, String> read) {

        return keys.stream()
                .map(key -> new FieldView(key.name(), key.label(), key.hint(), read.get(key)))
                .toList();
    }

    /**
     * Enough of the reading for a customer to see why a field came back empty.
     *
     * <p>THE FIRST READING ONLY. Every page is read twice under different layout assumptions
     * and both are stored, because a reviewer should see everything the extractor saw. A
     * customer should not: their document printed twice on their own screen looks like a
     * fault in the page, and the second copy answers a question they did not ask.
     */
    private static String preview(String text) {
        if (text == null || text.isBlank()) return null;

        String first = text.strip();
        int second = first.indexOf(TesseractReader.SECOND_READING);
        if (second > 0) first = first.substring(0, second).strip();

        return first.length() <= PREVIEW_CHARS
                ? first
                : first.substring(0, PREVIEW_CHARS) + "\n…";
    }
}

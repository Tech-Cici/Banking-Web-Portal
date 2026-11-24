package rw.bank.ibanking.collateral;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import rw.bank.ibanking.exception.BusinessRuleException;
import rw.bank.ibanking.exception.ResourceNotFoundException;

/**
 * Takes in a collateral document, reads it, and hands back what it thinks the page says.
 *
 * <p>THE UPLOAD SUCCEEDS EVEN WHEN THE READING FAILS. These are two different things and only
 * the first one is the customer's. A blurred photograph, a reader that is not installed on
 * this machine, a PDF with no pages: in every case the file is stored, the application can go
 * on, and the fields are typed by hand. Refusing the upload because the reading failed would
 * make a convenience into a gate, and the gate would be made of a regular expression.
 *
 * <p>THE READING HAPPENS IN THE REQUEST, not in a queue. It takes a second or two per page,
 * the customer is sitting in front of the form waiting for the fields to fill in, and a
 * background job would mean a screen that polls for an answer it needs immediately. The
 * timeout in {@link OcrProperties} is what keeps that honest.
 */
@Service
public class CollateralService {

    private static final Logger log = LoggerFactory.getLogger(CollateralService.class);

    /**
     * What the bank accepts, by what the bytes actually are rather than what they claim.
     *
     * <p>HEIC IS ABSENT AND IS HANDLED SEPARATELY BELOW. It is what an iPhone produces by
     * default, Java cannot decode it, and "we could not read your document" is the wrong
     * sentence for a file nothing here can even open.
     */
    private static final Map<String, String> ACCEPTED =
            Map.of(
                    "ffd8ff", "image/jpeg",
                    "89504e47", "image/png",
                    "25504446", "application/pdf");

    /** An upload nobody attached to an application is deleted after this. */
    private static final Duration ABANDONED_AFTER = Duration.ofHours(24);

    private final CollateralDocumentRepository documents;
    private final LoanCollateralRepository pledges;
    private final CollateralFieldRepository fields;
    private final TesseractReader reader;
    private final OcrProperties properties;

    public CollateralService(
            CollateralDocumentRepository documents,
            LoanCollateralRepository pledges,
            CollateralFieldRepository fields,
            TesseractReader reader,
            OcrProperties properties) {
        this.documents = documents;
        this.pledges = pledges;
        this.fields = fields;
        this.reader = reader;
        this.properties = properties;
    }

    /**
     * A stored document, what the form fills itself from, and what the page says besides.
     *
     * @param fields the values the customer is asked to confirm
     * @param alsoRead detail read off the page that nobody types and nobody declares -- the
     *     district, the dates, the make of the vehicle. Shown, never compared.
     * @param heading the document's own title, lifted off the top of the page
     */
    public record Accepted(
            CollateralDocumentEntity document,
            Map<CollateralFieldKey, String> fields,
            Map<CollateralFieldKey, String> alsoRead,
            String heading) {}

    @Transactional
    public Accepted accept(UUID customerId, CollateralKind kind, MultipartFile upload) {
        byte[] content = bytesOf(upload);

        checkSize(content.length);
        String contentType = typeOf(content);

        String digest = sha256(content);

        /*
         * THE SAME FILE AGAIN IS THE SAME DOCUMENT. Returning the existing row rather than a
         * second copy also means the reading is not run twice, which on a three-page PDF is
         * several seconds the customer does not spend again.
         */
        var existing =
                documents.findFirstByCustomerIdAndSha256AndLinkedAtIsNull(customerId, digest);
        if (existing.isPresent() && existing.get().kind() == kind) {
            CollateralDocumentEntity already = existing.get();
            return new Accepted(
                    already,
                    FieldReader.read(kind, already.ocrText()),
                    FieldReader.read(kind.alsoRead(), already.ocrText()),
                    FieldReader.heading(already.ocrText()));
        }

        DocumentReading reading = reader.read(content, contentType);

        if (reading.status() == OcrStatus.FAILED) {
            /*
             * WARN, NOT ERROR, and the request still succeeds. The customer is about to be
             * told to type the fields, which is a working path; what wants looking at is the
             * machine, and that is what the log line is for.
             */
            log.warn("A collateral document could not be read: {}", reading.failure());
        }

        Map<CollateralFieldKey, String> found =
                reading.hasText() ? FieldReader.read(kind, reading.text()) : Map.of();

        refuseIfNotTheRightDocument(kind, reading, found);

        CollateralDocumentEntity stored =
                documents.save(
                        CollateralDocumentEntity.uploaded(
                                customerId,
                                kind,
                                safeFilename(upload.getOriginalFilename()),
                                contentType,
                                content,
                                digest,
                                reading));

        log.info(
                "Collateral document {} ({}) stored for customer {}: {}, {} of {} fields read",
                stored.id(),
                kind,
                customerId,
                reading.status(),
                found.size(),
                kind.fields().size());

        return new Accepted(
                stored,
                found,
                reading.hasText()
                        ? FieldReader.read(kind.alsoRead(), reading.text())
                        : Map.of(),
                reading.hasText() ? FieldReader.heading(reading.text()) : null);
    }

    /**
     * Refuses a file that is readable but carries nothing belonging to this kind of document.
     *
     * <p>WHY THIS EXISTS. Without it, a customer could attach their application letter as a
     * land title: the reading finds no parcel number, every box comes back empty, and they
     * type whatever they like into them. The bank would then hold a declaration about a plot
     * with a covering letter filed as the evidence for it -- which is worse than holding
     * nothing, because it looks like evidence.
     *
     * <p>THE READER'S OWN FAILURE IS NEVER THE CUSTOMER'S FAULT, and this is the line that
     * keeps the two apart. If tesseract is missing, broken, or timed out, nothing can be
     * concluded about what the page shows, and refusing the upload would mean no loan
     * application anywhere can be submitted whenever the bank's own machine is misconfigured.
     * So FAILED passes, the boxes are typed by hand, and the fault is in the log where it
     * belongs.
     *
     * <p>AN IDENTIFIER, NOT ALL THE FIELDS. One parcel number, plate, chassis number or
     * company code is enough: it belongs to this class of document and does not turn up on a
     * letter. Demanding the complete set would reject a genuine certificate photographed at
     * an angle, and the fields that are missing are the ones the customer types anyway.
     *
     * <p>THE COST OF THIS CHECK IS A FALSE REFUSAL, and it is paid by a customer holding a
     * real document the patterns do not recognise. That is a real risk while those patterns
     * have been tested against invented certificates rather than Rwandan ones. The message
     * therefore says what was expected and what was found, so somebody meeting it can tell a
     * wrong attachment from a reader that needs fixing.
     */
    /*
     * PACKAGE-PRIVATE, NOT PRIVATE, so the command-line probe in src/test can put a document
     * through the real decision rather than a second copy of it. A test harness that
     * reimplements the rule it is testing agrees with itself and nothing else.
     */
    static void refuseIfNotTheRightDocument(
            CollateralKind kind, DocumentReading reading, Map<CollateralFieldKey, String> found) {

        if (reading.status() == OcrStatus.FAILED) return;

        if (reading.status() == OcrStatus.UNREADABLE) {
            throw new BusinessRuleException(
                    "We could not read anything on that page. Attach a sharper photograph of"
                            + " the "
                            + kind.document().toLowerCase(Locale.ROOT)
                            + ", with the whole page in frame and no glare.");
        }

        /*
         * EITHER AN IDENTIFIER, OR THE DOCUMENT'S OWN WORDS.
         *
         * The first real Rwandan land lease put through this read badly enough to give up no
         * parcel number at all, while plainly being a land lease: the heading, the word
         * ubutaka and the labels were all there. Requiring the extracted number would have
         * refused a genuine title, which is the expensive mistake here -- the customer has
         * the right document in their hand and no way to proceed.
         *
         * An application letter has neither, which is the case this check exists for.
         */
        if (kind.identifiers().stream().anyMatch(found::containsKey)) return;
        if (kind.looksLikeThisDocument(reading.text())) return;

        throw new BusinessRuleException(
                "That file does not look like a "
                        + kind.document().toLowerCase(Locale.ROOT)
                        + ". Nothing on the page says it is one. Attach the right document,"
                        + " or a clearer photograph of it.");
    }

    /**
     * Attaches declared collateral to a loan application.
     *
     * <p>IN THE APPLICATION'S OWN TRANSACTION. The pledge, its fields and the application are
     * one act: an application that committed without its collateral would be a loan request
     * the bank believes is secured by nothing, and a pledge without its application would be a
     * scan attached to a reference that does not exist.
     *
     * <p>THE READING IS RECOVERED FROM STORED TEXT, NOT RE-RUN. Running the reader again here
     * could produce different words from the ones the customer was shown a minute ago, and the
     * comparison recorded against their declaration would then be against something they never
     * saw.
     */
    @Transactional
    public List<LoanCollateralEntity> link(
            UUID customerId, UUID serviceRequestId, List<CollateralDeclaration> declarations) {

        List<LoanCollateralEntity> saved = new ArrayList<>(declarations.size());

        for (CollateralDeclaration declared : declarations) {
            CollateralDocumentEntity document = documentFor(customerId, declared.documentId());

            /*
             * ONE DOCUMENT, ONE PLEDGE. V35 enforces this with a unique constraint; catching it
             * here means the customer gets a sentence instead of a message about an index.
             */
            if (document.isLinked()) {
                throw new BusinessRuleException(
                        "That document is already attached to another application."
                                + " Upload it again for this one.");
            }

            String description = required(declared.description(), "Say what you are pledging.");

            LoanCollateralEntity pledge =
                    pledges.save(
                            LoanCollateralEntity.pledged(
                                    customerId,
                                    serviceRequestId,
                                    document.id(),
                                    document.kind(),
                                    description.length() > 160
                                            ? description.substring(0, 160)
                                            : description));

            Map<CollateralFieldKey, String> read = fieldsOf(document);

            for (CollateralFieldKey key : document.kind().fields()) {
                String value =
                        required(
                                declared.values() == null ? null : declared.values().get(key),
                                "Fill in " + key.label().toLowerCase(Locale.ROOT) + ".");

                fields.save(
                        CollateralFieldEntity.of(
                                pledge.id(),
                                key,
                                value.length() > 200 ? value.substring(0, 200) : value,
                                read.get(key)));
            }

            document.linked();
            documents.save(document);
            saved.add(pledge);
        }

        log.info(
                "Application {} pledged {} item(s) of collateral", serviceRequestId, saved.size());

        return saved;
    }

    private static String required(String value, String complaint) {
        if (value == null || value.isBlank()) throw new BusinessRuleException(complaint);
        return value.strip();
    }

    /** What was pledged against one application, for the screen that reviews it. */
    @Transactional(readOnly = true)
    public List<LoanCollateralEntity> pledgedAgainst(UUID serviceRequestId) {
        return pledges.findByServiceRequestIdOrderByCreatedAtAsc(serviceRequestId);
    }

    /** One pledge's fields, declared beside read. */
    @Transactional(readOnly = true)
    public List<CollateralFieldEntity> fieldsOfPledge(UUID collateralId) {
        return fields.findByCollateralId(collateralId);
    }

    /**
     * One document by its id alone, for a member of staff reviewing an application.
     *
     * <p>NO OWNER CHECK, AND THAT IS THE WHOLE DIFFERENCE from {@link #documentFor}. A
     * reviewer is not the uploader, so the customer's method would refuse them every time.
     * Keeping them as two methods rather than one with a flag means the unchecked path is
     * named, is findable, and cannot be reached by passing the wrong argument: whoever calls
     * this has to have said so. Authorisation for it lives on the staff endpoint.
     */
    @Transactional(readOnly = true)
    public CollateralDocumentEntity documentById(UUID documentId) {
        return documents
                .findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("We could not find that file."));
    }

    /** One of the customer's own documents, bytes included. */
    @Transactional(readOnly = true)
    public CollateralDocumentEntity documentFor(UUID customerId, UUID documentId) {
        return documents
                .findByIdAndCustomerId(documentId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("We could not find that file."));
    }

    /**
     * Deletes uploads that never became an application.
     *
     * <p>THE WHOLE REASON THIS METHOD EXISTS. Somebody photographs their land title, starts an
     * application, changes their mind and closes the tab. Without a sweep, a scan of their
     * property sits in the bank's database forever, attached to nothing, serving nobody and
     * waiting to appear in a breach. A document is kept because an application needs it; one
     * that no application needs is not kept.
     */
    @Transactional
    public int sweepAbandoned() {
        Instant cutoff = Instant.now().minus(ABANDONED_AFTER);
        List<CollateralDocumentEntity> stale =
                documents.findByLinkedAtIsNullAndUploadedAtBefore(cutoff);

        if (stale.isEmpty()) return 0;

        documents.deleteAll(stale);
        log.info("Deleted {} collateral uploads that were never submitted", stale.size());
        return stale.size();
    }

    /* ------------------------------------------------------------------------ the gatekeeping */

    private static byte[] bytesOf(MultipartFile upload) {
        if (upload == null || upload.isEmpty()) {
            throw new BusinessRuleException("Choose the document you want to attach.");
        }
        try {
            return upload.getBytes();
        } catch (IOException unreadable) {
            throw new BusinessRuleException(
                    "That file did not arrive in one piece. Please attach it again.");
        }
    }

    private void checkSize(int length) {
        if (length > properties.maxBytes()) {
            throw new BusinessRuleException(
                    "That file is larger than "
                            + (properties.maxBytes() / (1024 * 1024))
                            + " MB. A photograph of the page is enough.");
        }
    }

    /**
     * What the file actually is, read from its first bytes.
     *
     * <p>THE BROWSER'S CONTENT TYPE IS NOT EVIDENCE. It comes from the file's extension on
     * somebody else's computer, and this value decides both what the reader does with the
     * bytes and what header they are served back under. A file that says image/jpeg and
     * contains HTML, served back as image/jpeg, is a stored cross-site scripting hole.
     */
    private static String typeOf(byte[] content) {
        String head = HexFormat.of().formatHex(content, 0, Math.min(8, content.length));

        for (Map.Entry<String, String> known : ACCEPTED.entrySet()) {
            if (head.startsWith(known.getKey())) return known.getValue();
        }

        /* ftyp...heic / heif, at offset 4. Named so the customer is told something useful. */
        if (head.length() >= 16 && head.substring(8).startsWith("66747970")) {
            throw new BusinessRuleException(
                    "That looks like an iPhone HEIC photo, which the bank cannot open yet."
                            + " Please share it as a JPEG or PDF.");
        }

        throw new BusinessRuleException(
                "Attach a photograph or a scan: a JPEG, a PNG or a PDF.");
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            /* SHA-256 is required of every Java platform; its absence is not a runtime case. */
            throw new IllegalStateException("SHA-256 is missing from this JVM", impossible);
        }
    }

    /**
     * The customer's file name, reduced to something safe to store and to echo back.
     *
     * <p>IT IS KEPT ONLY SO THE CUSTOMER RECOGNISES THEIR OWN FILE on a list. Nothing opens it
     * by this name -- the bytes are in a column, not on a disk -- so the separators are
     * stripped rather than escaped, and what remains cannot describe a path.
     */
    private static String safeFilename(String original) {
        if (original == null || original.isBlank()) return "document";

        String base = original.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[\\p{Cntrl}]", "").strip();

        if (base.isBlank()) return "document";
        return base.length() > 255 ? base.substring(base.length() - 255) : base;
    }

    /** The reading, re-run over stored text — used by screens, never to decide anything. */
    public static Map<CollateralFieldKey, String> fieldsOf(CollateralDocumentEntity document) {
        return FieldReader.read(document.kind(), document.ocrText());
    }

    /** The extra detail, for the screen a member of staff reviews an application on. */
    public static Map<CollateralFieldKey, String> extraOf(CollateralDocumentEntity document) {
        return FieldReader.read(document.kind().alsoRead(), document.ocrText());
    }

    /** Lower-cased for comparison; kept out of the entity because it is a display concern. */
    static String normalise(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}

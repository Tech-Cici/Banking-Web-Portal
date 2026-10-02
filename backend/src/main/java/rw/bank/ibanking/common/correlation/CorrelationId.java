package rw.bank.ibanking.common.correlation;

import org.slf4j.MDC;

/** Access to the current request's correlation id. */
public final class CorrelationId {

    /** Inbound/outbound HTTP header carrying the id. */
    public static final String HEADER = "X-Correlation-Id";

    /** SLF4J MDC key; referenced by the logging pattern in application.yml. */
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {}

    /** Correlation id for the in-flight request, or {@code null} outside a request. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}

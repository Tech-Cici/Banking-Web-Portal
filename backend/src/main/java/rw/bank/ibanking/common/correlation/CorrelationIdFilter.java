package rw.bank.ibanking.common.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every request a correlation id, exposes it on the MDC for logging, and echoes it
 * back on the response so the client can display it beside an error message.
 *
 * <p>A client-supplied id is honoured only if it matches {@link #SAFE_ID} — an untrusted
 * header must never reach the logs unvalidated, since a newline would let a caller forge
 * log lines.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Conservative allow-list: id-safe characters, bounded length. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String correlationId = resolve(request.getHeader(CorrelationId.HEADER));
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private static String resolve(String supplied) {
        if (StringUtils.hasText(supplied) && SAFE_ID.matcher(supplied).matches()) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }

    /** Correlation ids are useful on error dispatches too. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}

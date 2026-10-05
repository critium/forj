package forj.examples.http;

import com.sun.net.httpserver.HttpExchange;
import java.util.UUID;

/**
 * Per-request context that every downstream call needs. It is passed as a {@code using}
 * parameter, so code in between never mentions it.
 */
public record RequestContext(String traceId) {

    static final String TRACE_HEADER = "X-Trace-Id";

    /** The caller's trace ID, or a new one. */
    static RequestContext from(HttpExchange exchange) {
        String traceId = exchange.getRequestHeaders().getFirst(TRACE_HEADER);
        return new RequestContext(traceId != null ? traceId : UUID.randomUUID().toString());
    }
}

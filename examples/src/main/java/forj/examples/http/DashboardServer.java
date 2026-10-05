package forj.examples.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import forj.Par;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An HTTP API whose handler calls three downstream services in parallel, then a fourth that
 * needs one of their answers, and combines everything with a forj comprehension over
 * {@link Callable}.
 *
 * <pre>
 * GET /dashboard/{user}  ->  GET /profile/{user}          \
 *                            GET /orders/{user}           |-- in parallel (Par.mapN)
 *                            GET /recommendations/{user}  /
 *                            GET /shipping/{item count}   --- then, using the orders
 * </pre>
 *
 * Everything is lazy: {@link #dashboard} only describes the calls, and they happen when
 * the handler invokes {@code call()}. {@link Par#mapN} then forks them in one
 * StructuredTaskScope; if one fails, the others are cancelled. The downstream services are
 * served by this same process with an artificial {@link #LATENCY}.
 *
 * <p>Every downstream call carries the request's trace ID. The handler declares the
 * {@link RequestContext} once as a {@code given}; {@link #dashboard} and {@link #get} take it
 * as a {@code using} parameter, and the forj plugin passes it along at compile time.
 *
 * <p>Run: {@code ./mill examples.runMain forj.examples.http.DashboardServer}, then
 * {@code curl localhost:8080/dashboard/ana}.
 */
public final class DashboardServer implements AutoCloseable {

    static final Duration LATENCY = Duration.ofMillis(200);

    private final HttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicInteger downstreamCalls = new AtomicInteger();
    private final List<String> downstreamTraceIds = new CopyOnWriteArrayList<>();

    private DashboardServer(HttpServer server) {
        this.server = server;
    }

    public static DashboardServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        DashboardServer app = new DashboardServer(server);
        server.createContext("/dashboard/", app::dashboard);
        server.createContext("/profile/", ex -> app.downstream(ex, user -> "{\"name\":\"" + user + "\"}"));
        server.createContext("/orders/", ex -> app.downstream(ex, user -> "[\"book\",\"lamp\"]"));
        server.createContext("/recommendations/", ex -> app.downstream(ex, user -> "[\"desk\"]"));
        server.createContext("/shipping/", ex -> app.downstream(ex, items ->
                s"{\"items\":$items,\"cents\":${Integer.parseInt(items) * 499}}"));
        server.start();
        return app;
    }

    public URI uri() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // GET /dashboard/{user}
    private void dashboard(HttpExchange exchange) {
        given RequestContext request = RequestContext.from(exchange);
        exchange.getResponseHeaders().set(RequestContext.TRACE_HEADER, request.traceId());
        try {
            respond(exchange, 200, dashboard(lastSegment(exchange)).call());   // `request` passed for us
        } catch (Exception e) {
            respond(exchange, 502, s"{\"error\":\"${e.getMessage()}\"}");
        }
    }

    record Parts(String profile, String orders, String recommendations) {}

    /** Describes the dashboard for {@code user}; no request is made until {@code call()}. */
    Callable<String> dashboard(String user) using RequestContext request {
        return forj {
            parts <- Par.mapN(
                    get("/profile/" + user),
                    get("/orders/" + user),
                    get("/recommendations/" + user),
                    Parts::new);
            shipping <- get("/shipping/" + itemCount(parts.orders()));   // needs the orders first
        } yield s"{\"profile\":${parts.profile()},\"orders\":${parts.orders()}"
                + s",\"recommendations\":${parts.recommendations()},\"shipping\":$shipping}";
    }

    /** Items in a JSON array of strings, e.g. {@code ["book","lamp"]} has 2. */
    static int itemCount(String jsonArray) {
        String inner = jsonArray.strip();
        inner = inner.substring(1, inner.length() - 1).strip();
        return inner.isEmpty() ? 0 : inner.split(",").length;
    }

    private Callable<String> get(String path) using RequestContext request {
        return () -> {
            HttpRequest http = HttpRequest.newBuilder(uri().resolve(path))
                    .header(RequestContext.TRACE_HEADER, request.traceId())
                    .build();
            HttpResponse<String> response = client.send(http, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(path + " returned " + response.statusCode());
            }
            return response.body();
        };
    }

    int downstreamCalls() {
        return downstreamCalls.get();
    }

    /** The trace ID each downstream call arrived with, in arrival order. */
    List<String> downstreamTraceIds() {
        return List.copyOf(downstreamTraceIds);
    }

    /** A pretend remote service: answers after {@link #LATENCY}. */
    private void downstream(HttpExchange exchange, java.util.function.Function<String, String> body) {
        downstreamCalls.incrementAndGet();
        downstreamTraceIds.add(String.valueOf(exchange.getRequestHeaders().getFirst(RequestContext.TRACE_HEADER)));
        try {
            Thread.sleep(LATENCY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        respond(exchange, 200, body.apply(lastSegment(exchange)));
    }

    private static String lastSegment(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static void respond(HttpExchange exchange, int status, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException e) {
            // client went away; nothing left to tell it
        }
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        DashboardServer app = start(port);
        System.out.println("Listening on " + app.uri() + "  (try " + app.uri().resolve("/dashboard/ana") + ")");
    }
}

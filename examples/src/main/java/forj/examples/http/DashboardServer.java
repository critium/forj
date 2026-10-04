package forj.examples.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An HTTP API whose handler calls three downstream services and combines the answers
 * with a forj comprehension over {@link Callable}.
 *
 * <pre>
 * GET /dashboard/{user}  ->  GET /profile/{user}          \
 *                            GET /orders/{user}           |-- one after another
 *                            GET /recommendations/{user}  /
 * </pre>
 *
 * Everything is lazy: {@link #dashboard} only describes the calls, and they happen when
 * the handler invokes {@code call()}, on the request's virtual thread. The downstream
 * services are served by this same process with an artificial {@link #LATENCY}.
 *
 * <p>Run: {@code ./mill examples.runMain forj.examples.http.DashboardServer}, then
 * {@code curl localhost:8080/dashboard/ana}.
 */
public final class DashboardServer implements AutoCloseable {

    static final Duration LATENCY = Duration.ofMillis(200);

    private final HttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicInteger downstreamCalls = new AtomicInteger();

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
        try {
            respond(exchange, 200, dashboard(lastSegment(exchange)).call());
        } catch (Exception e) {
            respond(exchange, 502, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /** Describes the dashboard for {@code user}; no request is made until {@code call()}. */
    Callable<String> dashboard(String user) {
        return forj {
            profile <- get("/profile/" + user);
            orders <- get("/orders/" + user);
            recommendations <- get("/recommendations/" + user);
        } yield "{\"profile\":" + profile
                + ",\"orders\":" + orders
                + ",\"recommendations\":" + recommendations + "}";
    }

    private Callable<String> get(String path) {
        return () -> {
            HttpRequest request = HttpRequest.newBuilder(uri().resolve(path)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(path + " returned " + response.statusCode());
            }
            return response.body();
        };
    }

    int downstreamCalls() {
        return downstreamCalls.get();
    }

    /** A pretend remote service: answers after {@link #LATENCY}. */
    private void downstream(HttpExchange exchange, java.util.function.Function<String, String> body) {
        downstreamCalls.incrementAndGet();
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

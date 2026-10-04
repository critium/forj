package forj.examples.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DashboardServerTest {

    private static final String EXPECTED =
            "{\"profile\":{\"name\":\"ana\"},\"orders\":[\"book\",\"lamp\"],\"recommendations\":[\"desk\"]}";

    @Test
    void servesTheCombinedDashboard() throws Exception {
        try (DashboardServer server = DashboardServer.start(0)) {
            var request = HttpRequest.newBuilder(server.uri().resolve("/dashboard/ana")).build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(EXPECTED, response.body());
            assertEquals(3, server.downstreamCalls());
        }
    }

    @Test
    void nothingRunsUntilCalledAndEachCallRunsAgain() throws Exception {
        try (DashboardServer server = DashboardServer.start(0)) {
            var dashboard = server.dashboard("ana");
            assertEquals(0, server.downstreamCalls());

            long started = System.nanoTime();
            assertEquals(EXPECTED, dashboard.call());
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            assertEquals(3, server.downstreamCalls());
            // one after another
            assertTrue(took.compareTo(DashboardServer.LATENCY.multipliedBy(3)) >= 0, "took " + took);

            dashboard.call();
            assertEquals(6, server.downstreamCalls());
        }
    }
}

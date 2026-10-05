package forj.examples.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardServerTest {

    private static final String EXPECTED =
            "{\"profile\":{\"name\":\"ana\"},\"orders\":[\"book\",\"lamp\"],\"recommendations\":[\"desk\"]"
                    + ",\"shipping\":{\"items\":2,\"cents\":998}}";

    @Test
    void servesTheCombinedDashboard() throws Exception {
        try (DashboardServer server = DashboardServer.start(0)) {
            var http = HttpRequest.newBuilder(server.uri().resolve("/dashboard/ana"))
                    .header("X-Trace-Id", "trace-42")
                    .build();
            var response = HttpClient.newHttpClient().send(http, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(EXPECTED, response.body());
            assertEquals(4, server.downstreamCalls());
            // the context reached every downstream call, parallel ones included
            assertEquals(List.of("trace-42", "trace-42", "trace-42", "trace-42"), server.downstreamTraceIds());
            assertEquals("trace-42", response.headers().firstValue("X-Trace-Id").orElseThrow());
        }
    }

    @Test
    void countsItemsInAJsonArray() {
        assertEquals(2, DashboardServer.itemCount("[\"book\",\"lamp\"]"));
        assertEquals(0, DashboardServer.itemCount("[]"));
    }

    @Test
    void nothingRunsUntilCalledAndEachCallRunsAgain() throws Exception {
        try (DashboardServer server = DashboardServer.start(0)) {
            given RequestContext request = new RequestContext("test-run");
            var dashboard = server.dashboard("ana");
            assertEquals(0, server.downstreamCalls());

            long started = System.nanoTime();
            assertEquals(EXPECTED, dashboard.call());
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            assertEquals(4, server.downstreamCalls());
            // three in parallel, then shipping: two rounds, where one after another would be four
            assertTrue(took.compareTo(DashboardServer.LATENCY.multipliedBy(2)) >= 0, "took " + took);
            assertTrue(took.compareTo(DashboardServer.LATENCY.multipliedBy(3)) < 0, "took " + took);

            dashboard.call();
            assertEquals(8, server.downstreamCalls());
        }
    }
}

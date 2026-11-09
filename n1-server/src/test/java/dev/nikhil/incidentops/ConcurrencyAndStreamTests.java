package dev.nikhil.incidentops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import dev.nikhil.incidentops.domain.EventType;
import dev.nikhil.incidentops.domain.Incident;
import dev.nikhil.incidentops.domain.IncidentEventRepository;
import dev.nikhil.incidentops.domain.Status;
import dev.nikhil.incidentops.stream.SseHub;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Real Tomcat on a random port + real HTTP clients, because MockMvc would hide the threading. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class ConcurrencyAndStreamTests {
    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;
    @Autowired IncidentEventRepository events;
    @Autowired SseHub hub;

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    String base;

    @BeforeEach
    void setUp() {
        base = "http://localhost:" + env.getProperty("local.server.port") + "/api";
        jdbc.update("delete from incident_event");
        jdbc.update("delete from incident");
    }

    HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json");
        b = json == null ? b.GET() : b.method(method, HttpRequest.BodyPublishers.ofString(json));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    String createIncident() throws Exception {
        HttpResponse<String> r = send("POST", "/incidents", "{\"serviceName\":\"Race\",\"severity\":\"HIGH\",\"summary\":\"x\"}");
        assertThat(r.statusCode()).isEqualTo(201);
        return JsonPath.read(r.body(), "$.id");
    }

    @Test
    void eightSimultaneousAcksExactlyOneWins() throws Exception {
        String id = createIncident();
        int n = 8;
        CyclicBarrier start = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int who = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return send("PATCH", "/incidents/" + id + "/status",
                        "{\"status\":\"ACKNOWLEDGED\",\"actor\":\"person-" + who + "\"}");
                }));
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<HttpResponse<String>> f : futures) codes.add(f.get(20, TimeUnit.SECONDS).statusCode());
            assertThat(codes).filteredOn(c -> c == 200).hasSize(1);
            assertThat(codes).filteredOn(c -> c == 409).hasSize(n - 1);
        } finally {
            pool.shutdownNow();
        }
        // and the audit trail agrees: one ack, not eight
        assertThat(events.countByIncidentIdAndType(UUID.fromString(id), EventType.ACKNOWLEDGED)).isEqualTo(1);
    }

    @Test
    void versionColumnRejectsTheSecondOfTwoInterleavedWriters() throws Exception {
        UUID id = UUID.fromString(createIncident());
        EntityManager alice = emf.createEntityManager();
        EntityManager bob = emf.createEntityManager();
        try {
            alice.getTransaction().begin();
            bob.getTransaction().begin();
            Incident a = alice.find(Incident.class, id);   // both read version 0
            Incident b = bob.find(Incident.class, id);
            a.transitionTo(Status.ACKNOWLEDGED, java.time.Instant.now(), null);
            b.transitionTo(Status.ACKNOWLEDGED, java.time.Instant.now(), null);
            alice.getTransaction().commit();               // version 0 -> 1
            assertThatThrownBy(() -> bob.getTransaction().commit())
                .isInstanceOf(PersistenceException.class)
                .satisfies(e -> assertThat(causeChain(e)).anyMatch(c -> c instanceof OptimisticLockException
                    || c.getClass().getSimpleName().contains("StaleObjectState")));
        } finally {
            alice.close();
            bob.close();
        }
    }

    @Test
    void dashboardStreamReceivesAckMadeBySomeoneElse() throws Exception {
        String id = createIncident();
        HttpRequest subscribe = HttpRequest.newBuilder(URI.create(base + "/stream"))
            .header("Accept", "text/event-stream").GET().build();
        HttpResponse<Stream<String>> stream = http.sendAsync(subscribe, HttpResponse.BodyHandlers.ofLines())
            .get(5, TimeUnit.SECONDS);
        assertThat(stream.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        try {
            CompletableFuture<List<String>> received = CompletableFuture.supplyAsync(() -> {
                List<String> lines = new ArrayList<>();
                var it = stream.body().iterator();
                while (it.hasNext()) {
                    String line = it.next();
                    lines.add(line);
                    if (line.startsWith("data:") && line.contains("\"ACKNOWLEDGED\"") && lines.stream().anyMatch(l -> l.equals("event:incident.acknowledged"))) {
                        return lines;
                    }
                }
                return lines;
            });
            assertThat(hub.subscriberCount()).isGreaterThanOrEqualTo(1);
            assertThat(send("PATCH", "/incidents/" + id + "/status", "{\"status\":\"ACKNOWLEDGED\",\"actor\":\"other-tab\"}")
                .statusCode()).isEqualTo(200);
            List<String> lines = received.get(5, TimeUnit.SECONDS);
            assertThat(lines).contains("event:hello", "event:incident.acknowledged");
            assertThat(String.join("\n", lines)).contains(id).contains("other-tab");
        } finally {
            stream.body().close();
        }
    }

    @Test
    void rejectedChangesAreNotBroadcast() throws Exception {
        String id = createIncident();
        HttpResponse<Stream<String>> stream = http.sendAsync(
            HttpRequest.newBuilder(URI.create(base + "/stream")).GET().build(), HttpResponse.BodyHandlers.ofLines())
            .get(5, TimeUnit.SECONDS);
        try {
            CompletableFuture<List<String>> received = CompletableFuture.supplyAsync(() -> {
                List<String> lines = new ArrayList<>();
                var it = stream.body().iterator();
                while (it.hasNext()) {
                    String line = it.next();
                    lines.add(line);
                    if (line.startsWith("event:incident.commented")) return lines;
                }
                return lines;
            });
            // illegal (OPEN -> RESOLVED) then a legal comment as a marker
            assertThat(send("PATCH", "/incidents/" + id + "/status",
                "{\"status\":\"RESOLVED\",\"postmortem\":{\"rootCause\":\"a\",\"impact\":\"b\",\"followUp\":\"c\"}}").statusCode())
                .isEqualTo(409);
            assertThat(send("POST", "/incidents/" + id + "/comments", "{\"author\":\"t\",\"message\":\"marker\"}").statusCode())
                .isEqualTo(201);
            List<String> lines = received.get(5, TimeUnit.SECONDS);
            assertThat(lines).doesNotContain("event:incident.resolved").contains("event:incident.commented");
        } finally {
            stream.body().close();
        }
    }

    static List<Throwable> causeChain(Throwable t) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable c = t; c != null && !chain.contains(c); c = c.getCause()) chain.add(c);
        return chain;
    }
}

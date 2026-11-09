package dev.nikhil.incidentops;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class IncidentApiTests extends ApiTestSupport {

    @Test
    void createReturns201WithLocationAssigneeAndSlaDeadline() throws Exception {
        mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceName\":\"  Trading API \",\"severity\":\"CRITICAL\",\"summary\":\"Elevated errors\"}"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", startsWith("/api/incidents/")))
            .andExpect(jsonPath("$.serviceName").value("Trading API"))
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.assignee").exists())
            .andExpect(jsonPath("$.ackDueAt").value("2026-01-01T00:05:00Z"))
            .andExpect(jsonPath("$.allowedNext[0]").value("ACKNOWLEDGED"))
            .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void createWritesCreatedEventToTimeline() throws Exception {
        String id = create("Billing", "LOW", "Slow invoices");
        mvc.perform(get("/api/incidents/" + id + "/events"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].type").value("CREATED"));
    }

    @Test
    void validationErrorsAreProblemJsonWithFieldErrors() throws Exception {
        mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceName\":\"\",\"summary\":\"bad\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("/problems/validation"))
            .andExpect(jsonPath("$.errors.serviceName").exists())
            .andExpect(jsonPath("$.errors.severity").exists());
    }

    @Test
    void unknownSeverityIs400Problem() throws Exception {
        mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceName\":\"x\",\"severity\":\"APOCALYPTIC\",\"summary\":\"y\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unknownIncidentIs404Problem() throws Exception {
        mvc.perform(get("/api/incidents/" + UUID.randomUUID()))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("/problems/not-found"));
        mvc.perform(post("/api/incidents/" + UUID.randomUUID() + "/comments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"author\":\"a\",\"message\":\"m\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void malformedIdIs400() throws Exception {
        mvc.perform(get("/api/incidents/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void paginationReturnsNewestFirstWithMetadata() throws Exception {
        for (int i = 1; i <= 5; i++) {
            create("svc-" + i, "LOW", "issue " + i);
            clock.advance(Duration.ofSeconds(1));
        }
        mvc.perform(get("/api/incidents?page=0&size=2"))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].serviceName").value("svc-5"))
            .andExpect(jsonPath("$.totalElements").value(5))
            .andExpect(jsonPath("$.totalPages").value(3));
        mvc.perform(get("/api/incidents?page=2&size=2&sort=oldest"))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].serviceName").value("svc-5"));
        mvc.perform(get("/api/incidents?size=100000")).andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void searchMatchesServiceOrSummaryCaseInsensitively() throws Exception {
        create("Payments API", "HIGH", "card declines");
        create("Search", "LOW", "slow PAYMENT lookup");
        create("Auth", "LOW", "login loop");
        mvc.perform(get("/api/incidents?q=payment")).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/incidents?q=100%25")).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void filtersByStatusAndSeverity() throws Exception {
        String a = create("A", "HIGH", "x");
        create("B", "HIGH", "y");
        create("C", "LOW", "z");
        ack(a).andExpect(status().isOk());
        mvc.perform(get("/api/incidents?severity=HIGH")).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/incidents?severity=HIGH&status=OPEN"))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].serviceName").value("B"));
    }

    @Test
    void illegalTransitionIs409WithAllowedNext() throws Exception {
        String id = create("Scheduler", "CRITICAL", "Queue stalled");
        resolve(id)
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("/problems/illegal-transition"))
            .andExpect(jsonPath("$.currentStatus").value("OPEN"))
            .andExpect(jsonPath("$.allowedNext[0]").value("ACKNOWLEDGED"));
    }

    @Test
    void staleExpectedVersionIs409AndChangesNothing() throws Exception {
        String id = create("Edge", "HIGH", "TLS errors");
        changeStatus(id, "{\"status\":\"ACKNOWLEDGED\",\"expectedVersion\":0}").andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1));
        // second person still looking at version 0 tries to reassign
        mvc.perform(patch("/api/incidents/" + id + "/assignee").contentType(MediaType.APPLICATION_JSON)
                .content("{\"assignee\":\"someone\",\"expectedVersion\":0}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("/problems/version-conflict"))
            .andExpect(jsonPath("$.currentVersion").value(1));
        mvc.perform(get("/api/incidents/" + id)).andExpect(jsonPath("$.assignee").value(org.hamcrest.Matchers.not("someone")));
    }

    @Test
    void resolveWithoutPostmortemIs422AndIncompletePostmortemIs400() throws Exception {
        String id = create("DB", "MEDIUM", "replica lag");
        ack(id);
        changeStatus(id, "{\"status\":\"RESOLVED\"}")
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.type").value("/problems/postmortem-required"));
        changeStatus(id, "{\"status\":\"RESOLVED\",\"postmortem\":{\"rootCause\":\"x\",\"impact\":\"\",\"followUp\":\"y\"}}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors['postmortem.impact']").exists());
        mvc.perform(get("/api/incidents/" + id)).andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
    }

    @Test
    void fullLifecycleBuildsOrderedAppendOnlyTimeline() throws Exception {
        String id = create("Checkout", "HIGH", "500s");
        clock.advance(Duration.ofSeconds(90));
        ack(id).andExpect(jsonPath("$.acknowledgedAt").value("2026-01-01T00:01:30Z"));
        mvc.perform(post("/api/incidents/" + id + "/comments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"author\":\"tester\",\"message\":\"rolling back\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.type").value("COMMENTED"));
        clock.advance(Duration.ofMinutes(5));
        resolve(id).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RESOLVED"))
            .andExpect(jsonPath("$.postmortem.followUp").value("add canary"))
            .andExpect(jsonPath("$.allowedNext.length()").value(0));
        mvc.perform(get("/api/incidents/" + id + "/events"))
            .andExpect(jsonPath("$[*].type").value(org.hamcrest.Matchers.contains("CREATED", "ACKNOWLEDGED", "COMMENTED", "RESOLVED")))
            .andExpect(jsonPath("$[1].message").value(startsWith("Acknowledged 90s")))
            .andExpect(jsonPath("$[3].message").value(endsWith("bad deploy")));
    }

    @Test
    void resolvedIsTerminalAndCannotBeReassigned() throws Exception {
        String id = create("CDN", "LOW", "stale cache");
        ack(id);
        resolve(id);
        ack(id).andExpect(status().isConflict());
        resolve(id).andExpect(status().isConflict());
        mvc.perform(patch("/api/incidents/" + id + "/assignee").contentType(MediaType.APPLICATION_JSON)
                .content("{\"assignee\":\"x\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void blankCommentIs400() throws Exception {
        String id = create("Mail", "LOW", "bounces");
        mvc.perform(post("/api/incidents/" + id + "/comments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"author\":\"tester\",\"message\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.message").exists());
    }

    @Test
    void reassignBumpsVersionAndWritesAssignedEvent() throws Exception {
        String id = create("Queue", "MEDIUM", "backlog");
        mvc.perform(patch("/api/incidents/" + id + "/assignee").contentType(MediaType.APPLICATION_JSON)
                .content("{\"assignee\":\"responder-c\",\"expectedVersion\":0,\"actor\":\"lead\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assignee").value("responder-c"))
            .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/incidents?assignee=responder-c")).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/incidents/" + id + "/events"))
            .andExpect(jsonPath("$[1].type").value("ASSIGNED"))
            .andExpect(jsonPath("$[1].actor").value("lead"));
    }

    @Test
    void statsComputeMttaAndMttrFromTimestamps() throws Exception {
        String a = create("A", "HIGH", "x");          // t=0
        String b = create("B", "HIGH", "y");          // t=0
        create("C", "LOW", "never acked");
        clock.advance(Duration.ofMinutes(2));
        ack(a);                                       // A: TTA 120s
        clock.advance(Duration.ofMinutes(2));
        ack(b);                                       // B: TTA 240s
        clock.advance(Duration.ofMinutes(6));
        resolve(a);                                   // A: TTR 600s
        mvc.perform(get("/api/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mttaMeanSeconds").value(180.0))
            .andExpect(jsonPath("$.mttaP50Seconds").value(120))
            .andExpect(jsonPath("$.mttrMeanSeconds").value(600.0))
            .andExpect(jsonPath("$.sampleAcknowledged").value(2))
            .andExpect(jsonPath("$.sampleResolved").value(1))
            .andExpect(jsonPath("$.byStatus.OPEN").value(1))
            .andExpect(jsonPath("$.open").value(1));
    }

    @Test
    void statsAreNullNotZeroWhenThereIsNoData() throws Exception {
        mvc.perform(get("/api/stats"))
            .andExpect(jsonPath("$.mttaMeanSeconds").doesNotExist())
            .andExpect(jsonPath("$.byStatus.RESOLVED").value(0));
    }

    @Test
    void legacyMetricsEndpointStillWorks() throws Exception {
        create("A", "LOW", "x");
        mvc.perform(get("/api/metrics"))
            .andExpect(jsonPath("$.OPEN").value(1))
            .andExpect(jsonPath("$.ACKNOWLEDGED").value(0))
            .andExpect(jsonPath("$.RESOLVED").value(0));
    }

    @Test
    void oncallEndpointExposesRotationAndSla() throws Exception {
        mvc.perform(get("/api/oncall"))
            .andExpect(jsonPath("$.rotation").value(containsInAnyOrder("responder-a", "responder-b", "responder-c")))
            .andExpect(jsonPath("$.ackSlaSeconds.CRITICAL").value(300))
            .andExpect(jsonPath("$.ackSlaSeconds.LOW").value(14400));
    }
}

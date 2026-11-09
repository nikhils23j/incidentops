package dev.nikhil.incidentops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.jayway.jsonpath.JsonPath;
import dev.nikhil.incidentops.service.SlaEscalator;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The @Scheduled job is effectively off in tests; we call it directly and move the fake clock instead. */
class SlaEscalationTests extends ApiTestSupport {
    @Autowired SlaEscalator escalator;

    String assigneeOf(String id) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/incidents/" + id)).andReturn().getResponse().getContentAsString(), "$.assignee");
    }

    @Test
    void notEscalatedBeforeDeadlineEscalatedAfter() throws Exception {
        String id = create("Payments", "CRITICAL", "timeouts");   // 5 min SLA
        String first = assigneeOf(id);
        clock.advance(Duration.ofMinutes(4));
        assertThat(escalator.escalateOverdue()).isZero();
        mvc.perform(get("/api/incidents/" + id)).andExpect(jsonPath("$.slaBreached").value(false));

        clock.advance(Duration.ofMinutes(2));
        mvc.perform(get("/api/incidents/" + id)).andExpect(jsonPath("$.slaBreached").value(true));
        assertThat(escalator.escalateOverdue()).isEqualTo(1);
        mvc.perform(get("/api/incidents/" + id))
            .andExpect(jsonPath("$.escalationLevel").value(1))
            .andExpect(jsonPath("$.slaBreached").value(false))
            .andExpect(jsonPath("$.ackDueAt").value("2026-01-01T00:11:00Z"));
        assertThat(assigneeOf(id)).isNotEqualTo(first);
        mvc.perform(get("/api/incidents/" + id + "/events"))
            .andExpect(jsonPath("$[1].type").value("ESCALATED"))
            .andExpect(jsonPath("$[1].actor").value("sla-bot"));
    }

    @Test
    void acknowledgedIncidentsAreNeverEscalated() throws Exception {
        String id = create("Payments", "CRITICAL", "timeouts");
        clock.advance(Duration.ofMinutes(3));
        ack(id);
        clock.advance(Duration.ofHours(1));
        assertThat(escalator.escalateOverdue()).isZero();
        mvc.perform(get("/api/incidents/" + id)).andExpect(jsonPath("$.escalationLevel").value(0));
    }

    @Test
    void escalatesAgainEachMissedWindowUpToTheCap() throws Exception {
        String id = create("Search", "HIGH", "slow");             // 15 min SLA, cap 3
        for (int i = 0; i < 6; i++) {
            clock.advance(Duration.ofMinutes(16));
            escalator.escalateOverdue();
        }
        mvc.perform(get("/api/incidents/" + id)).andExpect(jsonPath("$.escalationLevel").value(3));
        mvc.perform(get("/api/stats")).andExpect(jsonPath("$.escalatedTotal").value(3));
    }

    @Test
    void slaWindowDependsOnSeverity() throws Exception {
        String low = create("Docs", "LOW", "typo");                // 4 h
        String crit = create("Core", "CRITICAL", "down");          // 5 min
        clock.advance(Duration.ofMinutes(30));
        assertThat(escalator.escalateOverdue()).isEqualTo(1);
        mvc.perform(get("/api/incidents/" + low)).andExpect(jsonPath("$.escalationLevel").value(0));
        mvc.perform(get("/api/incidents/" + crit)).andExpect(jsonPath("$.escalationLevel").value(1));
    }

    @Test
    void statsCountOpenSlaBreaches() throws Exception {
        create("A", "CRITICAL", "x");
        create("B", "LOW", "y");
        clock.advance(Duration.ofMinutes(10));
        mvc.perform(get("/api/stats")).andExpect(jsonPath("$.slaBreachedOpen").value(1));
    }
}

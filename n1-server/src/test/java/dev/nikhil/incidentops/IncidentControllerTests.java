package dev.nikhil.incidentops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:incidentops-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class IncidentControllerTests {
    @Autowired MockMvc mvc;
    @Autowired IncidentRepository repository;

    @BeforeEach
    void clear() { repository.deleteAll(); }

    @Test
    void createsFiltersAndCountsIncidents() throws Exception {
        mvc.perform(post("/api/incidents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceName\":\"Trading API\",\"severity\":\"HIGH\",\"summary\":\"Elevated errors\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(get("/api/incidents?status=OPEN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/metrics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.OPEN").value(1))
            .andExpect(jsonPath("$.RESOLVED").value(0));
    }

    @Test
    void validatesInputAndRejectsIllegalTransitions() throws Exception {
        mvc.perform(post("/api/incidents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceName\":\"\",\"severity\":\"HIGH\",\"summary\":\"bad\"}"))
            .andExpect(status().isBadRequest());

        Incident incident = repository.save(new Incident("Scheduler", Severity.CRITICAL, "Queue stalled"));
        mvc.perform(patch("/api/incidents/" + incident.getId() + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"RESOLVED\"}"))
            .andExpect(status().isConflict());
        mvc.perform(patch("/api/incidents/" + incident.getId() + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACKNOWLEDGED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
        mvc.perform(patch("/api/incidents/" + incident.getId() + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"RESOLVED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RESOLVED"));
    }
}

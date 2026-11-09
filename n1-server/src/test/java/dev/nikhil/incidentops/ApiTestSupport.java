package dev.nikhil.incidentops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
abstract class ApiTestSupport {
    static final String PM = "{\"rootCause\":\"bad deploy\",\"impact\":\"5xx for 4 min\",\"followUp\":\"add canary\"}";

    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("delete from incident_event");
        jdbc.update("delete from incident");
        clock.set(MutableClock.START);
    }

    String create(String service, String severity, String summary) throws Exception {
        String body = mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceName\":\"" + service + "\",\"severity\":\"" + severity + "\",\"summary\":\"" + summary + "\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    ResultActions changeStatus(String id, String json) throws Exception {
        return mvc.perform(patch("/api/incidents/" + id + "/status").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions ack(String id) throws Exception { return changeStatus(id, "{\"status\":\"ACKNOWLEDGED\",\"actor\":\"tester\"}"); }

    ResultActions resolve(String id) throws Exception {
        return changeStatus(id, "{\"status\":\"RESOLVED\",\"actor\":\"tester\",\"postmortem\":" + PM + "}");
    }
}

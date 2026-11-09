package dev.nikhil.incidentops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nikhil.incidentops.config.IncidentopsProperties;
import dev.nikhil.incidentops.domain.Severity;
import dev.nikhil.incidentops.service.OnCallRotation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OnCallRotationTest {
    final OnCallRotation rotation = new OnCallRotation(new IncidentopsProperties(List.of("a", "b", "c"), null, null));

    @Test
    void assignsRoundRobin() {
        assertThat(List.of(rotation.next(), rotation.next(), rotation.next(), rotation.next()))
            .containsExactly("a", "b", "c", "a");
    }

    @Test
    void escalationGoesToNextPersonAndWraps() {
        assertThat(rotation.after("a")).isEqualTo("b");
        assertThat(rotation.after("c")).isEqualTo("a");
        assertThat(rotation.after("someone-manual")).isEqualTo("a");
    }

    @Test
    void propertiesFillDefaultsAndAllowOverrides() {
        IncidentopsProperties p = new IncidentopsProperties(null, Map.of(Severity.CRITICAL, Duration.ofSeconds(10)), null);
        assertThat(p.slaFor(Severity.CRITICAL)).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.slaFor(Severity.LOW)).isEqualTo(Duration.ofHours(4));
        assertThat(p.maxEscalationLevel()).isEqualTo(3);
        assertThat(p.oncall()).hasSize(3);
    }
}

package dev.nikhil.incidentops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nikhil.incidentops.domain.IllegalTransitionException;
import dev.nikhil.incidentops.domain.Incident;
import dev.nikhil.incidentops.domain.Postmortem;
import dev.nikhil.incidentops.domain.PostmortemRequiredException;
import dev.nikhil.incidentops.domain.Severity;
import dev.nikhil.incidentops.domain.Status;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Pure unit tests, no Spring: every (from, to) pair of the state machine. */
class IncidentStateMachineTest {
    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    static final Postmortem PM = new Postmortem("cause", "impact", "follow-up");

    static Incident incidentIn(Status status) {
        Incident i = new Incident("api", Severity.HIGH, "errors", "responder-a", T0, T0.plus(Duration.ofMinutes(15)));
        if (status != Status.OPEN) i.transitionTo(Status.ACKNOWLEDGED, T0.plusSeconds(60), null);
        if (status == Status.RESOLVED) i.transitionTo(Status.RESOLVED, T0.plusSeconds(600), PM);
        return i;
    }

    @ParameterizedTest(name = "{0} -> {1} legal={2}")
    @CsvSource({
        "OPEN, OPEN, false", "OPEN, ACKNOWLEDGED, true", "OPEN, RESOLVED, false",
        "ACKNOWLEDGED, OPEN, false", "ACKNOWLEDGED, ACKNOWLEDGED, false", "ACKNOWLEDGED, RESOLVED, true",
        "RESOLVED, OPEN, false", "RESOLVED, ACKNOWLEDGED, false", "RESOLVED, RESOLVED, false"
    })
    void everyTransitionPair(Status from, Status to, boolean legal) {
        Incident i = incidentIn(from);
        if (legal) {
            i.transitionTo(to, T0.plusSeconds(900), PM);
            assertThat(i.getStatus()).isEqualTo(to);
        } else {
            assertThatThrownBy(() -> i.transitionTo(to, T0.plusSeconds(900), PM))
                .isInstanceOf(IllegalTransitionException.class);
            assertThat(i.getStatus()).isEqualTo(from);
        }
    }

    @Test
    void transitionsStampTimes() {
        Incident i = incidentIn(Status.RESOLVED);
        assertThat(i.getAcknowledgedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(i.getResolvedAt()).isEqualTo(T0.plusSeconds(600));
        assertThat(i.getPostmortem()).isEqualTo(PM);
    }

    @Test
    void resolvingWithoutPostmortemIsRejectedAndLeavesStateAlone() {
        Incident i = incidentIn(Status.ACKNOWLEDGED);
        assertThatThrownBy(() -> i.transitionTo(Status.RESOLVED, T0, null)).isInstanceOf(PostmortemRequiredException.class);
        assertThat(i.getStatus()).isEqualTo(Status.ACKNOWLEDGED);
        assertThat(i.getResolvedAt()).isNull();
    }

    @Test
    void onlyOpenIncidentsAreOverdueOrEscalatable() {
        Incident open = incidentIn(Status.OPEN);
        assertThat(open.isAckOverdue(T0.plus(Duration.ofMinutes(14)))).isFalse();
        assertThat(open.isAckOverdue(T0.plus(Duration.ofMinutes(16)))).isTrue();
        Incident acked = incidentIn(Status.ACKNOWLEDGED);
        assertThat(acked.isAckOverdue(T0.plus(Duration.ofHours(5)))).isFalse();
        assertThatThrownBy(() -> acked.escalate("b", T0, T0)).isInstanceOf(IllegalTransitionException.class);
    }
}

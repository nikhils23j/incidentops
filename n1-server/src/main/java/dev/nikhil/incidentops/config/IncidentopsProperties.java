package dev.nikhil.incidentops.config;

import dev.nikhil.incidentops.domain.Severity;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param oncall             responders in rotation order (placeholder names, there is no real paging)
 * @param ackSla             how long each severity may stay OPEN before the SLA job escalates it
 * @param maxEscalationLevel stop escalating after this many levels
 */
@ConfigurationProperties("incidentops")
public record IncidentopsProperties(List<String> oncall, Map<Severity, Duration> ackSla, Integer maxEscalationLevel) {
    public IncidentopsProperties {
        if (oncall == null || oncall.isEmpty()) oncall = List.of("responder-a", "responder-b", "responder-c");
        Map<Severity, Duration> sla = new EnumMap<>(Map.of(
            Severity.CRITICAL, Duration.ofMinutes(5),
            Severity.HIGH, Duration.ofMinutes(15),
            Severity.MEDIUM, Duration.ofHours(1),
            Severity.LOW, Duration.ofHours(4)));
        if (ackSla != null) sla.putAll(ackSla);
        ackSla = Map.copyOf(sla);
        if (maxEscalationLevel == null) maxEscalationLevel = 3;
    }

    public Duration slaFor(Severity severity) { return ackSla.get(severity); }
}

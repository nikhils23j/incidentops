package dev.nikhil.incidentops.api;

import dev.nikhil.incidentops.domain.EventType;
import dev.nikhil.incidentops.domain.Incident;
import dev.nikhil.incidentops.domain.IncidentEvent;
import dev.nikhil.incidentops.domain.Postmortem;
import dev.nikhil.incidentops.domain.Severity;
import dev.nikhil.incidentops.domain.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Request/response shapes. Entities never leave the service layer as JSON. */
public final class Dtos {
    private Dtos() {}

    public record CreateIncident(
        @NotBlank @Size(max = 80) String serviceName,
        @NotNull Severity severity,
        @NotBlank @Size(max = 240) String summary,
        @Size(max = 60) String reporter
    ) {}

    public record PostmortemInput(
        @NotBlank @Size(max = 1000) String rootCause,
        @NotBlank @Size(max = 1000) String impact,
        @NotBlank @Size(max = 1000) String followUp
    ) {
        public Postmortem toDomain() { return new Postmortem(rootCause.trim(), impact.trim(), followUp.trim()); }
    }

    /** expectedVersion is optional; when sent, a stale value is rejected with 409 before anything changes. */
    public record ChangeStatus(
        @NotNull Status status,
        Long expectedVersion,
        @Size(max = 60) String actor,
        @Valid PostmortemInput postmortem
    ) {}

    public record ChangeAssignee(@NotBlank @Size(max = 60) String assignee, Long expectedVersion, @Size(max = 60) String actor) {}

    public record AddComment(@NotBlank @Size(max = 60) String author, @NotBlank @Size(max = 2000) String message) {}

    public record IncidentView(
        UUID id, String serviceName, Severity severity, Status status, String summary, String assignee,
        Instant createdAt, Instant updatedAt, Instant acknowledgedAt, Instant resolvedAt,
        Instant ackDueAt, boolean slaBreached, int escalationLevel, Set<Status> allowedNext,
        Postmortem postmortem, long version
    ) {
        public static IncidentView of(Incident i, Instant now) {
            return new IncidentView(i.getId(), i.getServiceName(), i.getSeverity(), i.getStatus(), i.getSummary(),
                i.getAssignee(), i.getCreatedAt(), i.getUpdatedAt(), i.getAcknowledgedAt(), i.getResolvedAt(),
                i.getAckDueAt(), i.isAckOverdue(now), i.getEscalationLevel(), i.getStatus().allowedNext(),
                i.getPostmortem(), i.getVersion() == null ? 0 : i.getVersion());
        }
    }

    public record TimelineEntry(long id, UUID incidentId, EventType type, String actor, String message, Instant at) {
        public static TimelineEntry of(IncidentEvent e) {
            return new TimelineEntry(e.getId(), e.getIncidentId(), e.getType(), e.getActor(), e.getMessage(), e.getOccurredAt());
        }
    }

    public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {}

    /** Durations are in seconds; null when there is no data yet. */
    public record Stats(
        Map<Status, Long> byStatus, long open, long slaBreachedOpen, long escalatedTotal,
        Double mttaMeanSeconds, Long mttaP50Seconds, Double mttrMeanSeconds, Long mttrP50Seconds,
        int sampleAcknowledged, int sampleResolved
    ) {}

    public record OnCall(List<String> rotation, Map<Severity, Long> ackSlaSeconds, int maxEscalationLevel) {}
}

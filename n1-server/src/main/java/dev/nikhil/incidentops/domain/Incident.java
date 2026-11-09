package dev.nikhil.incidentops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(indexes = {
    @Index(name = "idx_incident_status", columnList = "status"),
    @Index(name = "idx_incident_created", columnList = "createdAt")
})
public class Incident {
    @Id
    private UUID id;
    @Column(nullable = false, length = 80)
    private String serviceName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;
    @Column(nullable = false, length = 240)
    private String summary;
    @Column(length = 60)
    private String assignee;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant acknowledgedAt;
    private Instant resolvedAt;
    /** When this incident must be acknowledged by before the SLA job escalates it. */
    private Instant ackDueAt;
    private int escalationLevel;
    @Embedded
    private Postmortem postmortem;
    /** Optimistic lock: Hibernate adds "where version = ?" to every update. Null means "not persisted yet". */
    @Version
    private Long version;

    protected Incident() {}

    public Incident(String serviceName, Severity severity, String summary, String assignee, Instant now, Instant ackDueAt) {
        this.id = UUID.randomUUID();
        this.serviceName = serviceName;
        this.severity = severity;
        this.summary = summary;
        this.assignee = assignee;
        this.status = Status.OPEN;
        this.createdAt = now;
        this.updatedAt = now;
        this.ackDueAt = ackDueAt;
    }

    public void transitionTo(Status next, Instant now, Postmortem pm) {
        if (!status.canMoveTo(next)) throw new IllegalTransitionException(status, next);
        if (next == Status.RESOLVED) {
            if (pm == null) throw new PostmortemRequiredException();
            postmortem = pm;
            resolvedAt = now;
        }
        if (next == Status.ACKNOWLEDGED) acknowledgedAt = now;
        status = next;
        updatedAt = now;
    }

    public boolean isAckOverdue(Instant now) {
        return status == Status.OPEN && ackDueAt != null && now.isAfter(ackDueAt);
    }

    public void escalate(String nextAssignee, Instant now, Instant nextDue) {
        if (status != Status.OPEN) throw new IllegalTransitionException(status, status);
        escalationLevel++;
        assignee = nextAssignee;
        ackDueAt = nextDue;
        updatedAt = now;
    }

    public void assignTo(String newAssignee, Instant now) {
        if (status == Status.RESOLVED) throw new IllegalTransitionException(status, status);
        assignee = newAssignee;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getServiceName() { return serviceName; }
    public Severity getSeverity() { return severity; }
    public Status getStatus() { return status; }
    public String getSummary() { return summary; }
    public String getAssignee() { return assignee; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getAckDueAt() { return ackDueAt; }
    public int getEscalationLevel() { return escalationLevel; }
    public Postmortem getPostmortem() { return postmortem; }
    public Long getVersion() { return version; }
}

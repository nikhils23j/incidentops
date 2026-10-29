package dev.nikhil.incidentops;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
public class Incident {
    @Id
    private UUID id;
    private String serviceName;
    @Enumerated(EnumType.STRING)
    private Severity severity;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String summary;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private long version;

    protected Incident() {}

    public Incident(String serviceName, Severity severity, String summary) {
        this.id = UUID.randomUUID();
        this.serviceName = serviceName;
        this.severity = severity;
        this.summary = summary;
        this.status = Status.OPEN;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void moveTo(Status next) {
        if (status == Status.RESOLVED || next == status ||
            (status == Status.OPEN && next != Status.ACKNOWLEDGED) ||
            (status == Status.ACKNOWLEDGED && next != Status.RESOLVED)) {
            throw new IllegalStateException("Allowed transitions: OPEN -> ACKNOWLEDGED -> RESOLVED");
        }
        status = next;
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getServiceName() { return serviceName; }
    public Severity getSeverity() { return severity; }
    public Status getStatus() { return status; }
    public String getSummary() { return summary; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}

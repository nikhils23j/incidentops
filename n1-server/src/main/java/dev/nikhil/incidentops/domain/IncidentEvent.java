package dev.nikhil.incidentops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One row per thing that happened to an incident. Append-only: @Immutable tells Hibernate never to
 * issue UPDATEs for it, and IncidentEventRepository only exposes save + read.
 */
@Entity
@Immutable
@Table(indexes = @Index(name = "idx_event_incident", columnList = "incidentId, occurredAt"))
public class IncidentEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private UUID incidentId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventType type;
    @Column(nullable = false, length = 60)
    private String actor;
    @Column(length = 2000)
    private String message;
    @Column(nullable = false)
    private Instant occurredAt;

    protected IncidentEvent() {}

    public IncidentEvent(UUID incidentId, EventType type, String actor, String message, Instant occurredAt) {
        this.incidentId = incidentId;
        this.type = type;
        this.actor = actor;
        this.message = message;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public UUID getIncidentId() { return incidentId; }
    public EventType getType() { return type; }
    public String getActor() { return actor; }
    public String getMessage() { return message; }
    public Instant getOccurredAt() { return occurredAt; }
}

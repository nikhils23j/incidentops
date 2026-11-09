package dev.nikhil.incidentops.service;

import dev.nikhil.incidentops.api.Dtos.CreateIncident;
import dev.nikhil.incidentops.api.Dtos.IncidentView;
import dev.nikhil.incidentops.api.Dtos.PageResponse;
import dev.nikhil.incidentops.api.Dtos.TimelineEntry;
import dev.nikhil.incidentops.config.IncidentopsProperties;
import dev.nikhil.incidentops.domain.EventType;
import dev.nikhil.incidentops.domain.Incident;
import dev.nikhil.incidentops.domain.IncidentEvent;
import dev.nikhil.incidentops.domain.IncidentEventRepository;
import dev.nikhil.incidentops.domain.IncidentRepository;
import dev.nikhil.incidentops.domain.Postmortem;
import dev.nikhil.incidentops.domain.Severity;
import dev.nikhil.incidentops.domain.Status;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentService {
    public static final int MAX_PAGE_SIZE = 100;
    private static final String SYSTEM = "sla-bot";

    private final IncidentRepository incidents;
    private final IncidentEventRepository events;
    private final OnCallRotation rotation;
    private final IncidentopsProperties props;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;

    public IncidentService(IncidentRepository incidents, IncidentEventRepository events, OnCallRotation rotation,
                           IncidentopsProperties props, Clock clock, ApplicationEventPublisher publisher) {
        this.incidents = incidents;
        this.events = events;
        this.rotation = rotation;
        this.props = props;
        this.clock = clock;
        this.publisher = publisher;
    }

    @Transactional
    public IncidentView create(CreateIncident in) {
        Instant now = clock.instant();
        String assignee = rotation.next();
        Incident incident = incidents.saveAndFlush(new Incident(in.serviceName().trim(), in.severity(),
            in.summary().trim(), assignee, now, now.plus(props.slaFor(in.severity()))));
        record(incident, EventType.CREATED, actorOr(in.reporter()),
            in.severity() + " incident opened, paged " + assignee + ", ack due in " + human(props.slaFor(in.severity())));
        return view(incident);
    }

    @Transactional(readOnly = true)
    public IncidentView get(UUID id) { return view(load(id)); }

    @Transactional(readOnly = true)
    public PageResponse<IncidentView> search(Status status, Severity severity, String assignee, String q,
                                             int page, int size, boolean oldestFirst) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Sort sort = Sort.by(oldestFirst ? Sort.Direction.ASC : Sort.Direction.DESC, "createdAt").and(Sort.by("id"));
        Page<Incident> result = incidents.findAll(filter(status, severity, assignee, q), PageRequest.of(safePage, safeSize, sort));
        Instant now = clock.instant();
        return new PageResponse<>(result.getContent().stream().map(i -> IncidentView.of(i, now)).toList(),
            safePage, safeSize, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public IncidentView changeStatus(UUID id, Status next, Long expectedVersion, String actor, Postmortem postmortem) {
        Incident incident = load(id);
        checkVersion(incident, expectedVersion);
        incident.transitionTo(next, clock.instant(), postmortem);
        incidents.saveAndFlush(incident); // flush now so a lost race surfaces here, not after we've broadcast
        String message = switch (next) {
            case ACKNOWLEDGED -> "Acknowledged " + human(Duration.between(incident.getCreatedAt(), incident.getAcknowledgedAt())) + " after opening";
            case RESOLVED -> "Resolved. Root cause: " + postmortem.rootCause();
            default -> next.name();
        };
        record(incident, EventType.valueOf(next.name()), actorOr(actor), message);
        return view(incident);
    }

    @Transactional
    public IncidentView reassign(UUID id, String assignee, Long expectedVersion, String actor) {
        Incident incident = load(id);
        checkVersion(incident, expectedVersion);
        String before = incident.getAssignee();
        incident.assignTo(assignee.trim(), clock.instant());
        incidents.saveAndFlush(incident);
        record(incident, EventType.ASSIGNED, actorOr(actor), "Reassigned from " + before + " to " + incident.getAssignee());
        return view(incident);
    }

    @Transactional
    public TimelineEntry comment(UUID id, String author, String message) {
        Incident incident = load(id);
        return record(incident, EventType.COMMENTED, author.trim(), message.trim());
    }

    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(UUID id) {
        load(id);
        return events.findByIncidentIdOrderByOccurredAtAscIdAsc(id).stream().map(TimelineEntry::of).toList();
    }

    /** Called by the SLA job, one incident per transaction. Returns false if there was nothing to do. */
    @Transactional
    public boolean escalateIfOverdue(UUID id) {
        Incident incident = load(id);
        Instant now = clock.instant();
        if (!incident.isAckOverdue(now) || incident.getEscalationLevel() >= props.maxEscalationLevel()) return false;
        String previous = incident.getAssignee();
        String next = rotation.after(previous);
        incident.escalate(next, now, now.plus(props.slaFor(incident.getSeverity())));
        incidents.saveAndFlush(incident);
        record(incident, EventType.ESCALATED, SYSTEM, "Not acknowledged by " + previous + " within SLA; escalated to L"
            + incident.getEscalationLevel() + " and paged " + next);
        return true;
    }

    private TimelineEntry record(Incident incident, EventType type, String actor, String message) {
        IncidentEvent saved = events.save(new IncidentEvent(incident.getId(), type, actor, message, clock.instant()));
        TimelineEntry entry = TimelineEntry.of(saved);
        publisher.publishEvent(new IncidentChanged(view(incident), entry));
        return entry;
    }

    private void checkVersion(Incident incident, Long expectedVersion) {
        if (expectedVersion != null && !expectedVersion.equals(incident.getVersion())) {
            throw new VersionConflictException(expectedVersion, incident.getVersion());
        }
    }

    private Incident load(UUID id) { return incidents.findById(id).orElseThrow(() -> new NotFoundException(id)); }

    private IncidentView view(Incident incident) { return IncidentView.of(incident, clock.instant()); }

    private static String actorOr(String actor) { return actor == null || actor.isBlank() ? "anonymous" : actor.trim(); }

    /** Same truncation as the MTTA/MTTR stats (Duration.toSeconds), so the timeline and stats agree. */
    static String human(Duration d) {
        long s = Math.max(0, d.toSeconds());
        if (s < 120) return s + "s";
        if (s < 7200) return (s / 60) + "m" + (s % 60 == 0 ? "" : " " + (s % 60) + "s");
        return (s / 3600) + "h" + (s % 3600 / 60 == 0 ? "" : " " + (s % 3600 / 60) + "m");
    }

    private static Specification<Incident> filter(Status status, Severity severity, String assignee, String q) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (status != null) where.add(cb.equal(root.get("status"), status));
            if (severity != null) where.add(cb.equal(root.get("severity"), severity));
            if (assignee != null && !assignee.isBlank()) where.add(cb.equal(root.get("assignee"), assignee.trim()));
            if (q != null && !q.isBlank()) {
                String pattern = "%" + q.trim().toLowerCase()
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                where.add(cb.or(cb.like(cb.lower(root.get("serviceName")), pattern, '\\'),
                                cb.like(cb.lower(root.get("summary")), pattern, '\\')));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }
}

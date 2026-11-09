package dev.nikhil.incidentops.web;

import dev.nikhil.incidentops.api.Dtos.AddComment;
import dev.nikhil.incidentops.api.Dtos.ChangeAssignee;
import dev.nikhil.incidentops.api.Dtos.ChangeStatus;
import dev.nikhil.incidentops.api.Dtos.CreateIncident;
import dev.nikhil.incidentops.api.Dtos.IncidentView;
import dev.nikhil.incidentops.api.Dtos.OnCall;
import dev.nikhil.incidentops.api.Dtos.PageResponse;
import dev.nikhil.incidentops.api.Dtos.Stats;
import dev.nikhil.incidentops.api.Dtos.TimelineEntry;
import dev.nikhil.incidentops.config.IncidentopsProperties;
import dev.nikhil.incidentops.domain.Severity;
import dev.nikhil.incidentops.domain.Status;
import dev.nikhil.incidentops.service.IncidentService;
import dev.nikhil.incidentops.service.OnCallRotation;
import dev.nikhil.incidentops.service.StatsService;
import dev.nikhil.incidentops.stream.SseHub;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class IncidentController {
    private final IncidentService service;
    private final StatsService stats;
    private final SseHub hub;
    private final OnCallRotation rotation;
    private final IncidentopsProperties props;

    public IncidentController(IncidentService service, StatsService stats, SseHub hub,
                              OnCallRotation rotation, IncidentopsProperties props) {
        this.service = service;
        this.stats = stats;
        this.hub = hub;
        this.rotation = rotation;
        this.props = props;
    }

    @GetMapping("/incidents")
    public PageResponse<IncidentView> list(@RequestParam(required = false) Status status,
                                           @RequestParam(required = false) Severity severity,
                                           @RequestParam(required = false) String assignee,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size,
                                           @RequestParam(defaultValue = "newest") String sort) {
        return service.search(status, severity, assignee, q, page, size, "oldest".equalsIgnoreCase(sort));
    }

    @GetMapping("/incidents/{id}")
    public IncidentView get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping("/incidents")
    public ResponseEntity<IncidentView> create(@Valid @RequestBody CreateIncident input) {
        IncidentView created = service.create(input);
        return ResponseEntity.created(URI.create("/api/incidents/" + created.id())).body(created);
    }

    @PatchMapping("/incidents/{id}/status")
    public IncidentView changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeStatus input) {
        return service.changeStatus(id, input.status(), input.expectedVersion(), input.actor(),
            input.postmortem() == null ? null : input.postmortem().toDomain());
    }

    @PatchMapping("/incidents/{id}/assignee")
    public IncidentView reassign(@PathVariable UUID id, @Valid @RequestBody ChangeAssignee input) {
        return service.reassign(id, input.assignee(), input.expectedVersion(), input.actor());
    }

    @GetMapping("/incidents/{id}/events")
    public List<TimelineEntry> timeline(@PathVariable UUID id) { return service.timeline(id); }

    @PostMapping("/incidents/{id}/comments")
    public ResponseEntity<TimelineEntry> comment(@PathVariable UUID id, @Valid @RequestBody AddComment input) {
        return ResponseEntity.status(201).body(service.comment(id, input.author(), input.message()));
    }

    @GetMapping("/stats")
    public Stats stats() { return stats.stats(); }

    /** Kept from v1 so old clients keep working. */
    @GetMapping("/metrics")
    public Map<Status, Long> metrics() { return stats.countsByStatus(); }

    @GetMapping("/oncall")
    public OnCall oncall() {
        Map<Severity, Long> sla = new EnumMap<>(Severity.class);
        props.ackSla().forEach((k, v) -> sla.put(k, v.toSeconds()));
        return new OnCall(rotation.responders(), sla, props.maxEscalationLevel());
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() { return hub.subscribe(); }
}

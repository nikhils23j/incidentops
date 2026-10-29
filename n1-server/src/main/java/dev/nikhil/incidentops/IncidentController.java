package dev.nikhil.incidentops;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173")
public class IncidentController {
    private final IncidentRepository repository;

    public IncidentController(IncidentRepository repository) { this.repository = repository; }

    public record CreateIncident(
        @NotBlank @Size(max = 80) String serviceName,
        @NotNull Severity severity,
        @NotBlank @Size(max = 240) String summary
    ) {}
    public record ChangeStatus(@NotNull Status status) {}

    @GetMapping("/incidents")
    public List<Incident> list(@RequestParam(required = false) Status status) {
        return repository.findAll().stream()
            .filter(i -> status == null || i.getStatus() == status)
            .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
            .toList();
    }

    @GetMapping("/incidents/{id}")
    public Incident get(@PathVariable UUID id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found"));
    }

    @PostMapping("/incidents")
    public ResponseEntity<Incident> create(@Valid @RequestBody CreateIncident input) {
        Incident created = repository.save(new Incident(
            input.serviceName().trim(), input.severity(), input.summary().trim()));
        return ResponseEntity.created(URI.create("/api/incidents/" + created.getId())).body(created);
    }

    @PatchMapping("/incidents/{id}/status")
    public Incident changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeStatus input) {
        Incident incident = get(id);
        incident.moveTo(input.status());
        return repository.save(incident);
    }

    @GetMapping("/metrics")
    public Map<Status, Long> metrics() {
        Map<Status, Long> counts = repository.findAll().stream()
            .collect(Collectors.groupingBy(Incident::getStatus, Collectors.counting()));
        return Arrays.stream(Status.values()).collect(Collectors.toMap(s -> s, s -> counts.getOrDefault(s, 0L)));
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(IllegalStateException error) {
        return Map.of("error", error.getMessage());
    }
}

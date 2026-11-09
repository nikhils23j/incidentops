package dev.nikhil.incidentops.service;

import dev.nikhil.incidentops.api.Dtos.Stats;
import dev.nikhil.incidentops.domain.Incident;
import dev.nikhil.incidentops.domain.IncidentRepository;
import dev.nikhil.incidentops.domain.Status;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MTTA = created -> acknowledged, MTTR = created -> resolved. Computed in memory: fine for a demo-sized H2. */
@Service
public class StatsService {
    private final IncidentRepository incidents;
    private final Clock clock;

    public StatsService(IncidentRepository incidents, Clock clock) {
        this.incidents = incidents;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<Status, Long> countsByStatus() {
        Map<Status, Long> counts = new EnumMap<>(Status.class);
        for (Status s : Status.values()) counts.put(s, incidents.countByStatus(s));
        return counts;
    }

    @Transactional(readOnly = true)
    public Stats stats() {
        List<Incident> all = incidents.findAll();
        Instant now = clock.instant();
        List<Long> tta = all.stream().filter(i -> i.getAcknowledgedAt() != null)
            .map(i -> Duration.between(i.getCreatedAt(), i.getAcknowledgedAt()).toSeconds()).sorted().toList();
        List<Long> ttr = all.stream().filter(i -> i.getResolvedAt() != null)
            .map(i -> Duration.between(i.getCreatedAt(), i.getResolvedAt()).toSeconds()).sorted().toList();
        Map<Status, Long> byStatus = countsByStatus();
        return new Stats(byStatus, byStatus.get(Status.OPEN),
            all.stream().filter(i -> i.isAckOverdue(now)).count(),
            all.stream().mapToLong(Incident::getEscalationLevel).sum(),
            mean(tta), p50(tta), mean(ttr), p50(ttr), tta.size(), ttr.size());
    }

    private static Double mean(List<Long> xs) {
        return xs.isEmpty() ? null : xs.stream().mapToLong(Long::longValue).average().orElseThrow();
    }

    /** Nearest-rank median on an already-sorted list. */
    private static Long p50(List<Long> sorted) {
        return sorted.isEmpty() ? null : sorted.get((int) Math.ceil(0.5 * sorted.size()) - 1);
    }
}

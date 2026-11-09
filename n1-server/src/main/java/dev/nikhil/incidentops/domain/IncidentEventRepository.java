package dev.nikhil.incidentops.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Deliberately not a JpaRepository: there is no update or delete method for timeline events. */
public interface IncidentEventRepository extends Repository<IncidentEvent, Long> {
    IncidentEvent save(IncidentEvent event);
    List<IncidentEvent> findByIncidentIdOrderByOccurredAtAscIdAsc(UUID incidentId);
    long countByIncidentIdAndType(UUID incidentId, EventType type);
}

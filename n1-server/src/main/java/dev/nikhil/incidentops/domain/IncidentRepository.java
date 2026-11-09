package dev.nikhil.incidentops.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface IncidentRepository extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {
    List<Incident> findByStatusAndAckDueAtBeforeAndEscalationLevelLessThan(Status status, Instant now, int maxLevel);
    long countByStatus(Status status);
}

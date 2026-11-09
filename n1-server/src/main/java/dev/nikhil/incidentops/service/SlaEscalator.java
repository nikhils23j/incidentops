package dev.nikhil.incidentops.service;

import dev.nikhil.incidentops.config.IncidentopsProperties;
import dev.nikhil.incidentops.domain.Incident;
import dev.nikhil.incidentops.domain.IncidentRepository;
import dev.nikhil.incidentops.domain.Status;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically escalates incidents nobody acknowledged within the severity's SLA. */
@Component
public class SlaEscalator {
    private static final Logger log = LoggerFactory.getLogger(SlaEscalator.class);
    private final IncidentRepository incidents;
    private final IncidentService service;
    private final IncidentopsProperties props;
    private final Clock clock;

    public SlaEscalator(IncidentRepository incidents, IncidentService service, IncidentopsProperties props, Clock clock) {
        this.incidents = incidents;
        this.service = service;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${incidentops.sla.scan-interval-ms:5000}",
               fixedDelayString = "${incidentops.sla.scan-interval-ms:5000}")
    public void scheduledScan() { escalateOverdue(); }

    public int escalateOverdue() {
        int escalated = 0;
        for (UUID id : incidents.findByStatusAndAckDueAtBeforeAndEscalationLevelLessThan(
                Status.OPEN, clock.instant(), props.maxEscalationLevel()).stream().map(Incident::getId).toList()) {
            try {
                if (service.escalateIfOverdue(id)) escalated++;
            } catch (ObjectOptimisticLockingFailureException | dev.nikhil.incidentops.domain.IllegalTransitionException e) {
                // A human acked it between our query and our update. That's the outcome we want; skip it.
                log.info("Skipped escalation of {}: changed concurrently", id);
            }
        }
        if (escalated > 0) log.info("SLA scan escalated {} incident(s)", escalated);
        return escalated;
    }
}

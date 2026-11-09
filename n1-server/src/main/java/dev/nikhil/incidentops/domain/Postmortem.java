package dev.nikhil.incidentops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Small blameless-postmortem template captured when an incident is resolved. */
@Embeddable
public record Postmortem(
    @Column(name = "pm_root_cause", length = 1000) String rootCause,
    @Column(name = "pm_impact", length = 1000) String impact,
    @Column(name = "pm_follow_up", length = 1000) String followUp
) {}

package dev.nikhil.incidentops.domain;

import java.util.Set;

/** OPEN -> ACKNOWLEDGED -> RESOLVED. RESOLVED is terminal; no skipping, no going back. */
public enum Status {
    OPEN, ACKNOWLEDGED, RESOLVED;

    public Set<Status> allowedNext() {
        return switch (this) {
            case OPEN -> Set.of(ACKNOWLEDGED);
            case ACKNOWLEDGED -> Set.of(RESOLVED);
            case RESOLVED -> Set.of();
        };
    }

    public boolean canMoveTo(Status next) { return allowedNext().contains(next); }
}

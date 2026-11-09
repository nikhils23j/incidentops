package dev.nikhil.incidentops.service;

public class VersionConflictException extends RuntimeException {
    private final long currentVersion;

    public VersionConflictException(long expected, long current) {
        super("Incident changed since you loaded it (expected version " + expected + ", current " + current + ")");
        this.currentVersion = current;
    }

    public long currentVersion() { return currentVersion; }
}

package dev.nikhil.incidentops.domain;

public class PostmortemRequiredException extends RuntimeException {
    public PostmortemRequiredException() {
        super("Resolving an incident requires a postmortem (rootCause, impact, followUp)");
    }
}

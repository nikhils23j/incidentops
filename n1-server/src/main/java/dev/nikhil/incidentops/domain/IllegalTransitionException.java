package dev.nikhil.incidentops.domain;

public class IllegalTransitionException extends RuntimeException {
    private final Status from;
    private final Status to;

    public IllegalTransitionException(Status from, Status to) {
        super("Cannot move incident from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public Status from() { return from; }
    public Status to() { return to; }
}

package dev.nikhil.incidentops.service;

import java.util.UUID;

public class NotFoundException extends RuntimeException {
    public NotFoundException(UUID id) { super("Incident " + id + " not found"); }
}
